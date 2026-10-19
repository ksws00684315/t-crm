package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.api.KolLinkTargetChangedEvent;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolPlatformMapper;
import com.tcrm.admin.kol.mapper.KolPromotionRecordMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.KolPlatformDO;
import com.tcrm.admin.kol.model.KolPromotionRecordDO;
import com.tcrm.admin.kol.model.PromotionStatus;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

class KolPromotionServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolDO.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolPlatformDO.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolPromotionRecordDO.class);
  }

  private final KolMapper kols = mock(KolMapper.class);
  private final KolPlatformMapper platforms = mock(KolPlatformMapper.class);
  private final KolPromotionRecordMapper records = mock(KolPromotionRecordMapper.class);
  private final LinkTemplateService templates = mock(LinkTemplateService.class);
  private final BdUserApi bdUsers = mock(BdUserApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final KolPromotionService service =
      new KolPromotionService(
          kols,
          platforms,
          records,
          templates,
          bdUsers,
          current,
          TestTransactions.template(),
          audit,
          new AuditSanitizer(new ObjectMapper()),
          events);

  @BeforeEach
  void setUp() {
    when(current.user()).thenReturn(adminUser());
    when(platforms.selectList(any())).thenReturn(List.of());
    when(templates.usableTemplate(any()))
        .thenAnswer(
            inv -> {
              Long id = inv.getArgument(0);
              return new LinkTemplateDTO(
                  id, "code" + id, "name" + id, "https://x/{PRODUCT_ID}", "WEIDIAN", 0, 1);
            });
  }

  @Test
  void shouldAutoPickJumpTemplate_whenOnlyOnePlatformSelected() {
    KolDO kol = baseKol();
    KolDO saved = after(kol, "inv-new", 5L);
    when(kols.selectById(10L)).thenReturn(kol, saved);
    when(kols.update(isNull(), any())).thenReturn(1);

    service.updateConfig(
        10L,
        new KolPromotionService.PromotionConfigCommand(
            "0.1000", List.of(5L), "https://landing.example/", "inv-new", null, null, "note"));

    verify(events).publishEvent(new KolLinkTargetChangedEvent(10L));
  }

  @Test
  void shouldRejectJumpTemplate_whenNotAmongSelectedPlatforms() {
    when(kols.selectById(10L)).thenReturn(baseKol());
    assertThatThrownBy(
            () ->
                service.updateConfig(
                    10L,
                    new KolPromotionService.PromotionConfigCommand(
                        "0.1000",
                        List.of(5L, 6L),
                        "https://landing.example/",
                        "inv",
                        null,
                        9L,
                        null)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COMMON_VALIDATION_FAILED.message());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void shouldReject_whenMultiplePlatformsWithoutJumpTemplate() {
    when(kols.selectById(10L)).thenReturn(baseKol());
    assertThatThrownBy(
            () ->
                service.updateConfig(
                    10L,
                    new KolPromotionService.PromotionConfigCommand(
                        "0.1000",
                        List.of(5L, 6L),
                        "https://landing.example/",
                        "inv",
                        null,
                        null,
                        null)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COMMON_VALIDATION_FAILED.message());
  }

  @Test
  void shouldPublishLinkTargetEvent_whenInviteCodeChanges() {
    KolDO kol = baseKol();
    kol.setInviteCode("old");
    kol.setLinkTemplateId(5L);
    KolDO after = after(kol, "new", 5L);
    when(kols.selectById(10L)).thenReturn(kol, after);
    when(kols.update(isNull(), any())).thenReturn(1);
    when(platforms.selectList(any()))
        .thenReturn(List.of(platform(5L)))
        .thenReturn(List.of(platform(5L)));

    service.updateConfig(
        10L,
        new KolPromotionService.PromotionConfigCommand(
            "0.1000", List.of(5L), "https://landing.example/", "new", null, 5L, null));

    verify(events).publishEvent(new KolLinkTargetChangedEvent(10L));
  }

  @Test
  void shouldRejectReturnToPendingReview_whenAlreadyReviewed() {
    KolDO kol = baseKol();
    kol.setPromotionStatus(PromotionStatus.PENDING_PROMOTION.name());
    when(kols.selectById(10L)).thenReturn(kol);

    assertThatThrownBy(() -> service.updateStatus(10L, PromotionStatus.PENDING_REVIEW))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_REVIEW_ALREADY_DONE.message());
  }

  @Test
  void shouldAllowPendingReview_whenStillPendingReview() {
    KolDO kol = baseKol();
    kol.setPromotionStatus(PromotionStatus.PENDING_REVIEW.name());
    when(kols.selectById(10L)).thenReturn(kol);
    when(kols.update(isNull(), any())).thenReturn(1);

    service.updateStatus(10L, PromotionStatus.PENDING_REVIEW);

    verify(kols).update(isNull(), any());
  }

  private static KolPlatformDO platform(long templateId) {
    KolPlatformDO row = new KolPlatformDO();
    row.setId(templateId);
    row.setKolId(10L);
    row.setLinkTemplateId(templateId);
    return row;
  }

  private static KolDO baseKol() {
    KolDO kol = new KolDO();
    kol.setId(10L);
    kol.setBdUserId(1L);
    kol.setPromotionStatus(PromotionStatus.PENDING_PROMOTION.name());
    kol.setDefaultRate(new BigDecimal("0.1000"));
    kol.setLandingUrl("https://landing.example/");
    kol.setInviteCode("inv");
    return kol;
  }

  private static KolDO after(KolDO before, String invite, Long jump) {
    KolDO kol = baseKol();
    kol.setInviteCode(invite);
    kol.setLinkTemplateId(jump);
    kol.setDefaultRate(before.getDefaultRate());
    kol.setLandingUrl(before.getLandingUrl());
    return kol;
  }

  private static AccountDTO adminUser() {
    return new AccountDTO(
        1L,
        "admin",
        "hash",
        "Admin",
        null,
        Role.SUPER_ADMIN,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        1L,
        "Acme",
        true);
  }
}
