package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.integration.api.WeidianAuthorizationApi;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.api.SystemConfigApi;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests of the per-shop settlement pull scheduler (task 76 §3.4): due decisions send the right
 * window to the pull service, only authorized enabled shops are considered, disabled companies are
 * skipped, and the accepted action persists its checkpoints.
 */
class OrderSettlementSyncCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-10-28T19:30:00Z");

  private final OrderSettlementFlowService flows = mock(OrderSettlementFlowService.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final EnabledTenantApi tenants = mock(EnabledTenantApi.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final WeidianAuthorizationApi authorization = mock(WeidianAuthorizationApi.class);
  private final OrderSyncStateMapper states = mock(OrderSyncStateMapper.class);
  private final SystemConfigApi config = mock(SystemConfigApi.class);

  private OrderSettlementSyncCoordinator coordinator() {
    return new OrderSettlementSyncCoordinator(
        flows,
        locks,
        tenants,
        shops,
        authorization,
        states,
        config,
        Clock.fixed(NOW, java.time.ZoneOffset.UTC));
  }

  @Test
  void shouldRunLockedPullForEveryAuthorizedEnabledShopAcrossCompanies() throws Exception {
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L, 2L));
    when(shops.listShops()).thenReturn(List.of(enabled(10L), enabled(20L), disabled(30L)));
    when(authorization.authorizedShopIds()).thenReturn(List.of(10L, 20L, 30L));
    when(states.selectOne(any())).thenReturn(null);
    when(config.getInt("weidian.settlement.sync-interval-minutes")).thenReturn(60);
    when(config.getInt("weidian.settlement.backfill-hour")).thenReturn(3);
    when(flows.pull(anyLong(), any(), any(), any())).thenReturn(true);
    doAnswer(
            invocation -> {
              invocation.getArgument(3, Runnable.class).run();
              return true;
            })
        .when(locks)
        .run(any(String.class), anyLong(), anyLong(), any(Runnable.class));

    // First round: no checkpoints yet -> each shop is due for a backfill (hour 3 passed).
    coordinator().scheduled();

    // Both companies see the same configured shop list; the disabled shop is skipped everywhere.
    ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
    verify(locks, org.mockito.Mockito.times(4))
        .run(names.capture(), anyLong(), anyLong(), any(Runnable.class));
    assertThat(names.getAllValues())
        .contains("weidian:settlement:10", "weidian:settlement:20")
        .doesNotContain("weidian:settlement:30");
    verify(flows, org.mockito.Mockito.times(2))
        .pull(
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq(NOW.minus(SettlementPullPlanner.BACKFILL_LOOKBACK)),
            org.mockito.ArgumentMatchers.eq(NOW),
            org.mockito.ArgumentMatchers.eq(null));
    verify(flows, org.mockito.Mockito.times(2))
        .pull(
            org.mockito.ArgumentMatchers.eq(20L),
            org.mockito.ArgumentMatchers.eq(NOW.minus(SettlementPullPlanner.BACKFILL_LOOKBACK)),
            org.mockito.ArgumentMatchers.eq(NOW),
            org.mockito.ArgumentMatchers.eq(null));
    verify(flows, never()).pull(org.mockito.ArgumentMatchers.eq(30L), any(), any(), any());
    // Checkpoints persisted for the two pulled shops (twice, once per company scope).
    ArgumentCaptor<OrderSyncStateDO> inserted = ArgumentCaptor.forClass(OrderSyncStateDO.class);
    verify(states, org.mockito.Mockito.times(4)).insert(inserted.capture());
    assertThat(inserted.getAllValues())
        .allSatisfy(
            row -> {
              assertThat(row.getSettlementPullAt()).isEqualTo(NOW);
              assertThat(row.getSettlementBackfillAt()).isEqualTo(NOW);
            });
  }

  @Test
  void shouldNotPullDisabledCompanyOrUnAuthorizedShop() throws Exception {
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L));
    when(shops.listShops()).thenReturn(List.of(enabled(10L), enabled(40L)));
    // Shop 40 exists but its tenant is disabled (not in enabledTenantIds).
    when(authorization.authorizedShopIds()).thenReturn(List.of(10L));
    when(states.selectOne(any())).thenReturn(null);
    when(flows.pull(anyLong(), any(), any(), any())).thenReturn(true);
    doAnswer(
            invocation -> {
              invocation.getArgument(3, Runnable.class).run();
              return true;
            })
        .when(locks)
        .run(any(String.class), anyLong(), anyLong(), any(Runnable.class));

    coordinator().scheduled();

    verify(locks, org.mockito.Mockito.atLeastOnce())
        .run(
            org.mockito.ArgumentMatchers.eq("weidian:settlement:10"),
            anyLong(),
            anyLong(),
            any(Runnable.class));
    verify(locks, never())
        .run(
            org.mockito.ArgumentMatchers.eq("weidian:settlement:40"),
            anyLong(),
            anyLong(),
            any(Runnable.class));
    verify(flows, never()).pull(org.mockito.ArgumentMatchers.eq(40L), any(), any(), any());
  }

  @Test
  void shouldRunIncrementalAndStampOnlyPullWhenNotBackfillDue() throws Exception {
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L));
    when(shops.listShops()).thenReturn(List.of(enabled(10L)));
    when(authorization.authorizedShopIds()).thenReturn(List.of(10L));
    when(config.getInt("weidian.settlement.sync-interval-minutes")).thenReturn(15);
    when(config.getInt("weidian.settlement.backfill-hour")).thenReturn(3);
    when(flows.pull(anyLong(), any(), any(), any())).thenReturn(true);
    // Already backfilled today at 03:10; the incremental interval (15 min) elapsed again.
    OrderSyncStateDO state = new OrderSyncStateDO();
    state.setShopId(10L);
    state.setSettlementBackfillAt(Instant.parse("2026-10-28T19:10:00Z"));
    state.setSettlementPullAt(Instant.parse("2026-10-28T19:10:00Z"));
    when(states.selectOne(any())).thenReturn(state);
    doAnswer(
            invocation -> {
              invocation.getArgument(3, Runnable.class).run();
              return true;
            })
        .when(locks)
        .run(any(String.class), anyLong(), anyLong(), any(Runnable.class));

    coordinator().scheduled();

    verify(flows)
        .pull(
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq(NOW.minus(SettlementPullPlanner.INCREMENTAL_LOOKBACK)),
            org.mockito.ArgumentMatchers.eq(NOW),
            org.mockito.ArgumentMatchers.eq(Instant.parse("2026-10-28T19:10:00Z")));
    verify(states).updateById(state);
    assertThat(state.getSettlementPullAt()).isEqualTo(NOW);
    assertThat(state.getSettlementBackfillAt()).isEqualTo(Instant.parse("2026-10-28T19:10:00Z"));
  }

  @Test
  void shouldSkipPullWhenNothingDue() throws Exception {
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L));
    when(shops.listShops()).thenReturn(List.of(enabled(10L)));
    when(authorization.authorizedShopIds()).thenReturn(List.of(10L));
    when(config.getInt("weidian.settlement.sync-interval-minutes")).thenReturn(60);
    when(config.getInt("weidian.settlement.backfill-hour")).thenReturn(3);
    OrderSyncStateDO state = new OrderSyncStateDO();
    state.setShopId(10L);
    state.setSettlementBackfillAt(Instant.parse("2026-10-28T19:10:00Z"));
    state.setSettlementPullAt(NOW.minus(java.time.Duration.ofMinutes(20)));
    when(states.selectOne(any())).thenReturn(state);

    coordinator().scheduled();

    verify(flows, never()).pull(anyLong(), any(), any(), any());
    verify(states, never()).insert(any(OrderSyncStateDO.class));
    verify(states, never()).updateById(any(OrderSyncStateDO.class));
  }

  @Test
  void shouldLeaveCheckpointsUntouchedWhenPullFails() throws Exception {
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L));
    when(shops.listShops()).thenReturn(List.of(enabled(10L)));
    when(authorization.authorizedShopIds()).thenReturn(List.of(10L));
    when(config.getInt("weidian.settlement.sync-interval-minutes")).thenReturn(60);
    when(config.getInt("weidian.settlement.backfill-hour")).thenReturn(3);
    when(states.selectOne(any())).thenReturn(null);
    doAnswer(
            invocation -> {
              invocation.getArgument(3, Runnable.class).run();
              return true;
            })
        .when(locks)
        .run(any(String.class), anyLong(), anyLong(), any(Runnable.class));
    // A thrown pull and an unsuccessful pull both leave the checkpoints untouched.
    org.mockito.Mockito.doThrow(new RuntimeException("boom"))
        .when(flows)
        .pull(anyLong(), any(), any(), any());

    coordinator().scheduled();

    verify(states, never()).insert(any(OrderSyncStateDO.class));
    verify(states, never()).updateById(any(OrderSyncStateDO.class));

    org.mockito.Mockito.clearInvocations(flows, states);
    // doReturn, not when(): re-stubbing a throwing mock with when() would invoke the old stub.
    org.mockito.Mockito.doReturn(false).when(flows).pull(anyLong(), any(), any(), any());

    coordinator().scheduled();

    verify(states, never()).insert(any(OrderSyncStateDO.class));
    verify(states, never()).updateById(any(OrderSyncStateDO.class));
  }

  private static ShopAccessDTO enabled(long id) {
    return new ShopAccessDTO(id, "shop-" + id, String.valueOf(id), 1, "SHOP-" + id);
  }

  private static ShopAccessDTO disabled(long id) {
    return new ShopAccessDTO(id, "shop-" + id, String.valueOf(id), 0, "SHOP-" + id);
  }
}
