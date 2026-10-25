package com.tcrm.admin.order.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * Read-only per-item commission view for the creator portal (task 62, design §6.3 / ADR-032
 * appendix B). Port owned by the {@code order} module and implemented by {@code commission}: the
 * order module never depends on commission classes, so the module graph stays acyclic. The caller
 * runs inside the company tenant scope; {@code kolId} is the logged-in creator.
 */
public interface CommissionOrderStateApi {
  /** Creator line states, appendix B ladder order. Task 63 adds the occupation-based ones. */
  String STATUS_WITHDRAWING = "WITHDRAWING";

  String STATUS_WITHDRAWABLE = "WITHDRAWABLE";
  String STATUS_FROZEN = "FROZEN";
  String STATUS_WITHDRAWN = "WITHDRAWN";
  String STATUS_INVALID = "INVALID";

  /** All accepted settlementStatus filter values. */
  Collection<String> STATUS_VALUES =
      List.of(
          STATUS_WITHDRAWING, STATUS_WITHDRAWABLE, STATUS_FROZEN, STATUS_WITHDRAWN, STATUS_INVALID);

  /**
   * Line states of the creator's item lines among {@code orderItemIds}; unknown ids are skipped.
   * Lines without a commission snapshot count as {@code INVALID}.
   */
  List<ItemCommissionStateDTO> listByOrderItemIds(long kolId, Collection<Long> orderItemIds);

  /** Line states of every live commission snapshot of the creator under the given orders. */
  List<ItemCommissionStateDTO> listByKolAndOrderIds(long kolId, Collection<Long> orderIds);

  /** Line states of all live commission snapshots of the creator (workbench totals). */
  List<ItemCommissionStateDTO> listByKol(long kolId);

  /**
   * One line's commission view.
   *
   * @param orderId owning order id
   * @param orderItemId order item line id
   * @param settlementStatus appendix B line status; {@code INVALID} when unverified or refunded out
   * @param rateSnapshot pay-time rate of the latest revision; null when never accrued
   * @param commissionAmount accrued net commission; null when unverified
   * @param releasedAmount already released into the wallet; null when unverified. Frozen =
   *     commissionAmount − releasedAmount.
   */
  record ItemCommissionStateDTO(
      long orderId,
      long orderItemId,
      String settlementStatus,
      BigDecimal rateSnapshot,
      BigDecimal commissionAmount,
      BigDecimal releasedAmount) {}
}
