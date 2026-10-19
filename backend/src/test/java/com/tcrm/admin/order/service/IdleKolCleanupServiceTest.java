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
import com.tcrm.admin.kol.api.KolCleanupConfigDTO;
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
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
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
  private final BdUserApi bdUsers = mock(BdUserApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final AuditSanitizer sanitizer = mock(AuditSanitizer.class);
  private final IdleKolCleanupService service =
      new IdleKolCleanupService(
          cleanup,
          items,
          cleanupItems,
          orders,
          bdUsers,
          current,
          audit,
          sanitizer,
          TestTransactions.template());

  @Test
  void shouldSkipExecution_whenCleanupDisabled() {
    when(cleanup.getCleanupConfig()).thenReturn(new KolCleanupConfigDTO(false, 90));
    assertThat(service.executeForCurrentTenant()).isZero();
    verify(cleanup, never()).listPromotingWithLastPromotingAt();
  }

  @Test
  void shouldUsePromotingTime_whenNoAttributedOrder() {
    Instant now = Instant.parse("2026-10-18T00:00:00Z");
    Instant promotingAt = now.minus(100, ChronoUnit.DAYS);
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    1L, "KOL-AAA001", "A", 5L, promotingAt, now.minus(1, ChronoUnit.DAYS))));
    when(orders.lastAttributedPayTimes(List.of(1L))).thenReturn(List.of());
    var idle = service.findIdle(90, now);
    assertThat(idle).hasSize(1);
    assertThat(idle.get(0).idleSince()).isEqualTo(promotingAt);
    assertThat(idle.get(0).lastOrderAt()).isNull();
  }

  @Test
  void shouldPreferLastOrderPayTime_whenAttributedOrderExists() {
    Instant now = Instant.parse("2026-10-18T00:00:00Z");
    Instant pay = now.minus(120, ChronoUnit.DAYS);
    Instant promotingAt = now.minus(200, ChronoUnit.DAYS);
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(new PromotingKolDTO(2L, "KOL-BBB002", "B", 5L, promotingAt, promotingAt)));
    OrderMapper.KolLastPayRow row = new OrderMapper.KolLastPayRow();
    row.setKolId(2L);
    row.setPayTime(pay);
    when(orders.lastAttributedPayTimes(List.of(2L))).thenReturn(List.of(row));
    var idle = service.findIdle(90, now);
    assertThat(idle).hasSize(1);
    assertThat(idle.get(0).idleSince()).isEqualTo(pay);
    assertThat(idle.get(0).lastOrderAt()).isEqualTo(pay);
  }

  @Test
  void shouldIgnoreNonIdlePromotingKols() {
    Instant now = Instant.parse("2026-10-18T00:00:00Z");
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    3L, "KOL-CCC003", "C", 5L, now.minus(10, ChronoUnit.DAYS), now)));
    when(orders.lastAttributedPayTimes(List.of(3L))).thenReturn(List.of());
    assertThat(service.findIdle(90, now)).isEmpty();
  }

  @Test
  void shouldCloseMappingsAndSoftDelete_whenExecuting() {
    Instant now = Instant.now();
    when(cleanup.getCleanupConfig()).thenReturn(new KolCleanupConfigDTO(true, 90));
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    9L, "KOL-DDD009", "D", 5L, now.minus(100, ChronoUnit.DAYS), now)));
    when(orders.lastAttributedPayTimes(List.of(9L))).thenReturn(List.of());
    when(cleanup.lockPromotingKol(9L))
        .thenReturn(Optional.of(new CleanupKolSnapshot(9L, "KOL-DDD009", "D", 5L)));
    when(cleanup.softDeleteLockedKol(9L)).thenReturn(true);
    when(sanitizer.sanitize(any())).thenReturn("{}");
    assertThat(service.executeForCurrentTenant()).isEqualTo(1);
    verify(cleanup).lockPromotingKol(9L);
    verify(cleanup).softDeleteLockedKol(9L);
    verify(items).closeCurrentMappingsForKol(eq(9L), any(), isNull());
    verify(cleanupItems).enqueueDeletesForKol(9L);
  }

  @Test
  void shouldNotCloseMappings_whenSoftDeleteRejects() {
    Instant now = Instant.now();
    when(cleanup.getCleanupConfig()).thenReturn(new KolCleanupConfigDTO(true, 90));
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    9L, "KOL-DDD009", "D", 5L, now.minus(100, ChronoUnit.DAYS), now)));
    when(orders.lastAttributedPayTimes(List.of(9L))).thenReturn(List.of());
    when(cleanup.lockPromotingKol(9L)).thenReturn(Optional.empty());
    assertThat(service.executeForCurrentTenant()).isZero();
    verify(items, never()).closeCurrentMappingsForKol(anyLong(), any(), any());
  }

  @Test
  void shouldRejectPreview_whenOwnScope() {
    when(current.user())
        .thenReturn(
            new AccountDTO(
                5L,
                "bd",
                null,
                "BD",
                null,
                Role.STAFF,
                DataScope.OWN,
                1,
                null,
                null,
                0,
                1L,
                "Test",
                true));
    assertThatThrownBy(() -> service.preview(1, 20))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
  }

  private AccountDTO allScopeUser() {
    return new AccountDTO(
        1L,
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
        "Test",
        true);
  }

  private AccountDTO ownScopeUser(long id) {
    return new AccountDTO(
        id, "bd", null, "BD", null, Role.STAFF, DataScope.OWN, 1, null, null, 0, 1L, "Test", true);
  }

  @Test
  void shouldCleanupManual_whenAllScope() {
    when(current.user()).thenReturn(allScopeUser());
    when(cleanup.lockKol(9L))
        .thenReturn(Optional.of(new CleanupKolSnapshot(9L, "KOL-DDD009", "D", 5L)));
    when(cleanup.softDeleteLockedKol(9L)).thenReturn(true);
    when(sanitizer.sanitize(any())).thenReturn("{}");
    service.cleanupManual(9L, " idle account ");
    verify(cleanupItems)
        .createPendingItems(
            eq(9L), eq("KOL-DDD009"), eq(5L), eq("MANUAL"), eq("idle account"), any(), eq(1L));
    verify(items).closeCurrentMappingsForKol(eq(9L), any(), eq(1L));
    verify(cleanupItems).enqueueDeletesForKol(9L);
  }

  @Test
  void shouldRejectManualCleanup_whenOwnScopeAndNotOwner() {
    when(current.user()).thenReturn(ownScopeUser(5L));
    when(cleanup.lockKol(9L))
        .thenReturn(Optional.of(new CleanupKolSnapshot(9L, "KOL-DDD009", "D", 99L)));
    assertThatThrownBy(() -> service.cleanupManual(9L, "reason"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  void shouldRejectManualCleanup_whenKolMissing() {
    when(current.user()).thenReturn(allScopeUser());
    when(cleanup.lockKol(9L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.cleanupManual(9L, "reason"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldRejectBlankOrTooLongReason_whenManualCleanup() {
    when(current.user()).thenReturn(allScopeUser());
    assertThatThrownBy(() -> service.cleanupManual(9L, "  "))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
    assertThatThrownBy(() -> service.cleanupManual(9L, "x".repeat(201)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
    assertThatThrownBy(() -> service.cleanupManual(9L, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @Test
  void shouldPageCleanupItems_withOwnScopeFilter() {
    when(current.user()).thenReturn(ownScopeUser(5L));
    when(cleanupItems.page(eq("K"), eq("FAILED"), eq(5L), eq(1), eq(20)))
        .thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    assertThat(service.pageCleanupItems("K", "FAILED", 1, 20).total()).isZero();
    verify(cleanupItems).page("K", "FAILED", 5L, 1, 20);
  }

  @Test
  void shouldPageCleanupItems_withoutScope_whenAll() {
    when(current.user()).thenReturn(allScopeUser());
    when(cleanupItems.page(isNull(), isNull(), isNull(), eq(1), eq(20)))
        .thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    service.pageCleanupItems(null, null, 1, 20);
    verify(cleanupItems).page(null, null, null, 1, 20);
  }

  @Test
  void shouldRetryFailedCleanupItem() {
    when(current.user()).thenReturn(allScopeUser());
    when(cleanupItems.getById(3L))
        .thenReturn(
            Optional.of(
                new KolCleanupItemApi.KolCleanupItemDTO(
                    3L,
                    9L,
                    "KOL-1",
                    5L,
                    1L,
                    2L,
                    null,
                    "w1",
                    "FAILED",
                    "AUTO",
                    null,
                    "err",
                    Instant.now(),
                    null)));
    service.retryCleanupItem(3L);
    verify(cleanupItems).retryFailed(3L);
  }

  @Test
  void shouldRejectRetry_whenNotFailedOrForbidden() {
    when(current.user()).thenReturn(ownScopeUser(5L));
    when(cleanupItems.getById(3L))
        .thenReturn(
            Optional.of(
                new KolCleanupItemApi.KolCleanupItemDTO(
                    3L,
                    9L,
                    "KOL-1",
                    99L,
                    1L,
                    2L,
                    null,
                    "w1",
                    "FAILED",
                    "AUTO",
                    null,
                    "err",
                    Instant.now(),
                    null)));
    assertThatThrownBy(() -> service.retryCleanupItem(3L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.AUTH_FORBIDDEN);

    when(cleanupItems.getById(4L))
        .thenReturn(
            Optional.of(
                new KolCleanupItemApi.KolCleanupItemDTO(
                    4L,
                    9L,
                    "KOL-1",
                    5L,
                    1L,
                    2L,
                    null,
                    "w1",
                    "SUCCESS",
                    "AUTO",
                    null,
                    null,
                    Instant.now(),
                    Instant.now())));
    assertThatThrownBy(() -> service.retryCleanupItem(4L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);

    when(cleanupItems.getById(5L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.retryCleanupItem(5L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldPreviewIdleList_withBdNickname() {
    when(current.user()).thenReturn(allScopeUser());
    Instant now = Instant.now();
    when(cleanup.getCleanupConfig()).thenReturn(new KolCleanupConfigDTO(true, 90));
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    9L, "KOL-DDD009", "D", 5L, now.minus(100, ChronoUnit.DAYS), now)));
    when(orders.lastAttributedPayTimes(List.of(9L))).thenReturn(List.of());
    when(bdUsers.listByIds(List.of(5L)))
        .thenReturn(List.of(new BdUserDTO(5L, "bd5", "Nice BD", true)));
    var page = service.preview(1, 20);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).bdUserName()).isEqualTo("Nice BD");
  }

  @Test
  void shouldUseUsername_whenBdNicknameBlank() {
    when(current.user()).thenReturn(allScopeUser());
    Instant now = Instant.now();
    when(cleanup.getCleanupConfig()).thenReturn(new KolCleanupConfigDTO(true, 90));
    when(cleanup.listPromotingWithLastPromotingAt())
        .thenReturn(
            List.of(
                new PromotingKolDTO(
                    9L, "KOL-DDD009", "D", 5L, now.minus(100, ChronoUnit.DAYS), now)));
    when(orders.lastAttributedPayTimes(List.of(9L))).thenReturn(List.of());
    when(bdUsers.listByIds(List.of(5L))).thenReturn(List.of(new BdUserDTO(5L, "bd5", "  ", true)));
    assertThat(service.preview(1, 20).items().get(0).bdUserName()).isEqualTo("bd5");
  }
}
