package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Creator workbench overview (task 64). Sales figures are order pay amounts (freight included,
 * refunds not deducted; task 112); commission amounts come from the commission port.
 */
public record KolAnalyticsOverviewDTO(
    Instant asOf,
    BigDecimal salesCny,
    long paidOrderCount,
    BigDecimal commissionCny,
    BigDecimal withdrawnCny,
    BigDecimal withdrawingCny,
    BigDecimal frozenCny,
    List<Day> daily) {

  /**
   * One calendar day of the trend; {@code date} is formatted as {@code yyyy-MM-dd}. {@code
   * orderCount} is the number of attributed orders paid that day (task 92).
   */
  public record Day(String date, BigDecimal salesCny, BigDecimal commissionCny, long orderCount) {}
}
