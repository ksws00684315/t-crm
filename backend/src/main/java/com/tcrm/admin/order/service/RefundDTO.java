package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Refund list row (task 40 §3.3, row 09.04).
 *
 * @param channel sales channel of the order, WEIDIAN today
 * @param shopCode shop code from {@code crm_shop.shop_code}
 * @param refundType REFUND_ONLY / RETURN_REFUND / UNKNOWN
 * @param refundStatus PROCESSING / SUCCESS / CLOSED / UNKNOWN
 * @param applyTime when the refund was applied ({@code crm_refund.refund_time})
 * @param commissionStatus order-level commission status (task 115, 1105 sheet B12): the most active
 *     line of the order's creator, same ladder as the creator portal ({@code WITHDRAWING}, {@code
 *     WITHDRAWABLE}, {@code FROZEN}, {@code WITHDRAWN}, {@code INVALID}, {@code NO_COMMISSION});
 *     null when hidden, the order has no creator or is unpaid
 * @param commissionAfterSales the order is in after-sales (task 113)
 * @param commissionHidden the caller lacks {@code commission:view} or OWN coverage of the order
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
    Instant finishTime,
    String commissionStatus,
    boolean commissionAfterSales,
    boolean commissionHidden) {}
