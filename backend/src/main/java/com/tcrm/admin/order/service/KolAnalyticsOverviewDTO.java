package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Creator workbench overview (task 64). Sales figures are computed from the creator's own order
 * lines; commission amounts stay at zero until task 62 provides them through the commission {@code
 * api} package.
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

  /** One calendar day of the trend; {@code date} is formatted as {@code yyyy-MM-dd}. */
  public record Day(String date, BigDecimal salesCny, BigDecimal commissionCny) {}
}
