package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KolImportProcessorTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolDO.class);
  }

  private static final long ADMIN = 1L;
  private static final long BD = 5L;

  private final KolMapper kols = mock(KolMapper.class);
  private final KolWriter writer = mock(KolWriter.class);
  private final BdUserApi bdUsers = mock(BdUserApi.class);

  private KolImportProcessor processor(boolean own) {
    return new KolImportProcessor(new ImportActor(own ? BD : ADMIN, own), kols, writer, bdUsers);
  }

  /** Row in template order: code, name, email, country, BD, rate, platform, handle, followers. */
  private static List<String> row(String... cells) {
    List<String> row = new ArrayList<>(List.of(cells));
    while (row.size() < KolImportColumn.values().length) {
      row.add("");
    }
    return row;
  }

  private static List<String> valid() {
    return row(
        "", "Alice", "Alice@Example.com", "us", "", "15.5", "TikTok", "@alice", "12,000", "VIP");
  }

  private KolDO saved(String code, long actor) {
    var kol = ArgumentCaptor.forClass(KolDO.class);
    if (code == null) {
      verify(writer).insert(kol.capture(), isNull(), eq(actor));
    } else {
      verify(writer).insert(kol.capture(), eq(code), eq(actor));
    }
    return kol.getValue();
  }

  @Test
  void shouldInsertNormalizedKol_whenRowIsValid() {
    assertThat(processor(false).process(valid())).isEmpty();
    KolDO kol = saved(null, ADMIN);
    assertThat(kol.getName()).isEqualTo("Alice");
    assertThat(kol.getEmail()).isEqualTo("alice@example.com");
    assertThat(kol.getCountry()).isEqualTo("US");
    assertThat(kol.getBdUserId()).isNull();
    assertThat(kol.getDefaultRate()).isEqualByComparingTo("0.1550");
    assertThat(kol.getStatus()).isEqualTo(1);
    assertThat(kol.getRemark()).isEqualTo("VIP");
    assertThat(SocialAccountsJson.read(kol.getSocialAccounts()))
        .containsExactly(new SocialAccountDTO("TikTok", "@alice", null, 12_000L));
  }

  @Test
  void shouldKeepSuppliedCodeUppercased_whenCodeIsFree() {
    assertThat(processor(false).process(row("kol0042", "A", "a@x.io", "CN"))).isEmpty();
    KolDO kol = saved("KOL0042", ADMIN);
    assertThat(kol.getSocialAccounts()).isEqualTo("[]");
    assertThat(kol.getDefaultRate()).isNull();
    assertThat(kol.getRemark()).isNull();
  }

  @Test
  void shouldAssignUsableBd_whenAdminNamesBdUsername() {
    when(bdUsers.getByUsername("BD5")).thenReturn(new BdUserDTO(BD, "bd5", "BD", true));
    assertThat(processor(false).process(row("", "A", "a@x.io", "CN", "BD5"))).isEmpty();
    assertThat(saved(null, ADMIN).getBdUserId()).isEqualTo(BD);
  }

  @Test
  void shouldAssignSubmitterAndIgnoreColumn_whenBdImports() {
    assertThat(processor(true).process(row("", "A", "a@x.io", "CN", "nobody"))).isEmpty();
    assertThat(saved(null, BD).getBdUserId()).isEqualTo(BD);
    verify(bdUsers, never()).getByUsername(anyString());
  }

  @Test
  void shouldReportBd_whenUsernameIsUnknownOrNotUsable() {
    var p = processor(false);
    assertThat(p.process(row("", "A", "a@x.io", "CN", "ghost")))
        .containsExactly(KolImportProcessor.BD_INVALID);
    when(bdUsers.getByUsername("ops")).thenReturn(new BdUserDTO(2L, "ops", "Ops", false));
    assertThat(p.process(row("", "A", "b@x.io", "CN", "ops")))
        .containsExactly(KolImportProcessor.BD_INVALID);
    verify(writer, never()).insert(any(), any(), any());
  }

  @Test
  void shouldListEveryReasonInColumnOrder_whenRowHasManyProblems() {
    String longText = "x".repeat(501);
    assertThat(
            processor(false)
                .process(row("K1", "", "bad", "XX", "", "101", "TikTok", "", "-1", longText)))
        .containsExactly(
            KolImportProcessor.CODE_FORMAT,
            KolImportProcessor.NAME_REQUIRED,
            KolImportProcessor.EMAIL_FORMAT,
            KolImportProcessor.COUNTRY_FORMAT,
            KolImportProcessor.RATE_FORMAT,
            KolImportProcessor.SOCIAL_PAIR,
            KolImportProcessor.FOLLOWERS_FORMAT,
            KolImportProcessor.REMARK_LENGTH);
    verify(writer, never()).insert(any(), any(), any());
  }

  @Test
  void shouldReportRequiredAndLengthLimits() {
    var p = processor(false);
    assertThat(p.process(row("", "x".repeat(65), "", "")))
        .containsExactly(
            KolImportProcessor.NAME_LENGTH,
            KolImportProcessor.EMAIL_REQUIRED,
            KolImportProcessor.COUNTRY_REQUIRED);
    String longEmail = "a".repeat(120) + "@example.com";
    assertThat(p.process(row("", "A", longEmail, "USA", "", "", "p".repeat(33), "h".repeat(129))))
        .containsExactly(
            KolImportProcessor.EMAIL_LENGTH,
            KolImportProcessor.COUNTRY_FORMAT,
            KolImportProcessor.PLATFORM_LENGTH,
            KolImportProcessor.HANDLE_LENGTH);
  }

  @Test
  void shouldAcceptBoundaryLengths() {
    String email = "a".repeat(116) + "@example.com";
    assertThat(email).hasSize(128);
    assertThat(
            processor(false)
                .process(
                    row(
                        "KOL99999999",
                        "名".repeat(64),
                        email,
                        "US",
                        "",
                        "100",
                        "p".repeat(32),
                        "h".repeat(128),
                        "0",
                        "备".repeat(500))))
        .isEmpty();
  }

  @Test
  void shouldRejectFollowers_whenHandleIsEmpty() {
    assertThat(processor(false).process(row("", "A", "a@x.io", "US", "", "", "", "", "10")))
        .containsExactly(KolImportProcessor.FOLLOWERS_WITHOUT_HANDLE);
    assertThat(processor(false).process(row("", "A", "a@x.io", "US", "", "", "", "@a")))
        .containsExactly(KolImportProcessor.SOCIAL_PAIR);
  }

  @Test
  void shouldFailSecondOccurrence_whenCodeOrEmailRepeatsInFile() {
    var p = processor(false);
    assertThat(p.process(row("KOL0100", "A", "a@x.io", "US"))).isEmpty();
    assertThat(p.process(row("kol0100", "B", "A@X.IO", "US")))
        .containsExactly(KolImportProcessor.CODE_DUPLICATE, KolImportProcessor.EMAIL_DUPLICATE);
    assertThat(p.process(row("KOL0100", "C", "c@x.io", "US")))
        .containsExactly(KolImportProcessor.CODE_DUPLICATE);
  }

  @Test
  void shouldReportExisting_whenCodeOrEmailIsAlreadyStored() {
    when(kols.existsCode("KOL0100")).thenReturn(true);
    when(kols.selectCount(any())).thenReturn(1L);
    assertThat(processor(false).process(row("KOL0100", "A", "a@x.io", "US")))
        .containsExactly(KolImportProcessor.CODE_EXISTS, KolImportProcessor.EMAIL_EXISTS);
  }

  @Test
  void shouldMapConcurrentDuplicates_whenWriterRejectsInsert() {
    when(writer.insert(any(), any(), any()))
        .thenThrow(new BusinessException(ErrorCode.KOL_EMAIL_EXISTS))
        .thenThrow(new BusinessException(ErrorCode.KOL_CODE_EXISTS))
        .thenThrow(new BusinessException(ErrorCode.COMMON_CONFLICT));
    var p = processor(false);
    assertThat(p.process(row("", "A", "a@x.io", "US")))
        .containsExactly(KolImportProcessor.EMAIL_EXISTS);
    assertThat(p.process(row("", "A", "b@x.io", "US")))
        .containsExactly(KolImportProcessor.CODE_EXISTS);
    assertThatThrownBy(() -> p.process(row("", "A", "c@x.io", "US")))
        .isInstanceOf(BusinessException.class);
  }
}
