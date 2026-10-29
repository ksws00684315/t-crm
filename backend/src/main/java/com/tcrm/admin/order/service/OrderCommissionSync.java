package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.order.api.OrderItemsCommissionEvent;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.OrderSettlementFlowMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderSettlementFlowDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.order.service.SettlementBasisCalculator.BasisOutcome;
import com.tcrm.admin.shared.TransactionalEvents;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes commission source facts of an order's item lines (task 62, design §4.8.1; task 76
 * wiring). The {@code commission} module registers snapshots from the event before the order
 * transaction commits. Since task 76 the basis comes from the persisted Weidian settlement flows:
 * {@link SettlementBasisCalculator} checks them against the confirmed rules and either yields a
 * per-line basis (with the settlement time of the goods-income flow) or leaves the order unverified
 * with a queryable reason, persisted on {@code crm_order}.
 */
@Component
public class OrderCommissionSync {
  private static final Logger LOG = LoggerFactory.getLogger(OrderCommissionSync.class);

  private final ApplicationEventPublisher events;
  private final RefundMapper refunds;
  private final OrderSettlementFlowMapper settlementFlows;
  private final OrderMapper orders;

  public OrderCommissionSync(
      ApplicationEventPublisher events,
      RefundMapper refunds,
      OrderSettlementFlowMapper settlementFlows,
      OrderMapper orders) {
    this.events = events;
    this.refunds = refunds;
    this.settlementFlows = settlementFlows;
    this.orders = orders;
  }

  /** Publishes one fact row per item line; runs inside the caller's transaction. */
  public void sync(OrderDO order, List<OrderItemDO> items) {
    if (order == null || items == null || items.isEmpty()) {
      return;
    }
    Set<Long> afterSalesItems = afterSalesItems(order.getId());
    List<OrderSettlementFlowDO> flows =
        settlementFlows.listByRemoteOrderId(order.getShopId(), order.getWeidianOrderId());
    BasisOutcome basis = SettlementBasisCalculator.compute(order, items, flows);
    if (SettlementBasisCalculator.REASON_MULTIPLE_KOLS.equals(basis.reason())) {
      // Owner-confirmed impossible case; defensive only, never logs personal data.
      LOG.warn("settlement basis blocked for order {}: MULTIPLE_KOLS", order.getId());
    }
    persistOutcome(order, basis);
    List<OrderItemsCommissionEvent.ItemFacts> facts =
        items.stream().map(item -> toFacts(order, item, basis, afterSalesItems)).toList();
    TransactionalEvents.publish(
        events,
        new OrderItemsCommissionEvent(order.getId(), order.getShopId(), order.getPayTime(), facts));
  }

  private OrderItemsCommissionEvent.ItemFacts toFacts(
      OrderDO order, OrderItemDO item, BasisOutcome basis, Set<Long> afterSalesItems) {
    BigDecimal lineBasis = basis.lineBasis().get(item.getId());
    String settlementState;
    if (lineBasis != null) {
      settlementState = basis.settlementTime() == null ? "UNSETTLED" : "CONFIRMED";
    } else {
      settlementState = order.getSettledTime() == null ? "UNKNOWN" : "CONFIRMED";
    }
    return new OrderItemsCommissionEvent.ItemFacts(
        item.getId(),
        item.getSubOrderId(),
        item.getKolId(),
        item.getAttributionType() == null ? 0 : item.getAttributionType(),
        item.getProductId(),
        lineBasis,
        lineBasis == null ? null : Boolean.TRUE,
        item.getRefundAmount(),
        settlementState,
        lineBasis == null ? order.getSettledTime() : basis.settlementTime(),
        /* completedTime */ null,
        afterSalesItems.contains(item.getId()),
        basis.reason());
  }

  /**
   * Persists the settlement time and the basis check reason on the order with a targeted update, so
   * the column can also be cleared back to null once a basis verifies. Only changed values are
   * written.
   */
  private void persistOutcome(OrderDO order, BasisOutcome basis) {
    boolean settledChange =
        basis.settlementTime() != null && !basis.settlementTime().equals(order.getSettledTime());
    boolean reasonChange = !Objects.equals(basis.reason(), order.getCommissionBasisReason());
    if (!settledChange && !reasonChange) {
      return;
    }
    LambdaUpdateWrapper<OrderDO> update =
        new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, order.getId());
    if (settledChange) {
      update.set(OrderDO::getSettledTime, basis.settlementTime());
      order.setSettledTime(basis.settlementTime());
    }
    if (reasonChange) {
      update.set(OrderDO::getCommissionBasisReason, basis.reason());
      order.setCommissionBasisReason(basis.reason());
    }
    orders.update(null, update);
  }

  /** Items with an unconcluded refund (PROCESSING / SUCCESS / UNKNOWN block release, §8.4). */
  private Set<Long> afterSalesItems(long orderId) {
    List<RefundDO> rows =
        refunds.selectList(
            new LambdaQueryWrapper<RefundDO>()
                .select(RefundDO::getOrderItemId)
                .eq(RefundDO::getOrderId, orderId)
                .ne(RefundDO::getRefundStatus, "CLOSED")
                .isNotNull(RefundDO::getOrderItemId));
    Set<Long> ids = new HashSet<>();
    for (RefundDO row : rows) {
      ids.add(row.getOrderItemId());
    }
    return ids;
  }
}
