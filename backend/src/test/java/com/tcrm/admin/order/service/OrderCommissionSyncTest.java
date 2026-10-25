package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.order.api.OrderItemsCommissionEvent;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.shared.TestTransactions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** Unit tests of the order-side commission facts event (design §4.8.1). */
class OrderCommissionSyncTest {
  private static final Instant PAY_TIME = Instant.parse("2026-10-10T08:00:00Z");
  private static final Instant SETTLED = Instant.parse("2026-10-20T08:00:00Z");

  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final RefundMapper refunds = mock(RefundMapper.class);
  private final OrderCommissionSync sync = new OrderCommissionSync(events, refunds);

  @Test
  @SuppressWarnings("unchecked")
  void shouldPublishUnverifiedFactsWithoutBasisAndMarkAfterSales() {
    when(refunds.selectList(any(LambdaQueryWrapper.class)))
        .thenReturn(List.of(refund(9001L, "PROCESSING")));
    OrderDO order = order(null);
    OrderItemDO item = item(9001L, 101L, 1, "12.34");
    TestTransactions.template().executeWithoutResult(status -> sync.sync(order, List.of(item)));
    ArgumentCaptor<OrderItemsCommissionEvent> captor =
        ArgumentCaptor.forClass(OrderItemsCommissionEvent.class);
    verify(events).publishEvent(captor.capture());
    OrderItemsCommissionEvent event = captor.getValue();
    assertThat(event.payTime()).isEqualTo(PAY_TIME);
    OrderItemsCommissionEvent.ItemFacts facts = event.items().getFirst();
    // Effective amount pending M0 (Q-1017-1/2): basis stays null so the line stays unverified.
    assertThat(facts.basisCny()).isNull();
    assertThat(facts.successfulRefundCny()).isEqualByComparingTo("12.34");
    assertThat(facts.kolId()).isEqualTo(101L);
    assertThat(facts.attributionType()).isEqualTo(1);
    assertThat(facts.afterSales()).isTrue();
    assertThat(facts.settlementState()).isEqualTo("UNKNOWN");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldPassSettledTimeAsConfirmedState() {
    when(refunds.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    TestTransactions.template()
        .executeWithoutResult(
            status -> sync.sync(order(SETTLED), List.of(item(9001L, 101L, 1, null))));
    ArgumentCaptor<OrderItemsCommissionEvent> captor =
        ArgumentCaptor.forClass(OrderItemsCommissionEvent.class);
    verify(events).publishEvent(captor.capture());
    OrderItemsCommissionEvent.ItemFacts facts = captor.getValue().items().getFirst();
    assertThat(facts.settlementState()).isEqualTo("CONFIRMED");
    assertThat(facts.settlementTime()).isEqualTo(SETTLED);
    assertThat(facts.afterSales()).isFalse();
  }

  @Test
  void shouldSkipEmptyPayloads() {
    TestTransactions.template()
        .executeWithoutResult(
            status -> {
              sync.sync(null, List.of(item(9001L, 101L, 1, null)));
              sync.sync(order(null), List.of());
            });
    verifyNoInteractions(events);
  }

  private static OrderDO order(Instant settledTime) {
    OrderDO order = new OrderDO();
    order.setId(5001L);
    order.setShopId(30L);
    order.setPayTime(PAY_TIME);
    order.setSettledTime(settledTime);
    return order;
  }

  private static OrderItemDO item(long id, Long kolId, int type, String refund) {
    OrderItemDO item = new OrderItemDO();
    item.setId(id);
    item.setSubOrderId("SUB-" + id);
    item.setKolId(kolId);
    item.setAttributionType(type);
    item.setProductId(51L);
    item.setRefundAmount(refund == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(refund));
    return item;
  }

  private static RefundDO refund(long itemId, String status) {
    RefundDO refund = new RefundDO();
    refund.setOrderItemId(itemId);
    refund.setRefundStatus(status);
    return refund;
  }
}
