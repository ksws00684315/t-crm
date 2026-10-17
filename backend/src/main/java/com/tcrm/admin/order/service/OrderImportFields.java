package com.tcrm.admin.order.service;

import com.tcrm.admin.order.model.OrderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared Excel cell parsers for order and refund imports (Beijing time, CNY yuan). */
final class OrderImportFields {
  static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

  /**
   * Accepts Excel display text for datetime cells: {@code -} or {@code /} separators, unpadded
   * month/day/hour, optional seconds (default 0). Examples: {@code 2026-10-17 09:05:07}, {@code
   * 2026/10/17 9:05}, {@code 2026/10/17 9:05:07}.
   */
  private static final Pattern BEIJING_TIME =
      Pattern.compile(
          "^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})\\s+(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?$");

  private static final Pattern WEIDIAN_ID = Pattern.compile("^[0-9]{1,32}$");
  private static final Pattern MONEY = Pattern.compile("^[0-9]+(\\.[0-9]{1,2})?$");
  private static final Pattern QUANTITY = Pattern.compile("^[1-9][0-9]*$");
  private static final Map<String, OrderStatus> STATUSES =
      Map.ofEntries(
          Map.entry("待付款", OrderStatus.UNPAID),
          Map.entry("已付款", OrderStatus.PAID),
          Map.entry("已发货", OrderStatus.SHIPPED),
          Map.entry("已收货", OrderStatus.RECEIVED),
          Map.entry("已完成", OrderStatus.COMPLETED),
          Map.entry("已关闭", OrderStatus.CLOSED),
          Map.entry("UNPAID", OrderStatus.UNPAID),
          Map.entry("PAID", OrderStatus.PAID),
          Map.entry("SHIPPED", OrderStatus.SHIPPED),
          Map.entry("RECEIVED", OrderStatus.RECEIVED),
          Map.entry("COMPLETED", OrderStatus.COMPLETED),
          Map.entry("CLOSED", OrderStatus.CLOSED));

  private OrderImportFields() {}

  static boolean isWeidianId(String value) {
    return value != null && WEIDIAN_ID.matcher(value).matches();
  }

  static OrderStatus status(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    OrderStatus direct = STATUSES.get(text);
    if (direct != null) {
      return direct;
    }
    return STATUSES.get(text.trim().toUpperCase(Locale.ROOT));
  }

  static boolean payTimeRequired(OrderStatus status) {
    return status == OrderStatus.PAID
        || status == OrderStatus.SHIPPED
        || status == OrderStatus.RECEIVED
        || status == OrderStatus.COMPLETED;
  }

  static Instant beijingTime(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    var match = BEIJING_TIME.matcher(text.strip());
    if (!match.matches()) {
      return null;
    }
    try {
      int year = Integer.parseInt(match.group(1));
      int month = Integer.parseInt(match.group(2));
      int day = Integer.parseInt(match.group(3));
      int hour = Integer.parseInt(match.group(4));
      int minute = Integer.parseInt(match.group(5));
      int second = match.group(6) == null ? 0 : Integer.parseInt(match.group(6));
      return LocalDateTime.of(year, month, day, hour, minute, second).atZone(BEIJING).toInstant();
    } catch (DateTimeException | NumberFormatException e) {
      return null;
    }
  }

  /** Non-negative CNY amount with at most two decimals; {@code null} when invalid. */
  static BigDecimal money(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String value = text.replace(",", "").strip();
    if (!MONEY.matcher(value).matches()) {
      return null;
    }
    BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    if (amount.precision() > 14) {
      return null;
    }
    return amount;
  }

  static Integer quantity(String text) {
    if (text == null || text.isBlank() || !QUANTITY.matcher(text.strip()).matches()) {
      return null;
    }
    try {
      return Integer.valueOf(text.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static int codePointLength(String value) {
    return value == null ? 0 : value.codePointCount(0, value.length());
  }
}
