package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.model.CommissionItemStateDO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Pure commission settlement rules (task 62, design §8.3 / ADR-032 §2 and §5). No database access;
 * every branch is covered by {@code CommissionSettlementRulesTest}.
 *
 * <p>Unverified rows accrue nothing and never release (requirement 11.01): verification needs the
 * source commissionable amount (semantic {@code WEIDIAN_COMMISSIONABLE_CNY}, pending M0 /
 * Q-1017-1/2), a KOL attribution of type 1 (item id) or 2 (manual), and a pay-time rate.
 */
public final class CommissionSettlementRules {
  public static final String RELEASE_CONFIRMED = "CONFIRMED";
  public static final String RELEASE_FALLBACK = "FALLBACK";
  public static final String RELEASE_UNSETTLED = "UNSETTLED";
  public static final String RELEASE_AFTER_SALES = "AFTER_SALES";
  public static final String RELEASE_UNVERIFIED = "UNVERIFIED";

  /** Default Weidian freeze days when no rule version was effective at pay time (§4.8.2). */
  public static final int DEFAULT_FREEZE_DAYS = 7;

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

  /** Source facts of one order item line; the snapshot service resolves the rate before calling. */
  public record Inputs(
      BigDecimal basisCny,
      Boolean basisIncludesRefund,
      BigDecimal successfulRefundCny,
      Long kolId,
      Integer attributionType,
      BigDecimal rate,
      String settlementState,
      Instant settlementTime,
      Instant completedTime,
      boolean afterSales,
      Integer fallbackDays,
      BigDecimal releasedCny) {}

  /** Accrual and release decision derived from {@link Inputs}. */
  public record Result(
      boolean verified,
      BigDecimal effectiveBasisCny,
      BigDecimal accruedCny,
      String releaseBasis,
      Instant releaseAt) {
    public Result {
      accruedCny = accruedCny.setScale(2, RoundingMode.UNNECESSARY);
    }
  }

  private CommissionSettlementRules() {}

  /** True when the line is attributed to a KOL through item id (1) or manual assignment (2). */
  public static boolean attributedToKol(Long kolId, Integer attributionType) {
    return kolId != null
        && attributionType != null
        && (attributionType == 1 || attributionType == 2);
  }

  /**
   * Effective amount = source commissionable amount, minus confirmed successful refunds when the
   * source amount does not already exclude them (design §8.3 [D6]); never below zero (a full refund
   * zeroes the accrual instead of going negative).
   */
  public static BigDecimal effectiveBasis(
      BigDecimal basisCny, Boolean basisIncludesRefund, BigDecimal successfulRefundCny) {
    if (basisCny == null) {
      return null;
    }
    BigDecimal basis = basisCny;
    if (!Boolean.TRUE.equals(basisIncludesRefund) && successfulRefundCny != null) {
      basis = basis.subtract(successfulRefundCny);
    }
    return basis.max(ZERO).setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * 计佣金额 shown for a verified line (task 113, 定稿 §2.1, owner 2026-11-05): the effective basis,
   * except that a line of an order in after-sales (an unconcluded refund on any line) whose
   * commission was never released counts 0.00 until the refund concludes. A released line keeps its
   * amount (frozen, not clawed back) — the refund is deducted only once it succeeds.
   */
  public static BigDecimal commissionable(
      BigDecimal basisCny,
      Boolean basisIncludesRefund,
      BigDecimal successfulRefundCny,
      boolean afterSales,
      BigDecimal releasedCny) {
    BigDecimal effective = effectiveBasis(basisCny, basisIncludesRefund, successfulRefundCny);
    if (effective != null && afterSales && (releasedCny == null || releasedCny.signum() == 0)) {
      return ZERO;
    }
    return effective;
  }

  /** {@link #commissionable} of a stored snapshot: the one entry for every 计佣金额 display. */
  public static BigDecimal commissionable(CommissionItemStateDO row) {
    return commissionable(
        row.getBasisCny(),
        row.getBasisIncludesRefund(),
        row.getSuccessfulRefundCny(),
        Boolean.TRUE.equals(row.getAfterSales()),
        row.getReleasedCommissionCny());
  }

  /**
   * {@link #commissionable(CommissionItemStateDO)} as display text; null when the source has none.
   */
  public static String commissionableText(CommissionItemStateDO row) {
    BigDecimal value = commissionable(row);
    return value == null ? null : value.toPlainString();
  }

  /**
   * 退款调整 of a line (task 81, task 114): min(accrued − released, 0). Only a reduction after release
   * (refund or attribution change) is an adjustment; the frozen remainder (accrued minus released,
   * not yet released) is not. The one entry for the income detail and the settlement detail.
   */
  public static BigDecimal refundAdjustment(BigDecimal accruedCny, BigDecimal releasedCny) {
    BigDecimal released = releasedCny == null ? BigDecimal.ZERO : releasedCny;
    return accruedCny.subtract(released).min(ZERO);
  }

  /** Direction of a {@link #refundAdjustment}: DEBIT when negative, otherwise CREDIT (task 81). */
  public static String adjustmentDirection(BigDecimal adjustment) {
    return adjustment.signum() < 0 ? "DEBIT" : "CREDIT";
  }

  /** Accrued commission = round_half_up(effective amount × rate, 2); unverified lines accrue 0. */
  public static BigDecimal accrue(BigDecimal effectiveBasisCny, BigDecimal rate) {
    if (effectiveBasisCny == null || rate == null) {
      return ZERO;
    }
    return effectiveBasisCny.multiply(rate).setScale(2, RoundingMode.HALF_UP);
  }

  /** Evaluates verification, accrual and the release basis/time for one source snapshot. */
  public static Result evaluate(Inputs in) {
    boolean verified =
        in.basisCny() != null
            && attributedToKol(in.kolId(), in.attributionType())
            && in.rate() != null;
    if (!verified) {
      return new Result(
          false, effectiveBasis(in.basisCny(), null, null), ZERO, RELEASE_UNVERIFIED, null);
    }
    BigDecimal effective =
        effectiveBasis(in.basisCny(), in.basisIncludesRefund(), in.successfulRefundCny());
    BigDecimal accrued = accrue(effective, in.rate());
    if (in.afterSales()) {
      // Task 113 (定稿 §2.1, owner 2026-11-05): while the order is in after-sales the accrual is
      // min(accrual, released) — unreleased commission counts 0, released commission stays frozen
      // and is never clawed back here, so no ledger entry arises; a successful refund deducts
      // later.
      // The result keeps the real effective basis; the displayed 0.00 lives in commissionable.
      BigDecimal released = in.releasedCny() == null ? ZERO : in.releasedCny();
      return new Result(true, effective, accrued.min(released), RELEASE_AFTER_SALES, null);
    }
    // Owner 2026-11-03 (L-052): freeze days win over settlement confirmation when completedTime
    // is present; CONFIRMED only applies when there is no completedTime.
    if (in.completedTime() != null) {
      int days = in.fallbackDays() == null ? DEFAULT_FREEZE_DAYS : Math.max(0, in.fallbackDays());
      return new Result(
          true,
          effective,
          accrued,
          RELEASE_FALLBACK,
          in.completedTime().plusSeconds(days * 86400L));
    }
    if (in.settlementTime() != null) {
      return new Result(true, effective, accrued, RELEASE_CONFIRMED, in.settlementTime());
    }
    return new Result(true, effective, accrued, RELEASE_UNSETTLED, null);
  }
}
