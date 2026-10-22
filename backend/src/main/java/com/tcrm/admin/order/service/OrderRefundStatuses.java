package com.tcrm.admin.order.service;

import com.tcrm.admin.order.model.RefundDO;
import java.math.BigDecimal;
import java.util.List;

/**
 * Order-level refund status for list and detail display (task 40 §3.1, row 08.02). Not persisted:
 * derived from the order's refund rows each time it is shown.
 *
 * <ul>
 *   <li>{@link #NONE} 无退款 — the order has no refund rows;
 *   <li>{@link #PROCESSING} 退款中 — any refund row is PROCESSING;
 *   <li>{@link #PARTIAL_SUCCESS} 部分退款成功 — no PROCESSING row, successful amounts below the paid
 *       amount;
 *   <li>{@link #FULL_SUCCESS} 全额退款成功 — no PROCESSING row, successful amounts equal the paid amount.
 * </ul>
 *
 * <p>Only SUCCESS rows count into the successful amount; their finish_time is the statistic date
 * (row 08.02).
 */
public final class OrderRefundStatuses {
  public static final String NONE = "NONE";
  public static final String PROCESSING = "PROCESSING";
  public static final String PARTIAL_SUCCESS = "PARTIAL_SUCCESS";
  public static final String FULL_SUCCESS = "FULL_SUCCESS";

  private OrderRefundStatuses() {}

  /**
   * Derives the display status.
   *
   * @param refunds the order's refund rows (any status)
   * @param payAmount the order's paid amount; a null amount can never reach FULL_SUCCESS
   */
  public static String of(List<RefundDO> refunds, BigDecimal payAmount) {
    if (refunds == null || refunds.isEmpty()) {
      return NONE;
    }
    boolean processing = false;
    BigDecimal succeeded = BigDecimal.ZERO;
    for (var refund : refunds) {
      if ("PROCESSING".equals(refund.getRefundStatus())) {
        processing = true;
      } else if ("SUCCESS".equals(refund.getRefundStatus()) && refund.getAmount() != null) {
        succeeded = succeeded.add(refund.getAmount());
      }
    }
    if (processing) {
      return PROCESSING;
    }
    if (succeeded.signum() == 0) {
      return NONE;
    }
    if (payAmount != null && succeeded.compareTo(payAmount) >= 0) {
      return FULL_SUCCESS;
    }
    return PARTIAL_SUCCESS;
  }
}
