package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Masked KOL order summary; excludes every internal order, shop and product identifier. Commission
 * fields come from the commission module through its order.api port (task 62): line-level {@code
 * settlementStatus}, {@code rateSnapshot} and {@code commissionAmount} (null while unverified) and
 * the order-level status as the most active own line (appendix B ladder). {@code payAmount} is the
 * order pay amount, freight included (task 112: one order belongs to one creator). {@code
 * afterSales} marks an order in after-sales (task 113): unreleased commission counts 0 and released
 * commission is frozen until the refund concludes; the portal shows the after-sales hint.
 */
public record KolOrderDTO(
    String orderNo,
    Instant payTime,
    String orderStatus,
    BigDecimal payAmount,
    BigDecimal refundAmount,
    String settlementStatus,
    List<Line> items,
    boolean afterSales) {
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
