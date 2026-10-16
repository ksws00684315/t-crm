package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.integration.api.WeidianAuthorizationApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OrderSyncCoordinatorTest {
  private final OrderSync sync = mock(OrderSync.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final EnabledTenantApi tenants = mock(EnabledTenantApi.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final WeidianAuthorizationApi auth = mock(WeidianAuthorizationApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final OrderSyncCoordinator service =
      new OrderSyncCoordinator(sync, locks, tenants, shops, auth, current);

  @AfterEach
  void close() {
    service.shutdown();
    Thread.interrupted();
  }

  private void ready() throws Exception {
    when(shops.findShop(10L)).thenReturn(new ShopAccessDTO(10L, "Test", "10", 1));
    when(auth.authorizedShopIds()).thenReturn(List.of(10L));
    when(current.user())
        .thenReturn(
            new AccountDTO(
                1L,
                "test",
                null,
                "Test",
                null,
                Role.SUPER_ADMIN,
                DataScope.ALL,
                1,
                null,
                null,
                0,
                1L,
                "Test company",
                true));
    when(locks.run(anyString(), anyLong(), anyLong(), any()))
        .thenAnswer(
            call -> {
              call.getArgument(3, Runnable.class).run();
              return true;
            });
  }

  @Test
  void shouldAcquireLockBeforeAccepting_whenTriggeredManually() throws Exception {
    ready();
    service.trigger(10L);
    verify(sync, timeout(2000)).synchronize(10);
    verify(locks)
        .run(
            org.mockito.ArgumentMatchers.eq("order:sync:10"),
            org.mockito.ArgumentMatchers.eq(0L),
            org.mockito.ArgumentMatchers.eq(-1L),
            any());
  }

  @Test
  void shouldRejectConcurrentWork_whenAnotherInstanceHoldsLock() throws Exception {
    ready();
    org.mockito.Mockito.doReturn(false).when(locks).run(anyString(), anyLong(), anyLong(), any());
    assertThatThrownBy(() -> service.trigger(10L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("already running");
    verify(sync, never()).synchronize(anyLong());
  }

  @Test
  void shouldRejectDisabledMissingOrUnauthorizedShop_whenTriggered() {
    assertThatThrownBy(() -> service.trigger(10L)).hasMessageContaining("not found");
    when(shops.findShop(10L)).thenReturn(new ShopAccessDTO(10L, "Test", "10", 0));
    assertThatThrownBy(() -> service.trigger(10L)).hasMessageContaining("disabled");
    when(shops.findShop(10L)).thenReturn(new ShopAccessDTO(10L, "Test", "10", 1));
    assertThatThrownBy(() -> service.trigger(10L)).hasMessageContaining("not connected");
  }

  @Test
  void shouldSkipUnauthorizedAndDisabledShops_whenScheduled() throws Exception {
    ready();
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L));
    when(shops.listShops())
        .thenReturn(
            List.of(
                new ShopAccessDTO(10L, "Test", "10", 1),
                new ShopAccessDTO(20L, "Disabled", "20", 0),
                new ShopAccessDTO(30L, "Unauthorized", "30", 1)));
    service.scheduled();
    verify(sync).synchronize(10);
    verify(sync, never()).synchronize(20);
    verify(sync, never()).synchronize(30);
    assertThat(TenantScope.companyId()).isNull();
  }

  @Test
  void shouldContinueOtherCompanies_whenOneCompanyFails() throws Exception {
    ready();
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L, 2L));
    when(auth.authorizedShopIds())
        .thenThrow(new IllegalStateException("test"))
        .thenReturn(List.of(10L));
    when(shops.listShops()).thenReturn(List.of(new ShopAccessDTO(10L, "Test", "10", 1)));
    service.scheduled();
    verify(sync).synchronize(10);
  }

  @Test
  void shouldNotRun_whenDisabledByTestLifecycle() {
    service.setScheduled(false);
    service.scheduled();
    verify(tenants, never()).enabledTenantIds();
  }

  @Test
  void shouldFailSafely_whenWorkerLockAcquisitionIsInterrupted() throws Exception {
    ready();
    org.mockito.Mockito.doThrow(new InterruptedException("test"))
        .when(locks)
        .run(anyString(), anyLong(), anyLong(), any());
    assertThatThrownBy(() -> service.trigger(10L)).isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldContinueShops_whenOneWorkerFails() throws Exception {
    ready();
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L));
    when(auth.authorizedShopIds()).thenReturn(List.of(10L, 20L));
    when(shops.listShops())
        .thenReturn(
            List.of(
                new ShopAccessDTO(10L, "Test", "10", 1), new ShopAccessDTO(20L, "Test", "20", 1)));
    org.mockito.Mockito.doThrow(new IllegalStateException("test"))
        .doAnswer(
            call -> {
              call.getArgument(3, Runnable.class).run();
              return true;
            })
        .when(locks)
        .run(anyString(), anyLong(), anyLong(), any());
    service.scheduled();
    verify(sync).synchronize(20);
  }
}
