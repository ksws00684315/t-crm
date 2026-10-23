package com.tcrm.admin.order.service;

import java.time.Instant;
import java.util.List;

/** Masked KOL order summary; amounts are decimal strings and missing settlement data stays null. */
public record KolOrderDTO(
    String orderNo,
    Instant payTime,
    String orderStatus,
    String settlementStatus,
    String payAmount,
    String refundAmount,
    List<Line> items) {
  public record Line(
      String itemName,
      String skuName,
      int quantity,
      String payAmount,
      String refundAmount,
      String rateSnapshot,
      String commissionAmount,
      String settlementStatus) {}
}
