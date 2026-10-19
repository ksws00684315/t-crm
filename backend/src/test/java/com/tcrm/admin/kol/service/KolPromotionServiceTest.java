package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolPlatformMapper;
import com.tcrm.admin.kol.mapper.KolPromotionRecordMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.RelationStatus;
import com.tcrm.admin.kol.model.ReviewStatus;
import com.tcrm.admin.kol.service.KolPromotionService.PlatformInput;
import com.tcrm.admin.kol.service.KolPromotionService.PromotionConfigCommand;
import com.tcrm.admin.kol.service.KolPromotionService.ReviewCommand;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class KolPromotionServiceTest {
  private final KolMapper kols = mock(KolMapper.class);
  private final KolPlatformMapper platforms = mock(KolPlatformMapper.class);
  private final KolPromotionRecordMapper records = mock(KolPromotionRecordMapper.class);
  private final LinkTemplateService templates = mock(LinkTemplateService.class);
  private final BdUserApi bdUsers = mock(BdUserApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final AuditSanitizer sanitizer = mock(AuditSanitizer.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private KolPromotionService service;

  @BeforeEach
  void setUp() {
    service =
        new KolPromotionService(
            kols,
            platforms,
            records,
            templates,
            bdUsers,
            current,
            TestTransactions.template(),
            audit,
            sanitizer,
            events);
    when(current.user()).thenReturn(admin());
    when(sanitizer.sanitize(any())).thenReturn("{}");
  }

  @Test
  void updateConfig_requiresReviewApproved() {
    KolDO kol = baseKol();
    kol.setReviewStatus(ReviewStatus.PENDING.name());
    when(kols.selectById(1L)).thenReturn(kol);
    assertThatThrownBy(
            () ->
                service.updateConfig(
                    1L,
                    new PromotionConfigCommand(
                        "0.1",
                        List.of(new PlatformInput(7L, "https://example.com/a")),
                        "INV",
                        "https://docs.google.com/s",
                        7L,
                        null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.KOL_REVIEW_REQUIRED);
  }

  @Test
  void review_rejectsWhenAlreadyApproved() {
    KolDO kol = baseKol();
    kol.setReviewStatus(ReviewStatus.APPROVED.name());
    when(kols.selectById(1L)).thenReturn(kol);
    assertThatThrownBy(
            () ->
                service.review(1L, new ReviewCommand("Alice", null, null, null, null, null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.KOL_REVIEW_ALREADY_DONE);
  }

  private static KolDO baseKol() {
    KolDO kol = new KolDO();
    kol.setId(1L);
    kol.setBdUserId(9L);
    kol.setName("K");
    kol.setEmail("k@example.com");
    kol.setRelationStatus(RelationStatus.PENDING_PROMOTION.name());
    return kol;
  }

  private static AccountDTO admin() {
    return new AccountDTO(
        9L,
        "admin",
        null,
        "Admin",
        null,
        Role.SUPER_ADMIN,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        1L,
        "T",
        true);
  }
}
