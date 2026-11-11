package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.PromoSheetConfirmResult;
import com.tcrm.admin.product.api.PromoSheetItemApi;
import com.tcrm.admin.product.api.PromoSheetRowDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * Confirmation rules of the promo-sheet upload that {@code PromoSheetIT} does not reach: an expired
 * preview, a preview bound to another uploader, company or KOL, and a missing KOL (task 145, audit
 * C-03).
 */
class PromoSheetServiceTest {
  private static final String KEY = "kol:promo-sheet:upload-1";

  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final KolApi kols = mock(KolApi.class);
  private final PromoSheetItemApi mappings = mock(PromoSheetItemApi.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final PromoSheetService service =
      new PromoSheetService(
          current,
          kols,
          mock(PromoSheetReader.class),
          mappings,
          redis,
          audit,
          new AuditSanitizer(JsonMapper.builder().build()));

  @Test
  void shouldRejectConfirmation_whenThePreviewExpired() {
    ready(user(5L, 1L));
    when(values.get(KEY)).thenReturn(null);
    assertThatThrownBy(() -> service.confirm(9L, "upload-1"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.KOL_PROMO_UPLOAD_EXPIRED);
    verifyNoInteractions(mappings);
  }

  @Test
  void shouldRejectConfirmation_whenThePreviewBelongsToAnotherUploaderCompanyOrKol() {
    for (var payload : List.of(payload(1L, 9L, 6L), payload(2L, 9L, 5L), payload(1L, 8L, 5L))) {
      ready(user(5L, 1L));
      when(values.get(KEY)).thenReturn(payload.serialize());
      assertThatThrownBy(() -> service.confirm(9L, "upload-1"))
          .isInstanceOf(BusinessException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
    }
    verifyNoInteractions(mappings);
    verify(redis, never()).delete(anyString());
  }

  @Test
  void shouldWriteDeleteThePreviewAndAudit_whenTheUploaderConfirms() {
    ready(user(5L, 1L));
    when(values.get(KEY)).thenReturn(payload(1L, 9L, 5L).serialize());
    var result = new PromoSheetConfirmResult(2, 0, 1, 0, 0, Map.of(), List.of());
    when(mappings.confirm(eq(9L), any())).thenReturn(result);
    var outcome = service.confirm(9L, "upload-1");
    assertThat(outcome.result()).isEqualTo(result);
    assertThat(outcome.fileName()).isEqualTo("sheet.xlsx");
    assertThat(outcome.conflictOwnerVisible()).isFalse();
    verify(redis).delete(KEY);
    verify(audit)
        .recordTenantWriteOperation(
            eq(1L),
            eq(5L),
            eq("KOL"),
            eq("Confirm promo sheet"),
            eq("POST"),
            eq("/admin-api/kol/kols/9/promo-sheet-uploads"),
            anyString());
  }

  @Test
  void shouldReportNotFound_whenTheKolIsNotInTheCompany() {
    when(current.user()).thenReturn(user(5L, 1L));
    when(kols.listByIds(List.of(9L))).thenReturn(List.of());
    assertThatThrownBy(() -> service.confirm(9L, "upload-1"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
    verifyNoInteractions(redis);
  }

  private void ready(AccountDTO user) {
    when(current.user()).thenReturn(user);
    when(kols.listByIds(List.of(9L)))
        .thenReturn(List.of(new KolRefDTO(9L, "K009", "Kol", true, 5L)));
    when(redis.opsForValue()).thenReturn(values);
    when(mappings.confirm(anyLong(), any()))
        .thenReturn(new PromoSheetConfirmResult(0, 0, 0, 0, 0, Map.of(), List.of()));
  }

  private static PromoSheetUploadPayload payload(long tenantId, long kolId, long uploaderId) {
    return new PromoSheetUploadPayload(
        tenantId,
        kolId,
        uploaderId,
        "sheet.xlsx",
        List.of(
            new PromoSheetRowDTO(
                "Sheet1", 2, "https://weidian.com/item.html?itemID=1", false, "A")));
  }

  private static AccountDTO user(long id, long tenantId) {
    return new AccountDTO(
        id,
        "staff",
        null,
        "Staff",
        null,
        Role.STAFF,
        DataScope.OWN,
        1,
        null,
        null,
        0,
        tenantId,
        "Company",
        true);
  }
}
