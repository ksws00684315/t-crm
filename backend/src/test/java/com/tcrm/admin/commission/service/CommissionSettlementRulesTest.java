package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests of the pure settlement rules (design §8.3 / ADR-032 §2 and §5). */
class CommissionSettlementRulesTest {
  private static final Instant SETTLED = Instant.parse("2026-10-20T10:00:00Z");
  private static final Instant COMPLETED = Instant.parse("2026-10-18T10:00:00Z");

  @Test
  void shouldStayUnverifiedWithoutBasisAndAccrueNothing() {
    var result =
        CommissionSettlementRules.evaluate(
            inputs(
                null, null, null, 10L, 1, new BigDecimal("0.1000"), null, null, null, false, null));
    assertThat(result.verified()).isFalse();
    assertThat(result.accruedCny()).isEqualByComparingTo("0");
    assertThat(result.releaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_UNVERIFIED);
    assertThat(result.releaseAt()).isNull();
  }

  @Test
  void shouldStayUnverifiedWithoutAttribution() {
    var result =
        CommissionSettlementRules.evaluate(
            inputs(
                new BigDecimal("100.00"),
                null,
                null,
                null,
                0,
                new BigDecimal("0.1000"),
                null,
                null,
                null,
                false,
                null));
    assertThat(result.verified()).isFalse();
    assertThat(result.accruedCny()).isEqualByComparingTo("0");
  }

  @Test
  void shouldStayUnverifiedWithoutPayTimeRate() {
    var result =
        CommissionSettlementRules.evaluate(
            inputs(
                new BigDecimal("100.00"), null, null, 10L, 1, null, null, null, null, false, null));
    assertThat(result.verified()).isFalse();
    assertThat(result.accruedCny()).isEqualByComparingTo("0");
  }

  @Test
  void shouldAccrueWithHalfUpRounding() {
    // Hand calc: 33.335 × 0.15 = 5.00025 → round half up at 2 decimals → 5.00; 25 × 0.125 = 3.125 →
    // 3.13.
    assertThat(CommissionSettlementRules.accrue(new BigDecimal("33.335"), new BigDecimal("0.1500")))
        .isEqualByComparingTo("5.00");
    assertThat(CommissionSettlementRules.accrue(new BigDecimal("25.00"), new BigDecimal("0.1250")))
        .isEqualByComparingTo("3.13");
  }

  @Test
  void shouldSubtractRefundOnlyWhenSourceDoesNotIncludeIt() {
    // Source basis 100 not refund-inclusive, refund 30 → effective 70.
    assertThat(
            CommissionSettlementRules.effectiveBasis(
                new BigDecimal("100.00"), Boolean.FALSE, new BigDecimal("30.00")))
        .isEqualByComparingTo("70.00");
    // Refund-inclusive source keeps its value.
    assertThat(
            CommissionSettlementRules.effectiveBasis(
                new BigDecimal("70.00"), Boolean.TRUE, new BigDecimal("30.00")))
        .isEqualByComparingTo("70.00");
    // Unknown semantics (null) is treated as not inclusive: full refund zeroes the accrual.
    assertThat(
            CommissionSettlementRules.effectiveBasis(
                new BigDecimal("100.00"), null, new BigDecimal("100.00")))
        .isEqualByComparingTo("0.00");
  }

  @Test
  void shouldClampEffectiveBasisAtZeroOnOverRefund() {
    assertThat(
            CommissionSettlementRules.effectiveBasis(
                new BigDecimal("100.00"), Boolean.FALSE, new BigDecimal("120.00")))
        .isEqualByComparingTo("0.00");
  }

