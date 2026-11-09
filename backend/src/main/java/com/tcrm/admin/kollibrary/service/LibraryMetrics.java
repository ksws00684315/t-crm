package com.tcrm.admin.kollibrary.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Calculated values of content records and creators (requirement 1.2.6 ③, task 143 §3.1.5). One
 * implementation serves the API and the screen expectation; nothing is stored.
 *
 * <ul>
 *   <li>An empty amount or commission rate counts as 0.
 *   <li>Estimated commission = GMV × rate ÷ 100, two decimals, HALF_UP.
 *   <li>Total cost = sample cost + slot fee + estimated commission.
 *   <li>Actual ROI = GMV ÷ total cost, two decimals; null when the total cost is 0 (shown as "—").
 *   <li>Creator totals: count, sums of clicks, orders, GMV and total cost; the total ROI is the sum
 *       of GMV over the sum of total costs — not the average of the rows' ROIs — and null when the
 *       summed total cost is 0. The sums are added exactly, so the total ROI never differs from the
 *       quotient of the displayed totals because of rounded rows.
 * </ul>
 */
public final class LibraryMetrics {
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  public static final int SCALE = 2;

  private LibraryMetrics() {}

  /**
   * Values of one content record.
   *
   * @param gmv sales amount in CNY, may be null
   * @param sampleCost sample cost in CNY, may be null
   * @param slotFee slot fee in CNY, may be null
   * @param commissionRate commission rate in percent (0 to 100), may be null
   */
  public static ContentMetrics of(
      BigDecimal gmv, BigDecimal sampleCost, BigDecimal slotFee, BigDecimal commissionRate) {
    BigDecimal sales = zeroIfNull(gmv);
    BigDecimal commission =
        sales.multiply(zeroIfNull(commissionRate)).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    BigDecimal cost =
        zeroIfNull(sampleCost).add(zeroIfNull(slotFee)).add(commission).setScale(SCALE);
    BigDecimal roi = roi(sales, cost);
    return new ContentMetrics(
        sales, commission, cost, roi, zeroIfNull(sampleCost), zeroIfNull(slotFee));
  }

  /** Totals over the content records of one creator (clicks and orders count empty as 0 too). */
  public static Totals totals(List<ContentRecord> records) {
    long clicks = 0;
    long orders = 0;
    BigDecimal gmv = BigDecimal.ZERO.setScale(SCALE);
    BigDecimal cost = BigDecimal.ZERO.setScale(SCALE);
    for (ContentRecord record : records) {
      clicks += record.clicks() == null ? 0 : record.clicks();
      orders += record.orders() == null ? 0 : record.orders();
      ContentMetrics metrics =
          of(record.gmv(), record.sampleCost(), record.slotFee(), record.commissionRate());
      gmv = gmv.add(metrics.gmv());
      cost = cost.add(metrics.totalCost());
    }
    return new Totals(records.size(), clicks, orders, gmv, cost, roi(gmv, cost));
  }

  /** The manual input of one content record, as stored. */
  public record ContentRecord(
      Long clicks,
      Long orders,
      BigDecimal gmv,
      BigDecimal sampleCost,
      BigDecimal slotFee,
      BigDecimal commissionRate) {}

  /** GMV ÷ cost with two decimals; null when the cost is 0 so the screen shows a dash. */
  static BigDecimal roi(BigDecimal gmv, BigDecimal cost) {
    if (cost == null || cost.signum() == 0) {
      return null;
    }
    return gmv.divide(cost, SCALE, RoundingMode.HALF_UP);
  }

  static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(SCALE) : value;
  }

  /** Values of one content record; amounts in CNY with two decimals. */
  public record ContentMetrics(
      BigDecimal gmv,
      BigDecimal estimatedCommission,
      BigDecimal totalCost,
      BigDecimal actualRoi,
      BigDecimal sampleCost,
      BigDecimal slotFee) {}

  /** Sums over a creator's content records. */
  public record Totals(
      long contentCount,
      long clicks,
      long orders,
      BigDecimal gmv,
      BigDecimal totalCost,
      BigDecimal roi) {}
}
