package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Administrator order response; deliberately excludes buyer_hash and external raw data. */
public record OrderDTO(
    Long id,
    Long shopId,
    String shopName,
    String weidianOrderId,
    Long kolId,
    String kolCode,
    String kolName,
    Long bdUserId,
    String bdUserName,
    String orderStatus,
    String attributionStatus,
    Instant orderTime,
    Instant payTime,
    BigDecimal totalAmount,
    BigDecimal payAmount,
    BigDecimal refundAmount,
    List<OrderItemDTO> items) {}
