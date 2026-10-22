package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class KolItemStatusTest {
  private final Instant now = Instant.parse("2026-10-21T00:00:00Z");

  @Test
  void shouldTreatTheOpenWindowAsEnabled() {
    assertThat(KolItemStatus.mapping(now, now, null)).isEqualTo("ENABLED");
    assertThat(KolItemStatus.mapping(now, now.minusSeconds(1), now.plusSeconds(1)))
        .isEqualTo("ENABLED");
    assertThat(KolItemStatus.mapping(now, now.plusSeconds(1), null)).isEqualTo("DISABLED");
    assertThat(KolItemStatus.mapping(now, now.minusSeconds(2), now)).isEqualTo("DISABLED");
    assertThat(KolItemStatus.mapping(now, null, null)).isEqualTo("DISABLED");
  }

  @Test
  void shouldMapCleanupStatus() {
    assertThat(KolItemStatus.cleanup(null)).isEqualTo("NONE");
    assertThat(KolItemStatus.cleanup("PENDING")).isEqualTo("PENDING");
    assertThat(KolItemStatus.cleanup("SUCCESS")).isEqualTo("DONE");
    assertThat(KolItemStatus.cleanup("FAILED")).isEqualTo("FAILED");
    assertThat(KolItemStatus.cleanup("OTHER")).isEqualTo("NONE");
  }

  @Test
  void shouldScaleMoney() {
    assertThat(KolItemStatus.money(null)).isEqualByComparingTo("0.00");
    assertThat(KolItemStatus.money(new BigDecimal("1.2"))).isEqualByComparingTo("1.20");
    assertThat(KolItemStatus.money(new BigDecimal("1.235"))).isEqualByComparingTo("1.24");
  }
}
