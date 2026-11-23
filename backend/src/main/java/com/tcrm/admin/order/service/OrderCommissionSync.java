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
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes commission source facts of an order's item lines (task 62, design §4.8.1; task 81
 * basis). The {@code commission} module registers snapshots from the event before the order
 * transaction commits. Since task 81 the basis is the pay amount once the order is paid, and the
 * persisted Weidian settlement flows only confirm it: {@link SettlementBasisCalculator} yields a
 * per-line basis (with the settlement time of the goods-income flow once confirmed) or leaves the
 * order unverified with a queryable reason, persisted on {@code crm_order}. Refunds are netted by
 * the commission engine, so the event marks the basis as not refund-inclusive.
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
    boolean refundBlocked = hasBlockingRefund(order.getId());
    List<OrderSettlementFlowDO> flows =
        settlementFlows.listByRemoteOrderId(order.getShopId(), order.getWeidianOrderId());
    BasisOutcome basis = SettlementBasisCalculator.compute(order, items, flows);
    if (SettlementBasisCalculator.REASON_MULTIPLE_KOLS.equals(basis.reason())) {
      // Owner-confirmed impossible case; defensive only, never logs personal data.
      LOG.warn("settlement basis blocked for order {}: MULTIPLE_KOLS", order.getId());
    }
    boolean afterSales = refundBlocked || basis.refundGap();
    if (basis.refundGap()) {
      LOG.warn("settlement refund needs matched item evidence for order {}", order.getId());
    }
    persistOutcome(order, basis);
    List<OrderItemsCommissionEvent.ItemFacts> facts =
        items.stream().map(item -> toFacts(order, item, basis, afterSales)).toList();
    TransactionalEvents.publish(
        events,
        new OrderItemsCommissionEvent(order.getId(), order.getShopId(), order.getPayTime(), facts));
  }

  private OrderItemsCommissionEvent.ItemFacts toFacts(
      OrderDO order, OrderItemDO item, BasisOutcome basis, boolean afterSales) {
    BigDecimal lineBasis = basis.lineBasis().get(item.getId());
    String settlementState;
    if (order.getPayTime() == null) {
      // An unpaid order is not a verification issue (owner decision 2026-10-30).
      settlementState = "UNPAID";
    } else if (lineBasis != null) {
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
        lineBasis == null ? null : Boolean.FALSE,
        item.getRefundAmount(),
        settlementState,
        lineBasis == null ? order.getSettledTime() : basis.settlementTime(),
        // L-052 (owner 2026-11-03): completedTime is the Weidian trade-completion time. Neither
        // vdian.order.get 2.0 nor vdian.order.list.get 1.6 returns such a field (task 75 live
        // probe 2026-10-28), so it stays null instead of inferring from receive_time; the
        // FALLBACK branch stays unused until a real source exists (design §5: the time is never
        // inferred when the API does not provide it).
        /* completedTime */ null,
        afterSales,
        basis.reason());
  }

  /**
   * Persists the settlement time and the basis check reason on the order with a targeted update, so
   * the columns can also be cleared back to null. Only changed values are written.
   *
   * <p>The settlement time is cleared only when the stored confirmation no longer holds (task 91,
   * L-038): the order has income flows but they do not confirm the basis ({@code INCOME_MISMATCH}).
   * A round without income flows ({@code NO_SETTLEMENT}), an uncomputable basis (multiple KOLs,
   * allocation failure) do not invalidate a previously stored settlement fact.
   */
  private void persistOutcome(OrderDO order, BasisOutcome basis) {
    boolean confirmationLost =
        SettlementBasisCalculator.REASON_INCOME_MISMATCH.equals(basis.reason());
    boolean settledSet =
        basis.settlementTime() != null && !basis.settlementTime().equals(order.getSettledTime());
    boolean settledClear = confirmationLost && order.getSettledTime() != null;
    boolean reasonChange = !Objects.equals(basis.reason(), order.getCommissionBasisReason());
    if (!settledSet && !settledClear && !reasonChange) {
      return;
    }
    LambdaUpdateWrapper<OrderDO> update =
        new LambdaUpdateWrapper<OrderDO>().eq(OrderDO::getId, order.getId());
    if (settledSet) {
      update.set(OrderDO::getSettledTime, basis.settlementTime());
      order.setSettledTime(basis.settlementTime());
    } else if (settledClear) {
      update.set(OrderDO::getSettledTime, null);
      order.setSettledTime(null);
    }
    if (reasonChange) {
      update.set(OrderDO::getCommissionBasisReason, basis.reason());
      order.setCommissionBasisReason(basis.reason());
    }
    orders.update(null, update);
  }

  /** A refund whose conclusion is unknown (or still running) keeps the order in after-sales. */
  static final List<String> UNCONCLUDED_REFUND_STATUSES = List.of("PROCESSING", "UNKNOWN");

  /** Shared unconcluded rule (task 115 review): also drives the creator order summary. */
  static boolean hasUnconcludedRefund(List<RefundDO> refundRows) {
    return refundRows.stream().anyMatch(OrderCommissionSync::isUnconcludedRefund);
  }

  static boolean isUnconcludedRefund(RefundDO refund) {
    return refund.getRefundStatus() != null
        && UNCONCLUDED_REFUND_STATUSES.contains(refund.getRefundStatus());
  }

  /** Open refunds and successful refunds lacking a product line block the entire order. */
  private boolean hasBlockingRefund(long orderId) {
    return !refunds
        .selectList(
            new LambdaQueryWrapper<RefundDO>()
                .select(RefundDO::getId)
                .eq(RefundDO::getOrderId, orderId)
                .and(
                    query ->
                        query
                            .in(RefundDO::getRefundStatus, UNCONCLUDED_REFUND_STATUSES)
                            .or(
                                unassigned ->
                                    unassigned
                                        .eq(RefundDO::getRefundStatus, "SUCCESS")
                                        .isNull(RefundDO::getOrderItemId)))
                .last("LIMIT 1"))
        .isEmpty();
  }
}
