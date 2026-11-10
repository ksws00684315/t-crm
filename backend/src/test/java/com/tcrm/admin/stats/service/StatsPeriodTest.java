package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.shared.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StatsPeriodTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

  @Test
  void defaultsToSevenBeijingDaysAndCalculatesEqualPrecedingPeriod() {
    var period = StatsPeriod.resolve(null, null, TODAY);
    assertThat(period.from()).isEqualTo(Instant.parse("2026-10-27T16:00:00Z"));
    assertThat(period.until()).isEqualTo(Instant.parse("2026-11-03T16:00:00Z"));
    assertThat(period.previous().until()).isEqualTo(period.from());
    assertThat(period.previous().startDate()).isEqualTo(LocalDate.of(2026, 10, 21));
  }

  @Test
  void validatesInclusiveMaximumAndRejectsIncompleteOrReversedPeriods() {
    assertThat(StatsPeriod.resolve(TODAY.minusDays(89), TODAY, TODAY).endDate()).isEqualTo(TODAY);
    assertThatThrownBy(() -> StatsPeriod.resolve(TODAY.minusDays(90), TODAY, TODAY))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> StatsPeriod.resolve(null, TODAY, TODAY))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> StatsPeriod.resolve(TODAY, null, TODAY))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> StatsPeriod.resolve(TODAY, TODAY.minusDays(1), TODAY))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> StatsPeriod.resolve(LocalDate.of(1899, 12, 31), TODAY, TODAY))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> new StatsPeriod(LocalDate.MAX, LocalDate.MAX))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> new StatsPeriod(LocalDate.MIN, LocalDate.MIN))
        .isInstanceOf(BusinessException.class);
  }
}
