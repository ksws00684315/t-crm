package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order detail response (task 40 §3.2, row 09.04). Amounts in yuan; times UTC.
 *
 * @param merchantNo the shop's Weidian shop id shown to the operator
 * @param itemAmount sum of the line pay amounts (商品金额)
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
      BigDecimal payAmount,
      BigDecimal refundAmount,
      String status) {}
}
