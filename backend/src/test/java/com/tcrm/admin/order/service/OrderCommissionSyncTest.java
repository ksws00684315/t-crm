package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.order.api.OrderItemsCommissionEvent;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.OrderSettlementFlowMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderSettlementFlowDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.shared.TestTransactions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** Unit tests of the order-side commission facts event (design §4.8.1, task 76 wiring). */
class OrderCommissionSyncTest {
  private static final Instant PAY_TIME = Instant.parse("2026-10-10T08:00:00Z");
  private static final Instant SETTLED = Instant.parse("2026-10-20T08:00:00Z");
  private static final String REMOTE_ORDER_ID = "WD-76-1";

  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final RefundMapper refunds = mock(RefundMapper.class);
  private final OrderSettlementFlowMapper flows = mock(OrderSettlementFlowMapper.class);
  private final OrderMapper orders = mock(OrderMapper.class);
  private final OrderCommissionSync sync = new OrderCommissionSync(events, refunds, flows, orders);

  @BeforeAll
  static void metadata() {
    for (var type :
        List.of(OrderDO.class, OrderItemDO.class, RefundDO.class, OrderSettlementFlowDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldAccrueFromPayAmountWithoutFlowsAndMarkAfterSales() {
    when(refunds.selectList(any(LambdaQueryWrapper.class)))
        .thenReturn(List.of(refund(9001L, "PROCESSING")));
    when(flows.listByRemoteOrderId(anyLong(), anyString())).thenReturn(List.of());
    OrderDO order = order(null);
    OrderItemDO item = item(9001L, 101L, 1, "12.34", "60.00");
    TestTransactions.template().executeWithoutResult(status -> sync.sync(order, List.of(item)));
    ArgumentCaptor<OrderItemsCommissionEvent> captor =
        ArgumentCaptor.forClass(OrderItemsCommissionEvent.class);
    verify(events).publishEvent(captor.capture());
    OrderItemsCommissionEvent event = captor.getValue();
    assertThat(event.payTime()).isEqualTo(PAY_TIME);
    OrderItemsCommissionEvent.ItemFacts facts = event.items().getFirst();
    // Paid, no matched settlement flow yet: the basis is the pay amount, unconfirmed (task 81).
    assertThat(facts.basisCny()).isEqualByComparingTo("60.00");
    assertThat(facts.basisCheckReason()).isEqualTo("NO_SETTLEMENT");
    assertThat(facts.basisIncludesRefund()).isFalse();
    assertThat(facts.successfulRefundCny()).isEqualByComparingTo("12.34");
    assertThat(facts.kolId()).isEqualTo(101L);
    assertThat(facts.attributionType()).isEqualTo(1);
    assertThat(facts.afterSales()).isTrue();
    assertThat(facts.settlementState()).isEqualTo("UNSETTLED");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldPassSettledTimeAsConfirmedState() {
    when(refunds.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(flows.listByRemoteOrderId(anyLong(), anyString())).thenReturn(List.of());
    TestTransactions.template()
        .executeWithoutResult(
            status -> sync.sync(order(SETTLED), List.of(item(9001L, 101L, 1, null, null))));
    ArgumentCaptor<OrderItemsCommissionEvent> captor =
        ArgumentCaptor.forClass(OrderItemsCommissionEvent.class);
    verify(events).publishEvent(captor.capture());
    OrderItemsCommissionEvent.ItemFacts facts = captor.getValue().items().getFirst();
    assertThat(facts.settlementState()).isEqualTo("CONFIRMED");
    assertThat(facts.settlementTime()).isEqualTo(SETTLED);
    assertThat(facts.afterSales()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldPublishGoodsIncomeBasisWithSettlementTimeIgnoringFees() {
    // Hand-computed (amounts in fen, the default unit constant): goods income 600 -> 6.00 CNY
    // matches pay 6.00; fee -4 (-0.04 CNY) never enters the basis (task 81). Lines pay 4.00 +
    // 2.00: 6.00 x 4/6 = 4.00; 6.00 x 2/6 = 2.00; tail 0.00.
    when(refunds.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(flows.listByRemoteOrderId(anyLong(), anyString()))
        .thenReturn(
            List.of(
                flow("收入", "货款收入", "600", SETTLED, SettlementBasisCalculator.QUERY_INCOME_DETAIL),
                flow("支出", "交易手续费", "-4", SETTLED, SettlementBasisCalculator.QUERY_INCOME_DETAIL)));
    OrderDO order = order(null);
    order.setPayAmount(new BigDecimal("6.00"));
    TestTransactions.template()
        .executeWithoutResult(
            status ->
                sync.sync(
                    order,
                    List.of(
                        item(9001L, 101L, 1, null, "4.00"), item(9002L, 101L, 1, null, "2.00"))));
    ArgumentCaptor<OrderItemsCommissionEvent> captor =
        ArgumentCaptor.forClass(OrderItemsCommissionEvent.class);
    verify(events).publishEvent(captor.capture());
    List<OrderItemsCommissionEvent.ItemFacts> facts = captor.getValue().items();
    assertThat(facts.get(0).basisCny()).isEqualByComparingTo("4.00");
    assertThat(facts.get(1).basisCny()).isEqualByComparingTo("2.00");
    assertThat(facts.get(0).basisIncludesRefund()).isFalse();
    assertThat(facts.get(0).settlementState()).isEqualTo("CONFIRMED");
    assertThat(facts.get(0).settlementTime()).isEqualTo(SETTLED);
    assertThat(facts.get(0).basisCheckReason()).isNull();
    // The goods-income settlement time lands on crm_order.settled_time via a targeted update.
    verify(orders).update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldNotRewriteOrderColumnsWhenOutcomeUnchanged() {
    when(refunds.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(flows.listByRemoteOrderId(anyLong(), anyString())).thenReturn(List.of());
    OrderDO order = order(null);
    order.setCommissionBasisReason("NO_SETTLEMENT");
    TestTransactions.template()
        .executeWithoutResult(
            status -> sync.sync(order, List.of(item(9001L, 101L, 1, null, "60.00"))));
    verify(orders, never()).update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class));
  }

  @Test
  void shouldSkipEmptyPayloads() {
    TestTransactions.template()
        .executeWithoutResult(
            status -> {
              sync.sync(null, List.of(item(9001L, 101L, 1, null, null)));
              sync.sync(order(null), List.of());
            });
    verifyNoInteractions(events);
  }

  private static OrderDO order(Instant settledTime) {
    OrderDO order = new OrderDO();
    order.setId(5001L);
    order.setShopId(30L);
    order.setWeidianOrderId(REMOTE_ORDER_ID);
    order.setPayTime(PAY_TIME);
    order.setPayAmount(new BigDecimal("60.00"));
    order.setSettledTime(settledTime);
    return order;
  }

  private static OrderItemDO item(long id, Long kolId, int type, String refund, String pay) {
    OrderItemDO item = new OrderItemDO();
    item.setId(id);
    item.setSubOrderId("SUB-" + id);
    item.setKolId(kolId);
    item.setAttributionType(type);
    item.setProductId(51L);
    item.setRefundAmount(refund == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(refund));
    item.setPayAmount(pay == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(pay));
    return item;
  }

  private static OrderSettlementFlowDO flow(
      String tradeType, String subType, String amount, Instant tradeTime, String queryType) {
    OrderSettlementFlowDO flow = new OrderSettlementFlowDO();
    flow.setShopId(30L);
    flow.setWeidianOrderId(REMOTE_ORDER_ID);
    flow.setTradeType(tradeType);
    flow.setTradeSubType(subType);
    flow.setAmountRaw(new BigDecimal(amount));
    flow.setTradeTime(tradeTime);
    flow.setQueryType(queryType);
    return flow;
  }

  private static RefundDO refund(long itemId, String status) {
    RefundDO refund = new RefundDO();
    refund.setOrderItemId(itemId);
    refund.setRefundStatus(status);
    return refund;
  }
}
