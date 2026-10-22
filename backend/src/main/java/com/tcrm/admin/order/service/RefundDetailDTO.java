package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Refund detail (task 40 §3.3, row 09.04). One refund touches at most one order line in the current
 * schema ({@code crm_refund.order_item_id}); how Weidian models refunds over several lines waits
 * for M0.
 *
 * @param merchantNo the order shop's Weidian shop id
 */
public record RefundDetailDTO(
    Long id,
    String refundNo,
    Long orderId,
    String orderNo,
    String channel,
    Long shopId,
    String shopName,
    String shopCode,
    String merchantNo,
    Long kolId,
    String kolCode,
    String kolName,
    String refundType,
    String refundStatus,
    BigDecimal amount,
    Instant applyTime,
    Instant finishTime,
    List<RefundLineDTO> lines) {

  /** The refunded order line linked by {@code order_item_id} (task 40 §3.3). */
  record RefundLineDTO(
      Long orderItemId,
      String imageUrl,
      String itemName,
      String weidianItemId,
      String skuName,
      Long kolId,
      String kolCode,
      String kolName,
      Integer quantity,
      BigDecimal refundAmount) {}
}
