package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.order.api.OrderSyncFailedEvent;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TestTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class OrderSyncNotificationTest {
  private static final Instant NOW = Instant.parse("2026-10-17T01:00:00Z");
  private static final Instant EARLIER = Instant.parse("2026-10-16T01:00:00Z");

  @Test
  void shouldNotifyOnce_whenASuccessfulShopStartsFailing() {
    Harness harness = harness();
    harness.state.setLastSuccessAt(EARLIER);
    when(harness.api.orderList(anyLong(), any())).thenThrow(new IllegalStateException("down"));
    TenantScope.runCompany(
        7L,
        () -> {
          assertThat(harness.sync.synchronize(3L)).isFalse();
          assertThat(harness.sync.synchronize(3L)).isFalse();
        });
    var published = ArgumentCaptor.forClass(Object.class);
    verify(harness.events).publishEvent(published.capture());
    assertThat(published.getValue()).isEqualTo(new OrderSyncFailedEvent(7L, 3L, "Shop A"));
  }

  @Test
  void shouldNotifyAgain_whenTheShopRecoversAndThenFails() {
    Harness harness = harness();
    harness.state.setLastSuccessAt(EARLIER);
    harness.state.setLastError("Order synchronization failed: inspect API status logs");
    when(harness.api.orderList(anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.OrderListResponse(0, 0, List.of(), false, null, null));
    when(harness.api.refundList(anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.RefundListResponse(List.of(), 0, null));
    TenantScope.runCompany(7L, () -> assertThat(harness.sync.synchronize(3L)).isTrue());
    verify(harness.events, never()).publishEvent(any());

    when(harness.api.orderList(anyLong(), any())).thenThrow(new IllegalStateException("down"));
    TenantScope.runCompany(7L, () -> assertThat(harness.sync.synchronize(3L)).isFalse());
    verify(harness.events).publishEvent(new OrderSyncFailedEvent(7L, 3L, "Shop A"));
  }

  @Test
  void shouldNotNotify_whenTheShopHasNeverSucceeded() {
    Harness harness = harness();
    when(harness.api.orderList(anyLong(), any())).thenThrow(new IllegalStateException("down"));
    TenantScope.runCompany(7L, () -> assertThat(harness.sync.synchronize(3L)).isFalse());
    verify(harness.events, never()).publishEvent(any());
    verify(harness.states).updateById(harness.state);
  }

  @Test
  void shouldSaveTheErrorWithoutNotifying_whenTheCompanyIsUnknown() {
    Harness harness = harness();
    harness.state.setLastSuccessAt(EARLIER);
    when(harness.api.orderList(anyLong(), any())).thenThrow(new IllegalStateException("down"));
    TenantScope.runSystem(
        "scheduled order sync", () -> assertThat(harness.sync.synchronize(3L)).isFalse());
    verify(harness.states).updateById(harness.state);
    verify(harness.events, never()).publishEvent(any());
    assertThat(harness.state.getLastError()).contains("inspect API status logs");
  }

  private static Harness harness() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var shops = mock(ShopAccessApi.class);
    var events = mock(ApplicationEventPublisher.class);
    var state = new OrderSyncStateDO();
    state.setShopId(3L);
    when(states.selectOne(any())).thenReturn(state);
    when(shops.findShop(3L)).thenReturn(new ShopAccessDTO(3L, "Shop A", "wd-3", 1, null));
    var sync =
        new OrderSync(
            api,
            states,
            mock(OrderWriter.class),
            new OrderNormalizer("test"),
            Clock.fixed(NOW, ZoneOffset.UTC),
            shops,
            events,
            TestTransactions.template());
    return new Harness(api, states, events, state, sync);
  }

  private record Harness(
      WeidianBusinessApi api,
      OrderSyncStateMapper states,
      ApplicationEventPublisher events,
      OrderSyncStateDO state,
      OrderSync sync) {}
}
