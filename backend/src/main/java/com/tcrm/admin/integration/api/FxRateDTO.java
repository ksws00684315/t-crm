package com.tcrm.admin.integration.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Reference rate of one ECB date (design §7.5): {@code 1 CNY = rate USD}, 8 decimals. Display only;
 * bookkeeping is always in CNY (design D11).
 *
 * @param rateDate ECB reference date, shown next to every converted amount
 * @param rate USD per 1 CNY, positive
 */
public record FxRateDTO(LocalDate rateDate, BigDecimal rate) {
  /**
   * Converts a CNY amount to its USD reference amount, rounded to 0.01 HALF_UP (design §7.5).
   *
   * @param cny amount in CNY, not null
   * @return USD amount with scale 2
   */
  public BigDecimal toUsd(BigDecimal cny) {
    return cny.multiply(rate).setScale(2, RoundingMode.HALF_UP);
  }
}
