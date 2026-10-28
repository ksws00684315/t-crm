package com.tcrm.admin.commission.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Commission source snapshot registration for the {@code order} module (task 62, design §4.8.1 /
 * ADR-032 §2). The caller runs inside a transaction and the company tenant scope; implementation
 * joins the caller's transaction and appends append-only revisions when the source version changes.
 * Amounts use CNY scale 2.
 */
public interface CommissionItemApi {
  /**
   * Registers or refreshes the commission source snapshots of the order's item lines. Lines without
   * a source commissionable amount stay unverified: they accrue nothing and never release
   * (requirement 11.01, M0 / Q-1017-1/2 pending).
   *
   * @param order order identity and pay time used for pay-time rate resolution
   * @param items one entry per order item line, keyed by {@code orderItemId}
   */
  void syncOrderItems(OrderSourceDTO order, List<ItemSourceDTO> items);

  /** Order identity for snapshot registration. */
  record OrderSourceDTO(long orderId, long shopId, Instant payTime) {}

  /**
   * Source facts of one order item line.
   *
   * @param orderItemId {@code crm_order_item.id}
   * @param subOrderId Weidian sub order id (or generated stable key)
   * @param kolId attributed KOL id; null when unattributed
   * @param attributionType 0 unmatched, 1 item id, 2 manual, 3 non-KOL, 4 conflict
   * @param productId original product id when the mapping resolved it
   * @param basisCny source commissionable amount (semantic {@code WEIDIAN_COMMISSIONABLE_CNY});
   *     null when the settlement basis check failed, with the reason in {@code basisCheckReason}
   * @param basisIncludesRefund whether the source amount already excludes successful refunds; null
   *     when the source does not say
   * @param successfulRefundCny confirmed successful refunds of the line; null when unknown
   * @param settlementState CONFIRMED / UNSETTLED / UNKNOWN from the source
   * @param settlementTime Weidian settlement time; null when unknown
   * @param completedTime trade completion time; null when unknown
   * @param afterSales true when the line has an unconcluded refund (PROCESSING/SUCCESS/UNKNOWN)
   * @param basisCheckReason why the basis is missing (task 76: NO_SETTLEMENT / UNKNOWN_FLOW_TYPE /
   *     INCOME_MISMATCH / MULTIPLE_KOLS / ALLOCATION_FAILED); null when a basis was computed
   */
  record ItemSourceDTO(
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
