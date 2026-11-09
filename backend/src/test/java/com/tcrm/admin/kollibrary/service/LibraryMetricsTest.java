package com.tcrm.admin.kollibrary.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Calculated fields of requirement 1.2.6 ③ (task 143 §3.1.5): commission = GMV × rate ÷ 100, total
 * cost = sample cost + slot fee + commission, ROI = GMV ÷ total cost, empty as 0, null ROI when the
 * cost is 0, and the totals use the sums rather than an average of the rows.
 */
class LibraryMetricsTest {
  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  @Test
  void shouldCountEmptyAmountsAndRateAsZero() {
    var metrics = LibraryMetrics.of(null, null, null, null);
    assertThat(metrics.gmv()).isEqualByComparingTo("0.00");
    assertThat(metrics.estimatedCommission()).isEqualByComparingTo("0.00");
    assertThat(metrics.totalCost()).isEqualByComparingTo("0.00");
    assertThat(metrics.actualRoi()).isNull();
  }

  @Test
  void shouldMultiplyGmvByRateWithTwoDecimals() {
    var metrics = LibraryMetrics.of(d("1000.00"), null, null, d("12.5"));
    assertThat(metrics.estimatedCommission()).isEqualByComparingTo("125.00");
    assertThat(metrics.totalCost()).isEqualByComparingTo("125.00");
    assertThat(metrics.actualRoi()).isEqualByComparingTo("8.00");
  }

  @Test
  void shouldRoundHalfUp() {
    // 333.33 × 7.77% = 25.8997... → 25.90
    assertThat(LibraryMetrics.of(d("333.33"), null, null, d("7.77")).estimatedCommission())
        .isEqualByComparingTo("25.90");
    // 0.05 × 10% = 0.005 → 0.01 (HALF_UP, not HALF_EVEN which would give 0.00)
    assertThat(LibraryMetrics.of(d("0.05"), null, null, d("10")).estimatedCommission())
        .isEqualByComparingTo("0.01");
  }

  @Test
  void shouldAddSampleCostSlotFeeAndCommissionIntoTotalCost() {
    var metrics = LibraryMetrics.of(d("500.00"), d("30.00"), d("50.00"), d("10"));
    assertThat(metrics.estimatedCommission()).isEqualByComparingTo("50.00");
    assertThat(metrics.totalCost()).isEqualByComparingTo("130.00");
    assertThat(metrics.actualRoi()).isEqualByComparingTo("3.85");
  }

  @Test
  void shouldLeaveRoiEmpty_whenTotalCostIsZero() {
    assertThat(LibraryMetrics.of(d("800.00"), null, null, null).actualRoi()).isNull();
    assertThat(LibraryMetrics.of(null, d("0.00"), d("0.00"), d("0")).actualRoi()).isNull();
  }

  @Test
  void shouldComputeTotalsFromSums_notFromTheAverageOfRowRois() {
    var rows =
        List.of(
            // ROI 100 / 10 = 10.00
            new LibraryMetrics.ContentRecord(100L, 5L, d("100.00"), d("10.00"), null, null),
            // ROI 100 / 100 = 1.00
            new LibraryMetrics.ContentRecord(300L, 2L, d("100.00"), d("100.00"), null, null));
    var totals = LibraryMetrics.totals(rows);
    assertThat(totals.contentCount()).isEqualTo(2);
    assertThat(totals.clicks()).isEqualTo(400);
    assertThat(totals.orders()).isEqualTo(7);
    assertThat(totals.gmv()).isEqualByComparingTo("200.00");
    assertThat(totals.totalCost()).isEqualByComparingTo("110.00");
    // 200 / 110 = 1.82, not the average of 10.00 and 1.00 (5.50).
    assertThat(totals.roi()).isEqualByComparingTo("1.82");
  }

  @Test
  void shouldCountEmptyClicksAndOrdersAsZeroInTotals() {
    var totals =
        LibraryMetrics.totals(
            List.of(
                new LibraryMetrics.ContentRecord(null, null, null, null, null, null),
                new LibraryMetrics.ContentRecord(3L, 1L, d("10.00"), d("0.00"), null, d("100"))));
    assertThat(totals.clicks()).isEqualTo(3);
    assertThat(totals.orders()).isEqualTo(1);
    assertThat(totals.gmv()).isEqualByComparingTo("10.00");
    assertThat(totals.totalCost()).isEqualByComparingTo("10.00");
    assertThat(totals.roi()).isEqualByComparingTo("1.00");
  }

  @Test
  void shouldLeaveTotalRoiEmpty_forCreatorWithoutRecords() {
    var totals = LibraryMetrics.totals(List.of());
    assertThat(totals.contentCount()).isZero();
    assertThat(totals.gmv()).isEqualByComparingTo("0.00");
    assertThat(totals.totalCost()).isEqualByComparingTo("0.00");
    assertThat(totals.roi()).isNull();
  }

  @Test
  void shouldNotLoseADigitWhenCostsAreSummed() {
    // Three rows whose halves round differently: the totals use exact sums.
    var rows =
        List.of(
            new LibraryMetrics.ContentRecord(null, null, d("0.01"), null, null, null),
            new LibraryMetrics.ContentRecord(null, null, d("0.01"), null, null, null),
            new LibraryMetrics.ContentRecord(null, null, d("0.01"), d("0.01"), null, null));
    var totals = LibraryMetrics.totals(rows);
    assertThat(totals.gmv()).isEqualByComparingTo("0.03");
    assertThat(totals.totalCost()).isEqualByComparingTo("0.01");
    assertThat(totals.roi()).isEqualByComparingTo("3.00");
  }
}
