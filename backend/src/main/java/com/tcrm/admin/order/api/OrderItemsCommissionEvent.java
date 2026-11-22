package com.tcrm.admin.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * In-transaction signal that an order's item lines changed, published by the {@code order} module
 * (task 62, design §4.8.1). The {@code commission} module listens before commit and registers
 * source snapshots from these facts. Spring events avoid a module cycle: {@code commission} already
 * depends on {@code notification}, which listens to order events (spec 01 §3).
 */
public record OrderItemsCommissionEvent(
    long orderId, long shopId, Instant payTime, OrderFacts order, List<ItemFacts> items) {

  /**
   * Order-level facts for the finance flow postings (task 159, 财务流转口径规则 task 158). The commission
   * module records the income-side subjects from these before the order transaction commits;
   * commission-side subjects are recorded next to their own trigger points.
   *
   * @param orderStatus {@code OrderStatus} name of the order (CLOSED drives the close postings)
   * @param payAmount order pay amount; null while unpaid
   * @param orderTotalCny order amount owed at placement (goods total + freight); null when any
   *     historical unit price is missing, so no pre-income is booked (task 158 §1.1)
   * @param orderTime platform placement time ({@code crm_order.order_time}), the pre-income
   *     business time; falls back to the row creation time when the platform time is absent
   * @param orderUpdatedAt last update time of the order row; the observation time of a close
   * @param settledTime settlement confirmation time; null while unsettled
   * @param settlementIncomeCny goods-income flow amount of the order ({@code toCny} of {@code
   *     amount_raw}); null when no goods-income flow exists. Recorded as settlement evidence only
   * @param refunds successful refund rows of the order (id and amount known)
   */
  public record OrderFacts(
      String orderStatus,
      BigDecimal payAmount,
      BigDecimal orderTotalCny,
      Instant orderTime,
      Instant orderUpdatedAt,
      Instant settledTime,
      BigDecimal settlementIncomeCny,
      List<RefundFacts> refunds) {}

  /**
   * One successful refund row.
   *
   * @param refundId {@code crm_refund.id}
   * @param orderItemId the touched line; null on an order-level refund
   * @param amount the refund amount
   * @param refundStatus raw refund status; only SUCCESS rows are carried
   * @param occurredAt the refund conclusion time (finish time when present, else refund time)
   */
  public record RefundFacts(
      long refundId,
      Long orderItemId,
      BigDecimal amount,
      String refundStatus,
      Instant occurredAt) {}

  /**
   * Source facts of one item line.
   *
   * @param orderItemId {@code crm_order_item.id}
   * @param subOrderId Weidian sub order id (or generated stable key)
   * @param kolId attributed KOL id; null when unattributed
   * @param attributionType 0 unmatched, 1 item id, 2 manual, 3 non-KOL, 4 conflict
   * @param productId original product id when the mapping resolved it
   * @param basisCny source commissionable amount (task 81 / D-01: the order pay amount split to the
   *     line by pay ratio); null when the settlement basis check failed, with the reason in {@code
   *     basisCheckReason}
   * @param basisIncludesRefund whether the source amount already excludes successful refunds
   * @param successfulRefundCny confirmed successful refunds of the line; null when unknown
   * @param settlementState CONFIRMED / UNSETTLED / UNKNOWN from the source; UNPAID for an unpaid
   *     order
   * @param settlementTime Weidian settlement time; null when unknown
   * @param completedTime trade completion time; null when unknown
   * @param afterSales true when any refund of the order is unconcluded (PROCESSING/UNKNOWN); one
   *     order belongs to one creator, so every line of the order carries it (task 113)
   * @param basisCheckReason why the basis is missing (task 76: NO_SETTLEMENT / INCOME_MISMATCH /
   *     MULTIPLE_KOLS / ALLOCATION_FAILED); null when a basis was computed
   */
  public record ItemFacts(
      long orderItemId,
      String subOrderId,
      Long kolId,
      int attributionType,
      Long productId,
      BigDecimal basisCny,
      Boolean basisIncludesRefund,
      BigDecimal successfulRefundCny,
      String settlementState,
      Instant settlementTime,
      Instant completedTime,
      boolean afterSales,
      String basisCheckReason) {}
}
