package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Refund list row (task 40 §3.3, row 09.04).
 *
 * @param channel sales channel of the order, WEIDIAN today
 * @param shopCode shop code; null until the shop module's column lands (task 37 merge)
 * @param refundType REFUND_ONLY / RETURN_REFUND / UNKNOWN
 * @param refundStatus PROCESSING / SUCCESS / CLOSED / UNKNOWN
 * @param applyTime when the refund was applied ({@code crm_refund.refund_time})
 */
public record RefundDTO(
    Long id,
    String refundNo,
    Long orderId,
    String orderNo,
    String channel,
    Long shopId,
    String shopName,
    String shopCode,
    Long kolId,
    String kolCode,
    String kolName,
    String refundType,
    String refundStatus,
    BigDecimal amount,
    Instant applyTime,
    Instant finishTime) {}
