package com.tcrm.admin.link.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure partition arithmetic of {@code crm_click_log} (task 06 §4.5): one partition per UTC month
 * named {@code crm_click_log_yyyymm}; the current and the next two months must exist; partitions
 * that end on or before the first day of the month 13 months ago are dropped.
 */
public final class ClickPartitions {
  static final String PREFIX = "crm_click_log_";
  static final int FUTURE_MONTHS = 2;
  static final int RETENTION_MONTHS = 13;
  private static final Pattern NAME = Pattern.compile("^crm_click_log_(\\d{4})(0[1-9]|1[0-2])$");
  private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("uuuuMM");

  private ClickPartitions() {}

  public static String name(YearMonth month) {
    return PREFIX + month.format(SUFFIX);
  }

  /** Month of a partition name; empty for anything else (other tables are never touched). */
  public static Optional<YearMonth> month(String name) {
    var matcher = NAME.matcher(name == null ? "" : name);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(
        YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
  }

  /** The current month (UTC) and the next two. */
  public static List<YearMonth> required(LocalDate todayUtc) {
    YearMonth current = YearMonth.from(todayUtc);
    List<YearMonth> months = new ArrayList<>();
    for (int i = 0; i <= FUTURE_MONTHS; i++) {
      months.add(current.plusMonths(i));
    }
    return months;
  }

  /**
   * Existing partitions to drop: the upper bound (first day of the following month) is on or before
   * {@code today − 13 months}. Example: on 2026-10-06 the cutoff is 2025-09-06, so 2025-08 (ends
   * 2025-09-01) is dropped and 2025-09 (ends 2025-10-01) is kept.
   */
  public static List<String> expired(Collection<String> existing, LocalDate todayUtc) {
    LocalDate cutoff = todayUtc.minusMonths(RETENTION_MONTHS);
    List<String> names = new ArrayList<>();
    for (String name : existing) {
      month(name)
          .filter(month -> !month.plusMonths(1).atDay(1).isAfter(cutoff))
          .ifPresent(month -> names.add(name));
    }
    names.sort(null);
    return names;
  }

  /** Lower bound {@code yyyy-MM-01} of a month partition (UTC midnight is appended in SQL). */
  public static String from(YearMonth month) {
    return month.atDay(1).toString();
  }

  /** Upper bound (exclusive) {@code yyyy-MM-01} of a month partition. */
  public static String to(YearMonth month) {
    return month.plusMonths(1).atDay(1).toString();
  }
}
