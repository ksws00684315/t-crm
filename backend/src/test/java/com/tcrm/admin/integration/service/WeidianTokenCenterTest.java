package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WeidianTokenCenterTest {
  @Test
  void shouldInterpretRefreshExpiryAsAbsoluteMilliseconds_whenValueExceedsThreshold() {
    Instant authorizedAt = Instant.parse("2026-10-01T00:00:00Z");
    var value = JsonMapper.builder().build().valueToTree(1_792_022_400_000L);

    assertThat(WeidianTokenCenter.refreshExpiry(value, authorizedAt))
        .isEqualTo(Instant.ofEpochMilli(1_792_022_400_000L));
  }

  @Test
  void shouldInterpretRefreshExpiryAsDuration_whenValueIsBelowThreshold() {
    Instant authorizedAt = Instant.parse("2026-10-01T00:00:00Z");
    var value = JsonMapper.builder().build().valueToTree(2_592_000_000L);

    assertThat(WeidianTokenCenter.refreshExpiry(value, authorizedAt))
        .isEqualTo(authorizedAt.plusMillis(2_592_000_000L));
  }

  @Test
  void shouldCapRefreshExpiryAtThirtyDays_whenAbsoluteValueExceedsLimit() {
    Instant authorizedAt = Instant.parse("2026-10-01T00:00:00Z");
    var value = JsonMapper.builder().build().valueToTree(1_900_000_000_000L);

    assertThat(WeidianTokenCenter.refreshExpiry(value, authorizedAt))
        .isEqualTo(authorizedAt.plusSeconds(30 * 24 * 60 * 60L));
  }
}
