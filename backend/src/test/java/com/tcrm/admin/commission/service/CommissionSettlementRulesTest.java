package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.commission.model.CommissionItemStateDO;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  @Test
  void shouldPreferFreezeDaysOverSettlementConfirmation() {
    // Owner 2026-11-03 (L-052): completed 11-01, freeze 7 days, confirmed 11-05 → release 11-08.
    Instant completed = Instant.parse("2026-11-01T00:00:00Z");
    Instant confirmed = Instant.parse("2026-11-05T00:00:00Z");
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
                confirmed,
                completed,
                false,
                7));
    assertThat(result.releaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_FALLBACK);
    assertThat(result.releaseAt()).isEqualTo(completed.plusSeconds(7 * 86400L));
  }

  @Test
  void shouldReleaseAtSettlementTimeWhenConfirmedWithoutCompletedTime() {
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
  void shouldStayAfterSalesEvenWithCompletedAndConfirmed() {
    Instant completed = Instant.parse("2026-11-01T00:00:00Z");
    Instant confirmed = Instant.parse("2026-11-05T00:00:00Z");
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
                confirmed,
                completed,
                true,
                7));
    assertThat(result.releaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_AFTER_SALES);
    assertThat(result.releaseAt()).isNull();
  }

  @Test
  void shouldAccrueZeroForUnreleasedLineInAfterSales() {
    // Task 113 (定稿 §2.1): pay 100, rate 10%, nothing released, refund open → accrual 0.
    var result = CommissionSettlementRules.evaluate(afterSalesInputs(null, true, null));
    assertThat(result.verified()).isTrue();
    assertThat(result.accruedCny()).isEqualByComparingTo("0.00");
    // The result keeps the real effective basis; only the display reads 0.00 (commissionable).
    assertThat(result.effectiveBasisCny()).isEqualByComparingTo("100.00");
    assertThat(result.releaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_AFTER_SALES);
    assertThat(result.releaseAt()).isNull();
  }

  @Test
  void shouldKeepReleasedAccrualFrozenInAfterSales() {
    // Released 10.00 before the refund opened: the accrual stays 10.00 (never below released), so
    // the snapshot service writes no clawback.
    var result =
        CommissionSettlementRules.evaluate(afterSalesInputs(null, true, new BigDecimal("10.00")));
    assertThat(result.accruedCny()).isEqualByComparingTo("10.00");
    assertThat(result.effectiveBasisCny()).isEqualByComparingTo("100.00");
    assertThat(result.releaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_AFTER_SALES);
  }

  @Test
  void shouldRestoreAccrualWhenRefundIsRejected() {
    // Rejected or closed: no longer after-sales, the regular rule applies again.
    var result = CommissionSettlementRules.evaluate(afterSalesInputs(null, false, null));
    assertThat(result.accruedCny()).isEqualByComparingTo("10.00");
    assertThat(result.releaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_CONFIRMED);
  }

  @Test
  void shouldAccrueNetOfPartialRefundOnceItSucceeds() {
    var result =
        CommissionSettlementRules.evaluate(
            afterSalesInputs(new BigDecimal("30.00"), false, new BigDecimal("10.00")));
    assertThat(result.accruedCny()).isEqualByComparingTo("7.00");
  }

  @Test
  void shouldAccrueZeroOnceFullRefundSucceeds() {
    var result =
        CommissionSettlementRules.evaluate(
            afterSalesInputs(new BigDecimal("100.00"), false, new BigDecimal("10.00")));
    assertThat(result.accruedCny()).isEqualByComparingTo("0.00");
  }

  @Test
  void shouldCapAfterSalesAccrualAtNetOfAnEarlierSuccessfulRefund() {
    // A refund of 30 already succeeded and another is open: the open one keeps the line frozen,
    // the successful one is deducted (7.00 < released 10.00 → the service claws back 3.00).
    var result =
        CommissionSettlementRules.evaluate(
            afterSalesInputs(new BigDecimal("30.00"), true, new BigDecimal("10.00")));
    assertThat(result.accruedCny()).isEqualByComparingTo("7.00");
  }

  @Test
  void shouldShowCommissionableZeroOnlyForUnreleasedAfterSalesLines() {
    BigDecimal basis = new BigDecimal("100.00");
    assertThat(CommissionSettlementRules.commissionable(basis, false, null, true, null))
        .isEqualByComparingTo("0.00");
    assertThat(
            CommissionSettlementRules.commissionable(
                basis, false, null, true, new BigDecimal("0.00")))
        .isEqualByComparingTo("0.00");
    assertThat(
            CommissionSettlementRules.commissionable(
                basis, false, null, true, new BigDecimal("10.00")))
        .isEqualByComparingTo("100.00");
    assertThat(
            CommissionSettlementRules.commissionable(
                basis, false, new BigDecimal("30.00"), false, null))
        .isEqualByComparingTo("70.00");
    assertThat(CommissionSettlementRules.commissionable(null, false, null, true, null)).isNull();
  }

  @ParameterizedTest(name = "accrued {0}, released {1} -> adjustment {2}")
  @CsvSource({
    "15.00, 0.00, 0.00, CREDIT",
    "15.00, 15.00, 0.00, CREDIT",
    "12.00, 15.00, -3.00, DEBIT",
    "0.00, 15.00, -15.00, DEBIT",
    "15.00, , 0.00, CREDIT"
  })
  void shouldCountOnlyAReductionAfterReleaseAsRefundAdjustment(
      String accrued, String released, String adjustment, String direction) {
    BigDecimal value =
        CommissionSettlementRules.refundAdjustment(
            new BigDecimal(accrued), released == null ? null : new BigDecimal(released));
    assertThat(value.toPlainString()).isEqualTo(adjustment);
    assertThat(CommissionSettlementRules.adjustmentDirection(value)).isEqualTo(direction);
  }

  @Test
  void shouldRenderCommissionableTextOrNull() {
    CommissionItemStateDO row = new CommissionItemStateDO();
    row.setBasisCny(new BigDecimal("100.00"));
    row.setSuccessfulRefundCny(new BigDecimal("30.00"));
    assertThat(CommissionSettlementRules.commissionableText(row)).isEqualTo("70.00");
    row.setBasisCny(null);
    assertThat(CommissionSettlementRules.commissionableText(row)).isNull();
  }

  /** Pay 100, rate 10%, confirmed settlement; refund, after-sales flag and released vary. */
  private static CommissionSettlementRules.Inputs afterSalesInputs(
      BigDecimal successfulRefund, boolean afterSales, BigDecimal released) {
    return new CommissionSettlementRules.Inputs(
        new BigDecimal("100.00"),
        false,
        successfulRefund,
        10L,
        1,
        new BigDecimal("0.1000"),
        "CONFIRMED",
        SETTLED,
        null,
        afterSales,
        7,
        released);
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
        freezeDays,
        null);
  }
}
