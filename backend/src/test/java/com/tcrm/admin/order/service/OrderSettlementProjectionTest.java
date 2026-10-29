package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.OrderSettlementFlowMapper;
import com.tcrm.admin.order.mapper.OrderSettlementQueryMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderSettlementFlowDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests of the commission-facing order projections (task 62, 13.05). */
class OrderSettlementProjectionTest {
  @BeforeAll
  static void metadata() {
    for (var type : List.of(OrderDO.class, OrderItemDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  private final OrderMapper orders = mock(OrderMapper.class);
  private final OrderItemMapper items = mock(OrderItemMapper.class);
  private final OrderSettlementQueryMapper settlementQueries =
      mock(OrderSettlementQueryMapper.class);
  private final OrderSettlementFlowMapper settlementFlows = mock(OrderSettlementFlowMapper.class);
  private final OrderSettlementProjection projection =
      new OrderSettlementProjection(orders, items, settlementQueries, settlementFlows);

  @BeforeEach
  void prepare() {
    when(settlementQueries.countOrderIds(any(), any(), any())).thenReturn(0L);
  }

  @Test
  void shouldReturnEarly_whenCountIsZero() {
    var result = projection.pageOrderIds("WD-1", 30L, null, 1, 20);
    assertThat(result.total()).isZero();
    assertThat(result.items()).isEmpty();
  }

  @Test
  void shouldPageByIdsWithOwnScopePassedThrough() {
    when(settlementQueries.countOrderIds("WD-1", 30L, List.of(77L))).thenReturn(2L);
    when(settlementQueries.pageOrderIds("WD-1", 30L, List.of(77L), 20, 0L))
        .thenReturn(List.of(501L, 500L));

    var result = projection.pageOrderIds("WD-1", 30L, List.of(77L), 1, 20);

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.items()).containsExactly(501L, 500L);
  }

  @Test
  void shouldMapOrderAndItemProjections() {
    OrderDO order = new OrderDO();
    order.setId(501L);
    order.setWeidianOrderId("WD-501");
    order.setShopId(30L);
    order.setPayTime(Instant.parse("2026-10-10T08:00:00Z"));
    order.setOrderStatus("PAID");
    when(orders.selectList(any())).thenReturn(List.of(order));
    OrderItemDO item = new OrderItemDO();
    item.setId(9001L);
    item.setOrderId(501L);
    item.setItemName("Widget");
    item.setSkuName("Blue");
    item.setQuantity(2);
    item.setPayAmount(new BigDecimal("25.00"));
    item.setRefundAmount(new BigDecimal("5.00"));
    item.setKolId(77L);
    item.setProductId(51L);
    when(items.selectList(any())).thenReturn(List.of(item));

    var orderRows = projection.listOrdersByIds(List.of(501L));
    assertThat(orderRows).hasSize(1);
    assertThat(orderRows.getFirst().orderNo()).isEqualTo("WD-501");
    assertThat(orderRows.getFirst().payTime()).isEqualTo(Instant.parse("2026-10-10T08:00:00Z"));

    var itemRows = projection.listItemsByOrderIds(List.of(501L));
    assertThat(itemRows).hasSize(1);
    assertThat(itemRows.getFirst().id()).isEqualTo(9001L);
    assertThat(itemRows.getFirst().quantity()).isEqualTo(2);
    assertThat(itemRows.getFirst().payAmount()).isEqualByComparingTo("25.00");
    assertThat(itemRows.getFirst().kolId()).isEqualTo(77L);

    assertThat(projection.listOrdersByIds(List.of())).isEmpty();
    assertThat(projection.listItemsByOrderIds(List.of())).isEmpty();
  }

  @Test
  void shouldAggregateKolOrdersByAttributedOrderAndOwnLines() {
    OrderDO first = new OrderDO();
    first.setId(501L);
    first.setKolId(77L);
    first.setPayTime(Instant.parse("2026-10-10T08:00:00Z"));
    OrderDO second = new OrderDO();
    second.setId(502L);
    second.setKolId(77L);
    second.setPayTime(Instant.parse("2026-10-11T08:00:00Z"));
    when(orders.selectList(any())).thenReturn(List.of(first, second));
    OrderItemDO lineOne = new OrderItemDO();
    lineOne.setOrderId(501L);
    lineOne.setKolId(77L);
    lineOne.setQuantity(2);
    OrderItemDO lineTwo = new OrderItemDO();
    lineTwo.setOrderId(502L);
    lineTwo.setKolId(77L);
    lineTwo.setQuantity(3);
    when(items.selectList(any())).thenReturn(List.of(lineOne, lineTwo));

    var rows = projection.aggregateKolOrders(List.of(77L), null, null);

    assertThat(rows).hasSize(1);
    // Hand calc: two paid orders of KOL 77, lines 2 + 3 = 5 pieces.
    assertThat(rows.getFirst().kolId()).isEqualTo(77L);
    assertThat(rows.getFirst().orderCount()).isEqualTo(2);
    assertThat(rows.getFirst().quantity()).isEqualTo(5);
  }

  @Test
  void shouldSkipOrdersWithoutQuantityLines() {
    OrderDO single = new OrderDO();
    single.setId(501L);
    single.setKolId(77L);
    single.setPayTime(Instant.parse("2026-10-10T08:00:00Z"));
    when(orders.selectList(any())).thenReturn(List.of(single));
    when(items.selectList(any())).thenReturn(List.of());

    var rows = projection.aggregateKolOrders(List.of(77L), null, null);

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().quantity()).isZero();
    assertThat(projection.aggregateKolOrders(List.of(), null, null)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldSumIncomeDetailFlowsAsWithdrawableForOrdersWithSettlementFlows() {
    OrderDO order = new OrderDO();
    order.setId(5001L);
    order.setShopId(30L);
    order.setWeidianOrderId("WD-1");
    OrderDO unsettled = new OrderDO();
    unsettled.setId(5002L);
    unsettled.setShopId(30L);
    unsettled.setWeidianOrderId("WD-2");
    when(orders.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order, unsettled));
    when(settlementFlows.listByRemoteOrderId(30L, "WD-1"))
        .thenReturn(List.of(flow("收入", "货款收入", "6500"), flow("支出", "交易手续费", "-20")));
    when(settlementFlows.listByRemoteOrderId(30L, "WD-2")).thenReturn(List.of());

    Map<Long, BigDecimal> withdrawable =
        projection.weidianWithdrawableByOrderIds(List.of(5001L, 5002L));

    // Display total 65.00 - 0.20 = 64.80; an order without settlement flows is absent (null).
    assertThat(withdrawable).containsOnlyKeys(5001L);
    assertThat(withdrawable.get(5001L)).isEqualByComparingTo("64.80");
  }

  private static OrderSettlementFlowDO flow(String tradeType, String subType, String amount) {
    OrderSettlementFlowDO flow = new OrderSettlementFlowDO();
    flow.setTradeType(tradeType);
    flow.setTradeSubType(subType);
    flow.setAmountRaw(new BigDecimal(amount));
    flow.setQueryType(SettlementBasisCalculator.QUERY_INCOME_DETAIL);
    return flow;
  }
}
