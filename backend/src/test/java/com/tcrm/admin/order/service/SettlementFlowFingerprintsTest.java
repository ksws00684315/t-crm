package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests of the settlement flow dedup fingerprint (task 76 §3.2). */
class SettlementFlowFingerprintsTest {
  private static final Instant TIME = Instant.ofEpochMilli(1_761_234_567_000L);

  @Test
  void shouldReturnSameFingerprintForIdenticalFlows() {
    String first =
        SettlementFlowFingerprints.of(
            30L, "INCOME_DETAIL", "WD-1", "R-1", "收入", "货款收入", TIME, new BigDecimal("600"), "");
    String second =
        SettlementFlowFingerprints.of(
            30L, "INCOME_DETAIL", "WD-1", "R-1", "收入", "货款收入", TIME, new BigDecimal("600"), "");

    assertThat(first).hasSize(64).isEqualTo(second);
  }

  @Test
  void shouldDifferWhenAnyFingerprintFieldDiffers() {
    String base =
        SettlementFlowFingerprints.of(
            30L, "INCOME_DETAIL", "WD-1", null, "收入", "货款收入", TIME, new BigDecimal("600"), "note");

    assertThat(
            SettlementFlowFingerprints.of(
                31L,
                "INCOME_DETAIL",
                "WD-1",
                null,
                "收入",
                "货款收入",
                TIME,
                new BigDecimal("600"),
                "note"))
        .isNotEqualTo(base);
    assertThat(
            SettlementFlowFingerprints.of(
                30L,
                "PRE_SETTLE_REFUND",
                "WD-1",
                null,
                "收入",
                "货款收入",
                TIME,
                new BigDecimal("600"),
                "note"))
        .isNotEqualTo(base);
    assertThat(
            SettlementFlowFingerprints.of(
                30L,
                "INCOME_DETAIL",
                "WD-2",
                null,
                "收入",
                "货款收入",
                TIME,
                new BigDecimal("600"),
                "note"))
        .isNotEqualTo(base);
    assertThat(
            SettlementFlowFingerprints.of(
                30L,
                "INCOME_DETAIL",
                "WD-1",
                "R-9",
                "收入",
                "货款收入",
                TIME,
                new BigDecimal("600"),
                "note"))
        .isNotEqualTo(base);
    assertThat(
            SettlementFlowFingerprints.of(
                30L,
                "INCOME_DETAIL",
                "WD-1",
                null,
                "支出",
                "货款收入",
                TIME,
                new BigDecimal("600"),
                "note"))
        .isNotEqualTo(base);
    assertThat(
            SettlementFlowFingerprints.of(
                30L,
                "INCOME_DETAIL",
                "WD-1",
                null,
                "收入",
                "分润",
                TIME,
                new BigDecimal("600"),
                "note"))
        .isNotEqualTo(base);
    assertThat(
            SettlementFlowFingerprints.of(
                30L,
                "INCOME_DETAIL",
                "WD-1",
                null,
                "收入",
                "货款收入",
                TIME.plusMillis(1),
                new BigDecimal("600"),
                "note"))
        .isNotEqualTo(base);
    assertThat(
            SettlementFlowFingerprints.of(
                30L,
                "INCOME_DETAIL",
                "WD-1",
                null,
                "收入",
                "货款收入",
                TIME,
                new BigDecimal("-600"),
                "note"))
        .isNotEqualTo(base);
    assertThat(
            SettlementFlowFingerprints.of(
                30L,
                "INCOME_DETAIL",
                "WD-1",
                null,
                "收入",
                "货款收入",
                TIME,
                new BigDecimal("600"),
                "other"))
        .isNotEqualTo(base);
  }

  @Test
  void shouldIgnoreTrailingZerosButKeepSign() {
    // 600, 600.0 and 600.00 are the same amount; -600 is not.
    String plain =
        SettlementFlowFingerprints.of(
            30L, "INCOME_DETAIL", "WD-1", null, "收入", "货款收入", TIME, new BigDecimal("600"), "");
    assertThat(
            SettlementFlowFingerprints.of(
                30L,
                "INCOME_DETAIL",
                "WD-1",
                null,
                "收入",
                "货款收入",
                TIME,
                new BigDecimal("600.0"),
                ""))
        .isEqualTo(plain);
    assertThat(
            SettlementFlowFingerprints.of(
                30L,
                "INCOME_DETAIL",
                "WD-1",
                null,
                "收入",
                "货款收入",
                TIME,
                new BigDecimal("600.00"),
                ""))
        .isEqualTo(plain);
    assertThat(
            SettlementFlowFingerprints.of(
                30L, "INCOME_DETAIL", "WD-1", null, "收入", "货款收入", TIME, new BigDecimal("-600"), ""))
        .isNotEqualTo(plain);
  }

  @Test
  void shouldNotShiftFieldsWhenValuesContainSeparatorCharacters() {
    // A remark containing the canonical separator must never collide with a different split.
    String withPipe =
        SettlementFlowFingerprints.of(
            30L, "INCOME_DETAIL", "WD-1", null, "收入", "货款收入", TIME, new BigDecimal("600"), "a|b|c");
    String shifted =
        SettlementFlowFingerprints.of(
            30L, "INCOME_DETAIL", "WD-1", null, "收入|b", "c", TIME, new BigDecimal("600"), "a");

    assertThat(withPipe).isNotEqualTo(shifted);
  }
}
