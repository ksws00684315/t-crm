package com.tcrm.admin.kollibrary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kollibrary.mapper.LibraryKolContentMapper;
import com.tcrm.admin.kollibrary.mapper.LibraryKolMapper;
import com.tcrm.admin.kollibrary.model.LibraryKolContentDO;
import com.tcrm.admin.kollibrary.model.LibraryKolDO;
import com.tcrm.admin.kollibrary.model.LibraryOutreachStatus;
import com.tcrm.admin.kollibrary.model.LibraryPlatform;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Profile rules of the 2026-11-10 form revision (requirement 1.2.6 ①, task 146 §2.1). */
class LibraryKolServiceTest {
  @BeforeAll
  static void tables() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), LibraryKolDO.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), LibraryKolContentDO.class);
  }

  private final LibraryKolMapper kols = mock(LibraryKolMapper.class);
  private final LibraryKolContentMapper contents = mock(LibraryKolContentMapper.class);
  private final LibraryKolService service =
      new LibraryKolService(kols, contents, mock(CurrentUserContext.class));

  private static LibraryKolCommand command(
      Long followers,
      String profileUrl,
      String owner,
      List<String> categories,
      String outreach,
      String followUpRemark) {
    return new LibraryKolCommand(
        "TIKTOK",
        "@BeautyByAmy",
        "Amy",
        followers,
        "US",
        profileUrl,
        categories,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        null,
        outreach,
        owner,
        null,
        "NOT_SENT",
        null,
        null,
        followUpRemark);
  }

  private static LibraryKolCommand valid() {
    return command(
        12000L, "https://www.tiktok.com/@beautybyamy", "Amy BD", List.of(), null, "call friday");
  }

  private static void assertValidationFails(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @Test
  void shouldRequireFollowers_onCreate() {
    var missing = command(null, "https://www.tiktok.com/@x", "Amy BD", null, null, null);
    assertValidationFails(() -> service.applyProfile(new LibraryKolDO(), missing, true));
    var negative = command(-1L, "https://www.tiktok.com/@x", "Amy BD", null, null, null);
    assertValidationFails(() -> service.applyProfile(new LibraryKolDO(), negative, true));
  }

  @Test
  void shouldRequireProfileUrl_onCreate() {
    var missing = command(10L, null, "Amy BD", null, null, null);
    assertValidationFails(() -> service.applyProfile(new LibraryKolDO(), missing, true));
    var blank = command(10L, "  ", "Amy BD", null, null, null);
    assertValidationFails(() -> service.applyProfile(new LibraryKolDO(), blank, true));
    var notHttp = command(10L, "www.tiktok.com/@x", "Amy BD", null, null, null);
    assertValidationFails(() -> service.applyProfile(new LibraryKolDO(), notHttp, true));
  }

  @Test
  void shouldRequireFollowUpOwner_onCreate() {
    var missing = command(10L, "https://www.tiktok.com/@x", " ", null, null, null);
    assertValidationFails(() -> service.applyProfile(new LibraryKolDO(), missing, true));
  }

  @Test
  void shouldRequireFollowersAndProfileUrl_whenEditingAnOlderRowWithoutThem() {
    LibraryKolDO old = new LibraryKolDO();
    old.setOutreachStatus(LibraryOutreachStatus.REPLIED);
    var unchanged = command(null, null, "Amy BD", List.of(), null, null);
    assertValidationFails(() -> service.applyProfile(old, unchanged, false));
  }

  @Test
  void shouldSaveEmptyCategoriesAndDefaultTheOutreach_onCreate() {
    LibraryKolDO row = new LibraryKolDO();
    service.applyProfile(row, valid(), true);
    assertThat(row.getCategories()).isEqualTo("[]");
    assertThat(row.getOutreachStatus()).isEqualTo(LibraryOutreachStatus.NOT_CONTACTED);
    assertThat(row.getPlatform()).isEqualTo(LibraryPlatform.TIKTOK);
    assertThat(row.getKolUsername()).isEqualTo("beautybyamy");
    assertThat(row.getFollowUpRemark()).isEqualTo("call friday");

    LibraryKolDO nullCategories = new LibraryKolDO();
    service.applyProfile(
        nullCategories, command(1L, "https://www.tiktok.com/@x", "Amy BD", null, "", null), true);
    assertThat(nullCategories.getCategories()).isEqualTo("[]");
    assertThat(nullCategories.getOutreachStatus()).isEqualTo(LibraryOutreachStatus.NOT_CONTACTED);
  }

  @Test
  void shouldKeepTheOutreach_whenAnEditLeavesItOut() {
    LibraryKolDO row = new LibraryKolDO();
    row.setOutreachStatus(LibraryOutreachStatus.COOPERATING);
    service.applyProfile(row, valid(), false);
    assertThat(row.getOutreachStatus()).isEqualTo(LibraryOutreachStatus.COOPERATING);
    var changed =
        command(1L, "https://www.tiktok.com/@x", "Amy BD", null, "REJECTED", "edited remark");
    service.applyProfile(row, changed, false);
    assertThat(row.getOutreachStatus()).isEqualTo(LibraryOutreachStatus.REJECTED);
    assertThat(row.getFollowUpRemark()).isEqualTo("edited remark");
  }

  @Test
  void shouldRejectAnUnknownOutreachOrCategory() {
    var badOutreach = command(1L, "https://www.tiktok.com/@x", "Amy BD", null, "NOPE", null);
    assertValidationFails(() -> service.applyProfile(new LibraryKolDO(), badOutreach, true));
    var badCategory =
        command(1L, "https://www.tiktok.com/@x", "Amy BD", List.of("NOT_A_CATEGORY"), null, null);
    assertValidationFails(() -> service.applyProfile(new LibraryKolDO(), badCategory, true));
  }

  @Test
  void shouldLimitTheFollowUpRemark_to1000Characters() {
    var ok = command(1L, "https://www.tiktok.com/@x", "Amy BD", null, null, "x".repeat(1000));
    service.applyProfile(new LibraryKolDO(), ok, true);
    var tooLong = command(1L, "https://www.tiktok.com/@x", "Amy BD", null, null, "x".repeat(1001));
    assertValidationFails(() -> service.applyProfile(new LibraryKolDO(), tooLong, true));
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldWriteTheFollowUpRemark_fromTheFollowUpDialog() {
    LibraryKolDO row = new LibraryKolDO();
    row.setId(7L);
    row.setCategories("[]");
    row.setTags("[]");
    when(kols.selectById(7L)).thenReturn(row);
    when(contents.selectList(any())).thenReturn(List.of());

    service.updateFollowUp(
        7L,
        new LibraryFollowUpCommand(
            "COOPERATING", LocalDate.of(2026, 11, 5), "SHIPPED", "SF1", null, " ship by friday "));

    ArgumentCaptor<Wrapper<LibraryKolDO>> update = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).update(isNull(), update.capture());
    var wrapper = (LambdaUpdateWrapper<LibraryKolDO>) update.getValue();
    assertThat(wrapper.getSqlSet()).contains("follow_up_remark");
    assertThat(wrapper.getParamNameValuePairs()).containsValue("ship by friday");
  }
}
