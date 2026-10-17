package com.tcrm.admin.order.service;

import java.math.BigDecimal;

/** Order line response, without buyer information. */
public record OrderItemDTO(
    Long id,
    String subOrderId,
    String weidianItemId,
    String skuId,
    String itemName,
    String skuName,
    Integer quantity,
    BigDecimal payAmount,
    BigDecimal refundAmount,
    Long kolId,
    String kolCode,
    Long productId,
    Long kolItemId,
    Integer attributionType,
    String manualReason) {}
