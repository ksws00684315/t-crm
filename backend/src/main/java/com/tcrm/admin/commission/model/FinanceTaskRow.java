package com.tcrm.admin.commission.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the unified finance task list (13.03): commission withdrawals and creator prepays
 * share the page, each with its own source status plus one shared lifecycle status for filtering.
 */
public record FinanceTaskRow(
    long id,
    long kolId,
    String taskNo,
    String taskType,
    String sourceStatus,
    String status,
    BigDecimal amountCny,
    Instant createdAt) {

  /** Shared lifecycle: review queue, payment queue, done, rejected or cancelled. */
  public static String mapWithdrawalStatus(String status) {
    return switch (status) {
      case "PENDING_REVIEW" -> "PENDING_REVIEW";
      case "PENDING_PAYMENT" -> "PENDING_PAYMENT";
      case "PAID" -> "PAID";
      case "REJECTED" -> "REJECTED";
      case "WITHDRAWN" -> "CANCELLED";
      default -> throw new IllegalArgumentException("Unknown withdrawal status " + status);
    };
  }

  public static String mapPrepayStatus(String status) {
    return switch (status) {
      case "WAITING_DETAILS", "PENDING_OPERATIONS_REVIEW", "PENDING_FINANCE_REVIEW" ->
          "PENDING_REVIEW";
      case "APPROVED" -> "PENDING_PAYMENT";
      case "PAID" -> "PAID";
      case "REJECTED" -> "REJECTED";
      case "CANCELLED" -> "CANCELLED";
      default -> throw new IllegalArgumentException("Unknown prepay status " + status);
    };
  }
}
