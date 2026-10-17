package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Masked KOL order summary; excludes every internal order, shop and product identifier. */
public record KolOrderDTO(
    String orderNo,
    Instant payTime,
    String orderStatus,
    BigDecimal payAmount,
    BigDecimal refundAmount,
    List<Line> items) {
  public record Line(
      String itemName,
      String skuName,
      int quantity,
      BigDecimal payAmount,
      BigDecimal refundAmount) {}
}
