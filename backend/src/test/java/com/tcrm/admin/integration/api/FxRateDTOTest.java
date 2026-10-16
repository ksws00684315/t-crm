package com.tcrm.admin.integration.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FxRateDTOTest {
  private static final FxRateDTO RATE =
      new FxRateDTO(LocalDate.of(2026, 10, 15), new BigDecimal("0.14019164"));

  @Test
  void shouldConvertCnyToUsdWithTwoDecimals_whenAmountIsOrdinary() {
    // 100.00 × 0.14019164 = 14.019164 → 14.02; 1234.56 × 0.14019164 = 173.0749910784 → 173.07
    assertThat(RATE.toUsd(new BigDecimal("100.00"))).isEqualTo(new BigDecimal("14.02"));
    assertThat(RATE.toUsd(new BigDecimal("1234.56"))).isEqualTo(new BigDecimal("173.07"));
  }

  @Test
  void shouldRoundHalfUp_whenTheThirdDecimalIsExactlyFive() {
    // 0.04 × 0.125 = 0.005 → 0.01 with HALF_UP (HALF_EVEN would give 0.00)
    FxRateDTO eighth = new FxRateDTO(LocalDate.of(2026, 10, 15), new BigDecimal("0.125"));

    assertThat(eighth.toUsd(new BigDecimal("0.04"))).isEqualTo(new BigDecimal("0.01"));
  }

  @Test
  void shouldRoundDownToZero_whenTheAmountIsTiny() {
    // 0.03 × 0.14019164 = 0.0042057492 → 0.00
    assertThat(RATE.toUsd(new BigDecimal("0.03"))).isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  void shouldKeepTheSign_whenTheAmountIsNegative() {
    // −100.00 × 0.14019164 = −14.019164 → −14.02 (HALF_UP rounds away from zero)
    assertThat(RATE.toUsd(new BigDecimal("-100.00"))).isEqualTo(new BigDecimal("-14.02"));
  }
}
