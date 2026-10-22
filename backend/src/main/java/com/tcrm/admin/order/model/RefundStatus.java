package com.tcrm.admin.order.model;

/**
 * Normalized refund status (task 40, row 08.02). Mapped from the raw Weidian value by {@code
 * RefundDictionary}; the real raw values stay in {@code crm_refund.raw_status} until M0 verifies
 * them.
 */
public enum RefundStatus {
  /** Refund applied and still running. */
  PROCESSING,
  /** Refund completed; only these count into refund totals and success statistics. */
  SUCCESS,
  /** Refund closed or cancelled without money moving. */
  CLOSED,
  /** Raw value not recognized by the mapping. */
  UNKNOWN
}