  @Test
  void shouldAccruePartialRefundAndZeroFullRefund() {
    // Partial refund: (100 − 40) × 0.10 = 6.00.
    var partial =
        CommissionSettlementRules.evaluate(
            inputs(
                new BigDecimal("100.00"),
                Boolean.FALSE,
                new BigDecimal("40.00"),
                10L,
                1,
                new BigDecimal("0.1000"),
                "UNKNOWN",
                null,
                null,
                false,
                null));
    assertThat(partial.verified()).isTrue();
    assertThat(partial.accruedCny()).isEqualByComparingTo("6.00");
    // Full refund: basis zeroes out (11.01: settled orders close with a zeroed basis).
    var full =
        CommissionSettlementRules.evaluate(
            inputs(
                new BigDecimal("0.00"),
                Boolean.FALSE,
                new BigDecimal("100.00"),
                10L,
                1,
                new BigDecimal("0.1000"),
                "UNKNOWN",
                null,
                null,
                false,
                null));
    assertThat(full.verified()).isTrue();
    assertThat(full.accruedCny()).isEqualByComparingTo("0.00");
  }

  @Test
  void shouldReleaseAtSettlementTimeWhenConfirmed() {
    var result =
        CommissionSettlementRules.evaluate(
            inputs(
                new BigDecimal("100.00"),
                null,
                null,
                10L,
                1,
                new BigDecimal("0.1000"),
                "CONFIRMED",
                SETTLED,
                null,
                false,
                null));
    assertThat(result.releaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_CONFIRMED);
    assertThat(result.releaseAt()).isEqualTo(SETTLED);
  }

  @Test
  void shouldReleaseAfterCompletedPlusFreezeDays() {
    var result =
        CommissionSettlementRules.evaluate(
            inputs(
                new BigDecimal("100.00"),
                null,
                null,
                10L,
                1,
                new BigDecimal("0.1000"),
                "UNSETTLED",
                null,
                COMPLETED,
                false,
                7));
    assertThat(result.releaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_FALLBACK);
    assertThat(result.releaseAt()).isEqualTo(COMPLETED.plusSeconds(7 * 86400L));
  }

  @Test
  void shouldDefaultFreezeDaysToSeven() {
    var result =
        CommissionSettlementRules.evaluate(
            inputs(
                new BigDecimal("100.00"),
                null,
                null,
                10L,
                1,
                new BigDecimal("0.1000"),
                "UNSETTLED",
                null,
                COMPLETED,
                false,
                null));
    assertThat(result.releaseAt()).isEqualTo(COMPLETED.plusSeconds(7 * 86400L));
  }

  @Test
  void shouldNotReleaseWhenUnsettledAndNotCompleted() {
    var result =
        CommissionSettlementRules.evaluate(
            inputs(
                new BigDecimal("100.00"),
                null,
                null,
                10L,
                1,
                new BigDecimal("0.1000"),
                "UNSETTLED",
                null,
                null,
                false,
                null));
    assertThat(result.releaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_UNSETTLED);
    assertThat(result.releaseAt()).isNull();
  }

  @Test
  void shouldBlockReleaseOnAfterSalesEvenWhenSettled() {
    var result =
        CommissionSettlementRules.evaluate(
            inputs(
                new BigDecimal("100.00"),
                null,
                null,
                10L,
                1,
                new BigDecimal("0.1000"),
                "CONFIRMED",
                SETTLED,
                COMPLETED,
                true,
                7));
    assertThat(result.releaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_AFTER_SALES);
    assertThat(result.releaseAt()).isNull();
  }

  @Test
  void shouldNotTreatConflictOrNonKolAttributionAsVerified() {
    assertThat(CommissionSettlementRules.attributedToKol(10L, 4)).isFalse();
    assertThat(CommissionSettlementRules.attributedToKol(10L, 3)).isFalse();
    assertThat(CommissionSettlementRules.attributedToKol(null, 1)).isFalse();
    assertThat(CommissionSettlementRules.attributedToKol(10L, 1)).isTrue();
    assertThat(CommissionSettlementRules.attributedToKol(10L, 2)).isTrue();
  }

  private static CommissionSettlementRules.Inputs inputs(
      BigDecimal basis,
      Boolean includesRefund,
      BigDecimal refund,
      Long kolId,
      Integer attributionType,
      BigDecimal rate,
      String settlementState,
      Instant settlementTime,
      Instant completedTime,
      boolean afterSales,
      Integer freezeDays) {
    return new CommissionSettlementRules.Inputs(
        basis,
        includesRefund,
        refund,
        kolId,
        attributionType,
        rate,
        settlementState,
        settlementTime,
        completedTime,
        afterSales,
        freezeDays);
  }
}
