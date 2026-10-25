package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.order.api.OrderItemsCommissionEvent;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.shared.TransactionalEvents;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes commission source facts of an order's item lines (task 62, design §4.8.1). The {@code
 * commission} module registers snapshots from the event before the order transaction commits. The
 * effective amount (semantic {@code WEIDIAN_COMMISSIONABLE_CNY}) is not mapped from Weidian yet (M0
 * / Q-1017-1/2 pending), so lines register unverified and accrue nothing.
 */
@Component
public class OrderCommissionSync {
  private final ApplicationEventPublisher events;
  private final RefundMapper refunds;

  public OrderCommissionSync(ApplicationEventPublisher events, RefundMapper refunds) {
    this.events = events;
    this.refunds = refunds;
  }

  /** Publishes one fact row per item line; runs inside the caller's transaction. */
  public void sync(OrderDO order, List<OrderItemDO> items) {
    if (order == null || items == null || items.isEmpty()) {
      return;
    }
    Set<Long> afterSalesItems = afterSalesItems(order.getId());
    List<OrderItemsCommissionEvent.ItemFacts> facts =
        items.stream().map(item -> toFacts(order, item, afterSalesItems)).toList();
    TransactionalEvents.publish(
        events,
        new OrderItemsCommissionEvent(order.getId(), order.getShopId(), order.getPayTime(), facts));
  }

  private OrderItemsCommissionEvent.ItemFacts toFacts(
      OrderDO order, OrderItemDO item, Set<Long> afterSalesItems) {
    String settlementState = order.getSettledTime() == null ? "UNKNOWN" : "CONFIRMED";
    return new OrderItemsCommissionEvent.ItemFacts(
        item.getId(),
        item.getSubOrderId(),
        item.getKolId(),
        item.getAttributionType() == null ? 0 : item.getAttributionType(),
        item.getProductId(),
        /* basisCny */ null,
        /* basisIncludesRefund */ null,
        item.getRefundAmount(),
        settlementState,
        order.getSettledTime(),
        /* completedTime */ null,
        afterSalesItems.contains(item.getId()));
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
