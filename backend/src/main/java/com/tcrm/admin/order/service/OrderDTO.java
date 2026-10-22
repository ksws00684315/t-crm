package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Administrator order response; deliberately excludes buyer_hash and external raw data. Task 40
 * adds the list columns of row 09.04: channel, order-level refund status, freight and create time.
 *
 * @param weidianOrderId legacy field name of the order number (kept for the current admin page)
 * @param orderNo Weidian order number, same value as {@code weidianOrderId} (contract, task 40)
 * @param channel sales channel, WEIDIAN today
 * @param refundStatus order-level status from {@link OrderRefundStatuses}
 * @param shopCode shop code from {@code crm_shop.shop_code}
 */
public record OrderDTO(
    Long id,
    Long shopId,
    String shopName,
    String shopCode,
    String channel,
    String weidianOrderId,
    String orderNo,
    Long kolId,
    String kolCode,
    String kolName,
    Long bdUserId,
    String bdUserName,
    String orderStatus,
    String refundStatus,
    String attributionStatus,
    Instant orderTime,
    Instant payTime,
    Instant createTime,
    BigDecimal totalAmount,
    BigDecimal payAmount,
    BigDecimal freight,
    BigDecimal refundAmount,
    List<OrderItemDTO> items) {}
