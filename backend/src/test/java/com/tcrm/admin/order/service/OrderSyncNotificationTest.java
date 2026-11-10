package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.order.api.OrderSyncFailedEvent;
import com.tcrm.admin.order.mapper.OrderSyncRetryMapper;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.model.OrderSyncFailureKind;
import com.tcrm.admin.order.model.OrderSyncRetryDO;
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

  @Test
  void shouldRecordRetryAndKeepTheShopHealthy_whenOneOrderFailsTheFirstTime() {
    Harness harness = harness();
    harness.state.setLastSuccessAt(EARLIER);
    stubOneFailingOrder(harness);
    when(harness.retries.selectList(any())).thenReturn(List.of());
    TenantScope.runCompany(7L, () -> assertThat(harness.sync.synchronize(3L)).isTrue());
    var inserted = ArgumentCaptor.forClass(OrderSyncRetryDO.class);
    verify(harness.retries).insert(inserted.capture());
    assertThat(inserted.getValue().getOrderId()).isEqualTo("100");
    assertThat(inserted.getValue().getShopId()).isEqualTo(3L);
    assertThat(inserted.getValue().getFailCount()).isEqualTo(1);
    verify(harness.failures)
        .record(
            3L,
            OrderSyncFailureKind.ORDER_SYNC,
            "Order synchronization failed for order 100: inspect API status logs",
            null);
    verify(harness.events, never()).publishEvent(any());
    assertThat(harness.state.getLastError()).isNull();
  }

  @Test
  void shouldNotifyOnce_whenAnOrderFailsForTheThirdTime() {
    for (int previous : new int[] {1, 2, 3}) {
      Harness harness = harness();
      harness.state.setLastSuccessAt(EARLIER);
      stubOneFailingOrder(harness);
      var retry = retry(previous);
      when(harness.retries.selectList(any())).thenReturn(List.of(retry));
      TenantScope.runCompany(7L, () -> assertThat(harness.sync.synchronize(3L)).isTrue());
      verify(harness.retries).updateById(retry);
      assertThat(retry.getFailCount()).isEqualTo(previous + 1);
      // the listed order and the pending retry are the same order: fetched once per run
      verify(harness.api, times(1)).orderDetail(anyLong(), any());
      if (previous + 1 == 3) {
        verify(harness.events).publishEvent(new OrderSyncFailedEvent(7L, 3L, "Shop A"));
        verify(harness.failures).record(any(), any(), any(), any());
      } else {
        verify(harness.events, never()).publishEvent(any());
        verify(harness.failures, never()).record(any(), any(), any(), any());
      }
    }
  }

  @Test
  void shouldDeleteTheRetry_whenAPendingOrderSucceeds() {
    Harness harness = harness();
    when(harness.api.orderList(anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.OrderListResponse(0, 0, List.of(), false, null, null));
    when(harness.api.refundList(anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.RefundListResponse(List.of(), 0, null));
    when(harness.api.orderDetail(anyLong(), any()))
        .thenReturn(
            OrderNormalizerTest.detail(
                "100", "pay", NOW, List.of(OrderNormalizerTest.line("90", null))));
    when(harness.retries.selectList(any())).thenReturn(List.of(retry(2)));
    TenantScope.runCompany(7L, () -> assertThat(harness.sync.synchronize(3L)).isTrue());
    verify(harness.retries).deleteResolved(55L);
  }

  @Test
  void shouldFailTheShop_whenTheOrderDetailErrorConcernsTheWholeShop() {
    Harness harness = harness();
    harness.state.setLastSuccessAt(EARLIER);
    stubOneFailingOrder(harness);
    org.mockito.Mockito.doThrow(
            new com.tcrm.admin.shared.BusinessException(
                com.tcrm.admin.shared.ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED))
        .when(harness.api)
        .orderDetail(anyLong(), any());
    when(harness.retries.selectList(any())).thenReturn(List.of());
    TenantScope.runCompany(7L, () -> assertThat(harness.sync.synchronize(3L)).isFalse());
    verify(harness.retries, never()).insert(any(OrderSyncRetryDO.class));
    assertThat(harness.state.getLastError())
        .isEqualTo("Order synchronization failed for order 100: WEIDIAN_SHOP_NOT_AUTHORIZED");
  }

  private static void stubOneFailingOrder(Harness harness) {
    when(harness.api.orderList(anyLong(), any()))
        .thenReturn(
            new WeidianBusinessApi.OrderListResponse(
                1,
                1,
                List.of(new WeidianBusinessApi.OrderListItem("100", null, null, null, NOW, null)),
                false,
                null,
                null));
    when(harness.api.refundList(anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.RefundListResponse(List.of(), 0, null));
    when(harness.api.orderDetail(anyLong(), any()))
        .thenThrow(new IllegalStateException("bad payload"));
  }

  private static OrderSyncRetryDO retry(int failCount) {
    var retry = new OrderSyncRetryDO();
    retry.setId(55L);
    retry.setShopId(3L);
    retry.setOrderId("100");
    retry.setFailCount(failCount);
    retry.setLastError("Order synchronization failed for order 100: inspect API status logs");
    return retry;
  }

  private static Harness harness() {
    var api = mock(WeidianBusinessApi.class);
    var states = mock(OrderSyncStateMapper.class);
    var shops = mock(ShopAccessApi.class);
    var events = mock(ApplicationEventPublisher.class);
    var failures = mock(OrderSyncFailureWriter.class);
    var retries = mock(OrderSyncRetryMapper.class);
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
            TestTransactions.template(),
            failures,
            retries);
    return new Harness(api, states, events, failures, retries, state, sync);
  }

  private record Harness(
      WeidianBusinessApi api,
      OrderSyncStateMapper states,
      ApplicationEventPublisher events,
      OrderSyncFailureWriter failures,
      OrderSyncRetryMapper retries,
      OrderSyncStateDO state,
      OrderSync sync) {}
}
