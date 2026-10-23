package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.shared.api.CommissionSettlementApi.SourceSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CommissionSettlementRulesTest {
  private static final Instant COMPLETED = Instant.parse("2026-10-01T00:00:00Z");

  @Test
  void doesNotSubtractARefundThatTheVerifiedBasisAlreadyIncludes() {
    SourceSnapshot snapshot =
        snapshot(new BigDecimal("80.00"), new BigDecimal("20.00"), true, "UNKNOWN", false);

    assertThat(CommissionSettlementRules.effectiveBasis(snapshot)).isEqualByComparingTo("80.00");
  }

  @Test
  void subtractsOnlyKnownSuccessfulRefundFromBasisThatExcludesRefunds() {
    SourceSnapshot snapshot =
        snapshot(new BigDecimal("100.00"), new BigDecimal("20.00"), false, "UNKNOWN", false);

    assertThat(CommissionSettlementRules.effectiveBasis(snapshot)).isEqualByComparingTo("80.00");
    assertThat(
            CommissionSettlementRules.effectiveBasis(
                snapshot(new BigDecimal("100"), null, false, "UNKNOWN", false)))
        .isNull();
  }

  @Test
  void roundsEachOrderLineCommissionToTwoDecimals() {
    assertThat(
            CommissionSettlementRules.commission(
                new BigDecimal("12.345"), new BigDecimal("0.2000")))
        .isEqualByComparingTo("2.47");
    assertThat(CommissionSettlementRules.commission(new BigDecimal("12.34"), BigDecimal.ZERO))
        .isEqualByComparingTo("0.00");
  }

  @Test
  void explicitUnsettledAndAfterSalesNeverUseFallbackRelease() {
    SourceSnapshot unsettled =
        snapshot(new BigDecimal("10"), BigDecimal.ZERO, false, "UNSETTLED", false);
    SourceSnapshot afterSales =
        snapshot(new BigDecimal("10"), BigDecimal.ZERO, false, "UNKNOWN", true);

    assertThat(CommissionSettlementRules.releaseBasis(unsettled, true)).isEqualTo("UNSETTLED");
    assertThat(CommissionSettlementRules.releaseAt(unsettled, "UNSETTLED", 7)).isNull();
    assertThat(CommissionSettlementRules.releaseBasis(afterSales, true)).isEqualTo("AFTER_SALES");
    assertThat(CommissionSettlementRules.releaseAt(afterSales, "AFTER_SALES", 7)).isNull();
  }

  @Test
  void usesConfirmedSettlementTimeOrCompletionFallbackOnly() {
    SourceSnapshot confirmed =
        new SourceSnapshot(
            1,
            2,
            "item",
            "v1",
            COMPLETED,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            "WEIDIAN_COMMISSIONABLE_CNY",
            false,
            "CONFIRMED",
            COMPLETED.plusSeconds(60),
            COMPLETED,
            false,
            null);
    SourceSnapshot missingSettlementTime =
        new SourceSnapshot(
            1,
            2,
            "item",
            "v1",
            COMPLETED,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            "WEIDIAN_COMMISSIONABLE_CNY",
            false,
            "CONFIRMED",
            null,
            COMPLETED,
            false,
            null);
    SourceSnapshot fallback = snapshot(BigDecimal.ONE, BigDecimal.ZERO, false, "UNKNOWN", false);

    assertThat(CommissionSettlementRules.releaseAt(confirmed, "CONFIRMED", 7))
        .isEqualTo(COMPLETED.plusSeconds(60));
    assertThat(CommissionSettlementRules.releaseBasis(missingSettlementTime, true))
        .isEqualTo("UNSETTLED");
    assertThat(CommissionSettlementRules.releaseAt(fallback, "FALLBACK", 7))
        .isEqualTo(COMPLETED.plusSeconds(7 * 86400L));
    assertThat(CommissionSettlementRules.releaseBasis(fallback, false)).isEqualTo("UNVERIFIED");
  }

  private static SourceSnapshot snapshot(
      BigDecimal basis,
      BigDecimal refund,
      boolean includesRefund,
      String settlementState,
      boolean afterSales) {
    return new SourceSnapshot(
        1,
        2,
        "item",
        "v1",
        COMPLETED,
        basis,
        refund,
        "WEIDIAN_COMMISSIONABLE_CNY",
        includesRefund,
        settlementState,
        null,
        COMPLETED,
        afterSales,
        null);
  }
}
