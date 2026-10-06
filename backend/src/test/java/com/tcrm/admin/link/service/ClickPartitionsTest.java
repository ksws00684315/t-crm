package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClickPartitionsTest {
  @Test
  void shouldNamePartitionByMonth() {
    assertThat(ClickPartitions.name(YearMonth.of(2026, 1))).isEqualTo("crm_click_log_202601");
    assertThat(ClickPartitions.month("crm_click_log_202612")).hasValue(YearMonth.of(2026, 12));
    assertThat(ClickPartitions.from(YearMonth.of(2026, 12))).isEqualTo("2026-12-01");
    assertThat(ClickPartitions.to(YearMonth.of(2026, 12))).isEqualTo("2027-01-01");
  }

  @Test
  void shouldIgnoreNamesThatAreNotMonthPartitions() {
    assertThat(ClickPartitions.month("crm_click_log_202613")).isEmpty();
    assertThat(ClickPartitions.month("crm_click_log_202600")).isEmpty();
    assertThat(ClickPartitions.month("crm_click_log_default")).isEmpty();
    assertThat(ClickPartitions.month("crm_click_log_202601; DROP TABLE x")).isEmpty();
    assertThat(ClickPartitions.month(null)).isEmpty();
  }

  @Test
  void shouldRequireCurrentAndNextTwoMonths_acrossYearEnd() {
    assertThat(ClickPartitions.required(LocalDate.of(2026, 11, 30)))
        .containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1));
    assertThat(ClickPartitions.required(LocalDate.of(2026, 12, 1)))
        .containsExactly(YearMonth.of(2026, 12), YearMonth.of(2027, 1), YearMonth.of(2027, 2));
  }

  @Test
  void shouldExpirePartitionsEndingThirteenMonthsAgo() {
    var existing =
        List.of(
            "crm_click_log_202508",
            "crm_click_log_202509",
            "crm_click_log_202510",
            "crm_click_log_202610",
            "crm_click_log_default");
    assertThat(ClickPartitions.expired(existing, LocalDate.of(2026, 10, 6)))
        .containsExactly("crm_click_log_202508");
    assertThat(ClickPartitions.expired(existing, LocalDate.of(2026, 10, 31)))
        .containsExactly("crm_click_log_202508");
    // A partition goes on the day its upper bound is exactly 13 months ago.
    assertThat(ClickPartitions.expired(existing, LocalDate.of(2026, 11, 1)))
        .containsExactly("crm_click_log_202508", "crm_click_log_202509");
  }

  @Test
  void shouldExpireAcrossYearEnd() {
    var existing = List.of("crm_click_log_202511", "crm_click_log_202512", "crm_click_log_202601");
    assertThat(ClickPartitions.expired(existing, LocalDate.of(2027, 1, 15)))
        .containsExactly("crm_click_log_202511");
    assertThat(ClickPartitions.expired(existing, LocalDate.of(2027, 2, 1)))
        .containsExactly("crm_click_log_202511", "crm_click_log_202512");
  }
}
