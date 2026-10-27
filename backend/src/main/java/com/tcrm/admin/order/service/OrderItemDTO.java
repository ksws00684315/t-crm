package com.tcrm.admin.order.service;

import java.math.BigDecimal;

/**
 * Order line response, without buyer information. Task 71 (handoff B05) adds the line image and the
 * line KOL's display name.
 *
 * @param imageUrl main image of the line's original product; null when the line has no product id,
 *     the product is gone or it has no image (same source as the detail endpoint)
 * @param kolName display name of the line's KOL; null together with {@code kolCode} when the line
 *     is unattributed or the KOL is deleted
 */
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
    String kolName,
    Long productId,
    Long kolItemId,
    Integer attributionType,
    String manualReason,
    String imageUrl) {}
