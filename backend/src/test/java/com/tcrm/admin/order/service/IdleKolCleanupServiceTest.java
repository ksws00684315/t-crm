package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolCleanupApi;
import com.tcrm.admin.kol.api.KolCleanupApi.CleanupKolSnapshot;
import com.tcrm.admin.kol.api.PromotingKolDTO;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.product.api.KolCleanupItemApi;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdleKolCleanupServiceTest {
  private final KolCleanupApi cleanup = mock(KolCleanupApi.class);
  private final KolItemApi items = mock(KolItemApi.class);
  private final KolCleanupItemApi cleanupItems = mock(KolCleanupItemApi.class);
  private final OrderMapper orders = mock(OrderMapper.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final AuditSanitizer sanitizer = mock(AuditSanitizer.class);
  private final IdleKolCleanupService service =
      new IdleKolCleanupService(
          cleanup,
          items,
          cleanupItems,
          orders,
          current,
          audit,
          sanitizer,
          TestTransactions.template());

  @Test
  void shouldApplyEachKolThresholdAndIgnoreDisabledKols() {
    Instant now = Instant.parse("2026-10-18T00:00:00Z");
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    1L, "KOL-AAA001", "A", 5L, 90, now.minus(100, ChronoUnit.DAYS), now),
                new PromotingKolDTO(
                    2L, "KOL-BBB002", "B", 5L, 7, now.minus(8, ChronoUnit.DAYS), now),
                new PromotingKolDTO(
                    3L, "KOL-CCC003", "C", 5L, null, now.minus(200, ChronoUnit.DAYS), now)));
    when(orders.lastAttributedPayTimes(List.of(1L, 2L, 3L))).thenReturn(List.of());

    var idle = service.findIdle(now);

    assertThat(idle).extracting(IdleKolCleanupService.IdleKol::id).containsExactly(1L, 2L);
  }

  @Test
  void shouldReportRemainingDaysUsingIdleBaselineAndRespectOwnScope() {
    Instant now = Instant.now();
    when(current.user()).thenReturn(user(Role.STAFF, DataScope.OWN, 5L));
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    1L, "KOL-AAA001", "A", 5L, 10, now.minus(3, ChronoUnit.DAYS), now),
                new PromotingKolDTO(
                    2L, "KOL-BBB002", "B", 99L, 10, now.minus(3, ChronoUnit.DAYS), now)));
    when(orders.lastAttributedPayTimes(List.of(1L))).thenReturn(List.of());

    assertThat(service.remainingDays(List.of(1L, 2L))).containsOnlyKeys(1L).containsEntry(1L, 7);
  }

  @Test
  void shouldUseLastOrderOrFallbackAndClampOverdueCountdown() {
    Instant now = Instant.now();
    when(current.user()).thenReturn(user(Role.SUPER_ADMIN, DataScope.ALL, 5L));
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    1L, "KOL-AAA001", "A", 5L, 10, now.minus(20, ChronoUnit.DAYS), now),
                new PromotingKolDTO(
                    2L, "KOL-BBB002", "B", 5L, 10, null, now.minus(20, ChronoUnit.DAYS)),
                new PromotingKolDTO(3L, "KOL-CCC003", "C", 5L, null, null, now),
                new PromotingKolDTO(4L, "KOL-DDD004", "D", 5L, 10, null, null)));
    OrderMapper.KolLastPayRow pay = new OrderMapper.KolLastPayRow();
    pay.setKolId(1L);
    pay.setPayTime(now.minus(2, ChronoUnit.DAYS));
    when(orders.lastAttributedPayTimes(List.of(1L, 2L, 3L, 4L))).thenReturn(List.of(pay));

    assertThat(service.remainingDays(List.of(1L, 2L, 3L, 4L, 99L)))
        .containsOnlyKeys(1L, 2L)
        .containsEntry(1L, 8)
        .containsEntry(2L, 0);
  }

  @Test
  void shouldRejectUnboundedCountdownRequests() {
    assertThat(service.remainingDays(null)).isEmpty();
    assertThat(service.remainingDays(List.of())).isEmpty();
    assertThat(service.remainingDays(java.util.Collections.nCopies(101, 1L))).isEmpty();
  }

  @Test
  void shouldCleanOnlyWhenStillEnabledAndStillIdleAtLockTime() {
    Instant now = Instant.now();
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    9L, "KOL-DDD009", "D", 5L, 90, now.minus(100, ChronoUnit.DAYS), now)));
    when(orders.lastAttributedPayTimes(List.of(9L))).thenReturn(List.of());
    when(cleanup.lockPromotingKol(9L))
        .thenReturn(Optional.of(new CleanupKolSnapshot(9L, "KOL-DDD009", "D", 5L, 90)));
    when(cleanup.softDeleteLockedKol(9L)).thenReturn(true);
    when(sanitizer.sanitize(any())).thenReturn("{}");

    assertThat(service.executeForCurrentTenant()).isEqualTo(1);
    verify(cleanup).softDeleteLockedKol(9L);
    verify(items).closeCurrentMappingsForKol(eq(9L), any(), isNull());
    verify(cleanupItems).enqueueDeletesForKol(9L);
  }

  @Test
  void shouldSkipCleanupWhenThresholdWasDisabledAfterScan() {
    Instant now = Instant.now();
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    9L, "KOL-DDD009", "D", 5L, 90, now.minus(100, ChronoUnit.DAYS), now)));
    when(orders.lastAttributedPayTimes(List.of(9L))).thenReturn(List.of());
    when(cleanup.lockPromotingKol(9L))
        .thenReturn(Optional.of(new CleanupKolSnapshot(9L, "KOL-DDD009", "D", 5L, null)));

    assertThat(service.executeForCurrentTenant()).isZero();
    verify(cleanup, never()).softDeleteLockedKol(9L);
    verify(items, never()).closeCurrentMappingsForKol(anyLong(), any(), any());
  }

  @Test
  void shouldManuallyCleanupOwnedKol() {
    when(current.user()).thenReturn(user(Role.STAFF, DataScope.OWN, 5L));
    when(cleanup.lockKol(9L))
        .thenReturn(Optional.of(new CleanupKolSnapshot(9L, "KOL-DDD009", "D", 5L, null)));
    when(cleanup.softDeleteLockedKol(9L)).thenReturn(true);
    when(sanitizer.sanitize(any())).thenReturn("{}");

    service.cleanupManual(9L, " idle account ");

    verify(cleanupItems)
        .createPendingItems(
            eq(9L), eq("KOL-DDD009"), eq(5L), eq("MANUAL"), eq("idle account"), any(), eq(5L));
    verify(items).closeCurrentMappingsForKol(eq(9L), any(), eq(5L));
    verify(cleanupItems).enqueueDeletesForKol(9L);
  }

  @Test
  void shouldRejectManualCleanupOfKolOwnedBySomeoneElse() {
    when(current.user()).thenReturn(user(Role.STAFF, DataScope.OWN, 5L));
    when(cleanup.lockKol(9L))
        .thenReturn(Optional.of(new CleanupKolSnapshot(9L, "KOL-DDD009", "D", 99L, null)));

    assertThatThrownBy(() -> service.cleanupManual(9L, "reason"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  private static AccountDTO user(Role role, DataScope scope, long id) {
    return new AccountDTO(
        id, "user" + id, null, "User", null, role, scope, 1, null, null, 0, 1L, "Test", true);
  }
}
