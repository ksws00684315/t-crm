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
    long orderId, long shopId, Instant payTime, List<ItemFacts> items) {

  /**
   * Source facts of one item line.
   *
   * @param orderItemId {@code crm_order_item.id}
   * @param subOrderId Weidian sub order id (or generated stable key)
   * @param kolId attributed KOL id; null when unattributed
   * @param attributionType 0 unmatched, 1 item id, 2 manual, 3 non-KOL, 4 conflict
   * @param productId original product id when the mapping resolved it
   * @param basisCny source commissionable amount (semantic {@code WEIDIAN_COMMISSIONABLE_CNY});
   *     null when the settlement basis check failed, with the reason in {@code basisCheckReason}
   * @param basisIncludesRefund whether the source amount already excludes successful refunds
   * @param successfulRefundCny confirmed successful refunds of the line; null when unknown
   * @param settlementState CONFIRMED / UNSETTLED / UNKNOWN from the source
   * @param settlementTime Weidian settlement time; null when unknown
   * @param completedTime trade completion time; null when unknown
   * @param afterSales true when the line has an unconcluded refund (PROCESSING/SUCCESS/UNKNOWN)
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
