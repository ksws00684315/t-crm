package com.tcrm.admin.order.service;

import com.tcrm.admin.order.model.RefundStatus;
import com.tcrm.admin.order.model.RefundType;
import java.util.Map;

/**
 * The single mapping from raw Weidian refund fields to the normalized dictionaries (task 40, §3.1).
 * Every known raw value lives here and nowhere else; unrecognized values map to UNKNOWN and the
 * original stays in {@code crm_refund.raw_status}. The mapping follows design §8.1a and waits for
 * M0 to confirm the real value sets.
 */
public final class RefundDictionary {
  /**
   * Raw refund status values of {@code refundBasicInfo.status}. The design fixes only 10 = refund
   * completed (design §5.2 crm_refund); intermediate and cancelled values are added here after M0.
   */
  private static final Map<String, RefundStatus> STATUSES = Map.of("10", RefundStatus.SUCCESS);

  /**
   * Raw refund type values of {@code refundBasicInfo.refund_type}: 1 refund only, 2 return and
   * refund (to be confirmed at M0).
   */
  private static final Map<String, RefundType> TYPES =
      Map.of(
          "1", RefundType.REFUND_ONLY,
          "2", RefundType.RETURN_REFUND);

  private RefundDictionary() {}

  /** Maps a raw refund status; null, blank and unrecognized values are UNKNOWN, not an error. */
  public static RefundStatus statusOf(String rawStatus) {
    if (rawStatus == null || rawStatus.isBlank()) {
      return RefundStatus.UNKNOWN;
    }
    return STATUSES.getOrDefault(rawStatus, RefundStatus.UNKNOWN);
  }

  /** Maps a raw refund type; null, blank and unrecognized values are UNKNOWN, not an error. */
  public static RefundType typeOf(String rawType) {
    if (rawType == null || rawType.isBlank()) {
      return RefundType.UNKNOWN;
    }
    return TYPES.getOrDefault(rawType, RefundType.UNKNOWN);
  }
}
