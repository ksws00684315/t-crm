package com.tcrm.admin.kol.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CopyTokenDisplayStatusTest {
  private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

  @Test
  void shouldPreferRevokedOverExpiredAndExhausted() {
    assertThat(
            CopyTokenDisplayStatus.of(
                KolCopyTokenDO.STATUS_REVOKED, NOW.minusSeconds(1), 10, 10, NOW))
        .isEqualTo(CopyTokenDisplayStatus.REVOKED);
  }

  @Test
  void shouldPreferExpiredOverExhausted() {
    assertThat(
            CopyTokenDisplayStatus.of(
                KolCopyTokenDO.STATUS_ACTIVE, NOW.minusSeconds(1), 10, 10, NOW))
        .isEqualTo(CopyTokenDisplayStatus.EXPIRED);
  }

  @Test
  void shouldMarkExhaustedAndActive() {
    assertThat(
            CopyTokenDisplayStatus.of(
                KolCopyTokenDO.STATUS_ACTIVE, NOW.plusSeconds(60), 10, 10, NOW))
        .isEqualTo(CopyTokenDisplayStatus.EXHAUSTED);
    assertThat(
            CopyTokenDisplayStatus.of(
                KolCopyTokenDO.STATUS_ACTIVE, NOW.plusSeconds(60), 3, 10, NOW))
        .isEqualTo(CopyTokenDisplayStatus.ACTIVE);
  }
}
