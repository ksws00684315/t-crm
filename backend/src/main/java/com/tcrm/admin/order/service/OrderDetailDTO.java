package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order detail response (task 40 §3.2, row 09.04). Amounts in yuan; times UTC.
 *
 * @param merchantNo the shop's Weidian shop id shown to the operator
 * @param itemAmount sum of verified historical unit sale prices times quantities; null when missing
 * @param totalAmount total reported by the source order (Weidian). Independent of the historical
 *     unit prices: a missing line price never blanks the merchant's own order total
 * @param source API or IMPORT (1 / 2 of {@code crm_order.source})
 * @param lastSyncTime last write of this order, sync or import
 */
public record OrderDetailDTO(
    Long id,
    String orderNo,
    String channel,
    Long shopId,
    String shopName,
    String shopCode,
    String merchantNo,
    Long kolId,
    String kolCode,
    String kolName,
    String orderStatus,
    String refundStatus,
    String attributionStatus,
    String source,
    Instant orderTime,
    Instant payTime,
    Instant lastSyncTime,
    Instant createTime,
    BigDecimal itemAmount,
    BigDecimal totalAmount,
    BigDecimal payAmount,
    BigDecimal freight,
    List<OrderDetailItemDTO> items) {

  /** One product line of the detail view (row 09.04). */
  record OrderDetailItemDTO(
      Long id,
      String imageUrl,
      String itemName,
      String weidianItemId,
      String skuName,
      Long kolId,
      String kolCode,
      String kolName,
      Integer quantity,
      BigDecimal unitPrice,
      BigDecimal totalAmount,
      BigDecimal payAmount,
      BigDecimal refundAmount,
      String status) {}
}
