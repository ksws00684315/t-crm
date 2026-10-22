package com.tcrm.admin.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Mapping and cleanup status text for the creator-item page (task 39). */
final class KolItemStatus {
  static final String ENABLED = "ENABLED";
  static final String DISABLED = "DISABLED";
  static final String NONE = "NONE";
  static final String PENDING = "PENDING";
  static final String DONE = "DONE";
  static final String FAILED = "FAILED";
  static final String CHANNEL = "WEIDIAN";

  private KolItemStatus() {}

  /** ENABLED when {@code validFrom <= now} and {@code validTo} is null or still in the future. */
  static String mapping(Instant now, Instant validFrom, Instant validTo) {
    boolean enabled =
        validFrom != null && !validFrom.isAfter(now) && (validTo == null || validTo.isAfter(now));
    return enabled ? ENABLED : DISABLED;
  }

  /** Maps a cleanup row. No row and an unknown status are NONE. SUCCESS is DONE. */
  static String cleanup(String raw) {
    if (raw == null) {
      return NONE;
    }
    return switch (raw) {
      case "PENDING" -> PENDING;
      case "SUCCESS" -> DONE;
      case "FAILED" -> FAILED;
      default -> NONE;
    };
  }

  /** Two-decimal amount. Null becomes 0.00. */
  static BigDecimal money(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
  }
}
