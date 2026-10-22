package com.tcrm.admin.order.model;

/**
 * Refund type (task 40, row 08.02): refund only versus return-and-refund. Mapped from the raw
 * Weidian field by {@code RefundDictionary}; unknown values stay UNKNOWN with the original kept in
 * {@code raw_status}.
 */
public enum RefundType {
  /** Refund only, goods not returned. */
  REFUND_ONLY,
  /** Goods returned first, refund after receipt. */
  RETURN_REFUND,
  /** Raw value not recognized by the mapping. */
  UNKNOWN
}
