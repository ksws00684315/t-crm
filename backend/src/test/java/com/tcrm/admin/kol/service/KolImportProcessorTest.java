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
import com.tcrm.admin.task.api.ImportReason;
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
  private static final long TEMPLATE = 77L;

  private final KolMapper kols = mock(KolMapper.class);
  private final KolWriter writer = mock(KolWriter.class);
  private final BdUserApi bdUsers = mock(BdUserApi.class);
  private final LinkTemplateService templates = mock(LinkTemplateService.class);

  private KolImportProcessor processor(boolean own) {
    return new KolImportProcessor(
        new ImportActor(own ? BD : ADMIN, own), kols, writer, bdUsers, templates);
  }

  /** Row in template order: code, name, email, country, BD, rate, platform, handle, followers. */
  private static List<String> row(String... cells) {
    List<String> row = new ArrayList<>(List.of(cells));
    while (row.size() < KolImportColumn.values().length) {
      row.add("");
    }
    return row;
  }

  /** Row of a pre-task-11 file: the first ten columns only. */
  private static List<String> legacyRow(String... cells) {
    List<String> row = new ArrayList<>(List.of(cells));
    while (row.size() < KolImportColumn.LEGACY_COLUMNS) {
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
    assertThat(kol.getRegisterSource()).isEqualTo(1);
    assertThat(SocialAccountsJson.read(kol.getSocialAccounts()))
        .containsExactly(new SocialAccountDTO("TikTok", "@alice", null, 12_000L));
  }

  @Test
  void shouldImportLegacyFile_whenRowHasOnlyTenColumns() {
    assertThat(processor(false).process(legacyRow("", "Alice", "alice@example.com", ""))).isEmpty();
    KolDO kol = saved(null, ADMIN);
    assertThat(kol.getCountry()).isNull();
    assertThat(kol.getWhatsapp()).isNull();
    assertThat(kol.getLinkTemplateId()).isNull();
    assertThat(kol.getInviteCode()).isNull();
    assertThat(kol.getSheetUrl()).isNull();
  }

  @Test
  void shouldStoreProfileColumns_whenTemplateCodeIsUsable() {
    when(templates.usableTemplateByCode("kakobuy"))
        .thenReturn(
            new LinkTemplateDTO(
                TEMPLATE,
                "kakobuy",
                "kakobuy",
                "https://item.kakobuy.com/i/{PRODUCT_ID}",
                "WEIDIAN",
                0,
                1,
                null));
    assertThat(
            processor(false)
                .process(
                    row(
                        "",
                        "Alice",
                        "alice@example.com",
                        "US",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "+12345678",
                        "kakobuy",
                        "aff_01",
                        "https://docs.google.com/spreadsheets/d/1/edit")))
        .isEmpty();
    KolDO kol = saved(null, ADMIN);
    assertThat(kol.getWhatsapp()).isEqualTo("+12345678");
    assertThat(kol.getLinkTemplateId()).isEqualTo(TEMPLATE);
    assertThat(kol.getInviteCode()).isEqualTo("aff_01");
    assertThat(kol.getSheetUrl()).isEqualTo("https://docs.google.com/spreadsheets/d/1/edit");
  }

  @Test
  void shouldReportEveryProfileProblem_inColumnOrder() {
    var p = processor(false);
    assertThat(
            p.process(
                row(
                    "",
                    "Alice",
                    "alice@example.com",
                    "US",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "12345",
                    "kakobuy",
                    "aff code!",
                    "http://example.com/sheet")))
        .containsExactly(
            ImportReason.of(KolImportProcessor.WHATSAPP_FORMAT),
            ImportReason.of(KolImportProcessor.TEMPLATE_INVALID),
            ImportReason.of(KolImportProcessor.INVITE_CODE_FORMAT),
            ImportReason.of(KolImportProcessor.SHEET_URL_FORMAT));
    verify(writer, never()).insert(any(), any(), any());
  }

  @Test
  void shouldReportTemplate_whenCodeIsUnknownOrDisabled() {
    // The service returns null for unknown codes and disabled templates alike.
    when(templates.usableTemplateByCode("ghost")).thenReturn(null);
    when(templates.usableTemplateByCode("off")).thenReturn(null);
    var p = processor(false);
    assertThat(p.process(row("", "A", "a@x.io", "US", "", "", "", "", "", "", "", "ghost")))
        .containsExactly(ImportReason.of(KolImportProcessor.TEMPLATE_INVALID));
    assertThat(p.process(row("", "A", "b@x.io", "US", "", "", "", "", "", "", "", "off")))
        .containsExactly(ImportReason.of(KolImportProcessor.TEMPLATE_INVALID));
  }

  @Test
  void shouldKeepSuppliedCodeUppercased_whenCodeIsFree() {
    assertThat(processor(false).process(row("kol-aaa042", "A", "a@x.io", "CN"))).isEmpty();
    KolDO kol = saved("KOL-AAA042", ADMIN);
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
        .containsExactly(ImportReason.of(KolImportProcessor.BD_INVALID));
    when(bdUsers.getByUsername("ops")).thenReturn(new BdUserDTO(2L, "ops", "Ops", false));
    assertThat(p.process(row("", "A", "b@x.io", "CN", "ops")))
        .containsExactly(ImportReason.of(KolImportProcessor.BD_INVALID));
    verify(writer, never()).insert(any(), any(), any());
  }

  @Test
  void shouldListEveryReasonInColumnOrder_whenRowHasManyProblems() {
    String longText = "x".repeat(501);
    assertThat(
            processor(false)
                .process(row("K1", "", "bad", "XX", "", "101", "TikTok", "", "-1", longText)))
        .containsExactly(
            ImportReason.of(KolImportProcessor.CODE_FORMAT),
            ImportReason.of(KolImportProcessor.NAME_REQUIRED),
            ImportReason.of(KolImportProcessor.EMAIL_FORMAT),
            ImportReason.of(KolImportProcessor.COUNTRY_FORMAT),
            ImportReason.of(KolImportProcessor.RATE_FORMAT),
            ImportReason.of(KolImportProcessor.SOCIAL_PAIR),
            ImportReason.of(KolImportProcessor.FOLLOWERS_FORMAT),
            ImportReason.of(KolImportProcessor.REMARK_LENGTH));
    verify(writer, never()).insert(any(), any(), any());
  }

  @Test
  void shouldReportLengthLimits_andAllowMissingCountry() {
    var p = processor(false);
    String longEmail = "a".repeat(120) + "@example.com";
    assertThat(p.process(row("", "x".repeat(65), "", "")))
        .containsExactly(
            ImportReason.of(KolImportProcessor.NAME_LENGTH),
            ImportReason.of(KolImportProcessor.EMAIL_REQUIRED));
    assertThat(p.process(row("", "A", longEmail, "USA", "", "", "p".repeat(33), "h".repeat(129))))
        .containsExactly(
            ImportReason.of(KolImportProcessor.EMAIL_LENGTH),
            ImportReason.of(KolImportProcessor.COUNTRY_FORMAT),
            ImportReason.of(KolImportProcessor.PLATFORM_LENGTH),
            ImportReason.of(KolImportProcessor.HANDLE_LENGTH));
  }

  @Test
  void shouldAcceptBoundaryLengths() {
    String email = "a".repeat(116) + "@example.com";
    assertThat(email).hasSize(128);
    assertThat(
            processor(false)
                .process(
                    row(
                        "KOL-ZZZ99999999",
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
        .containsExactly(ImportReason.of(KolImportProcessor.FOLLOWERS_WITHOUT_HANDLE));
    assertThat(processor(false).process(row("", "A", "a@x.io", "US", "", "", "", "@a")))
        .containsExactly(ImportReason.of(KolImportProcessor.SOCIAL_PAIR));
  }

  @Test
  void shouldFailSecondOccurrence_whenCodeOrEmailRepeatsInFile() {
    var p = processor(false);
    assertThat(p.process(row("KOL-AAA100", "A", "a@x.io", "US"))).isEmpty();
    assertThat(p.process(row("kol-aaa100", "B", "A@X.IO", "US")))
        .containsExactly(
            ImportReason.of(KolImportProcessor.CODE_DUPLICATE),
            ImportReason.of(KolImportProcessor.EMAIL_DUPLICATE));
    assertThat(p.process(row("KOL-AAA100", "C", "c@x.io", "US")))
        .containsExactly(ImportReason.of(KolImportProcessor.CODE_DUPLICATE));
  }

  @Test
  void shouldReportExisting_whenCodeOrEmailIsAlreadyStored() {
    when(kols.existsCode("KOL-AAA100")).thenReturn(true);
    when(kols.selectCount(any())).thenReturn(1L);
    assertThat(processor(false).process(row("KOL-AAA100", "A", "a@x.io", "US")))
        .containsExactly(
            ImportReason.of(KolImportProcessor.CODE_EXISTS),
            ImportReason.of(KolImportProcessor.EMAIL_EXISTS));
  }

  @Test
  void shouldMapConcurrentDuplicates_whenWriterRejectsInsert() {
    when(writer.insert(any(), any(), any()))
        .thenThrow(new BusinessException(ErrorCode.KOL_EMAIL_EXISTS))
        .thenThrow(new BusinessException(ErrorCode.KOL_CODE_EXISTS))
        .thenThrow(new BusinessException(ErrorCode.COMMON_CONFLICT));
    var p = processor(false);
    assertThat(p.process(row("", "A", "a@x.io", "US")))
        .containsExactly(ImportReason.of(KolImportProcessor.EMAIL_EXISTS));
    assertThat(p.process(row("", "A", "b@x.io", "US")))
        .containsExactly(ImportReason.of(KolImportProcessor.CODE_EXISTS));
    assertThatThrownBy(() -> p.process(row("", "A", "c@x.io", "US")))
        .isInstanceOf(BusinessException.class);
  }
}
