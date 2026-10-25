package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Masked KOL order summary; excludes every internal order, shop and product identifier. Commission
 * fields come from the commission module through its order.api port (task 62): line-level {@code
 * settlementStatus}, {@code rateSnapshot} and {@code commissionAmount} (null while unverified) and
 * the order-level status as the most active own line (appendix B ladder).
 */
public record KolOrderDTO(
    String orderNo,
    Instant payTime,
    String orderStatus,
    BigDecimal payAmount,
    BigDecimal refundAmount,
    String settlementStatus,
    List<Line> items) {
  public record Line(
      String itemName,
      String skuName,
      int quantity,
      BigDecimal payAmount,
      BigDecimal refundAmount,
      String settlementStatus,
      BigDecimal rateSnapshot,
      BigDecimal commissionAmount) {}
}
