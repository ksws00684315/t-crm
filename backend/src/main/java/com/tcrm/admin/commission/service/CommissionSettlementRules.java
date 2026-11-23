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

  /** Successful refunds reduce the basis; an open refund only blocks use of the commission. */
  public static BigDecimal commissionable(
      BigDecimal basisCny,
      Boolean basisIncludesRefund,
      BigDecimal successfulRefundCny,
      boolean afterSales,
      BigDecimal releasedCny) {
    return effectiveBasis(basisCny, basisIncludesRefund, successfulRefundCny);
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

  /** Signed reduction of the verified commission due to successful refunds. */
  public static BigDecimal refundAdjustment(CommissionItemStateDO row, BigDecimal payTimeRate) {
    if (!Boolean.TRUE.equals(row.getVerified())) {
      return null;
    }
    var refund = row.getSuccessfulRefundCny();
    if (refund == null || refund.signum() == 0) {
      return ZERO;
    }
    var basis = row.getBasisCny();
    var net = row.getAccruedCommissionCny();
    if (!Boolean.FALSE.equals(row.getBasisIncludesRefund())
        || basis == null
        || payTimeRate == null
        || net == null
        || accrue(effectiveBasis(basis, false, refund), payTimeRate).compareTo(net) != 0) {
      return null;
    }
    return net.subtract(accrue(basis.max(ZERO), payTimeRate));
  }

  /** Direction of a verified refund adjustment. */
  public static String adjustmentDirection(BigDecimal adjustment) {
    return adjustment == null ? null : adjustment.signum() < 0 ? "DEBIT" : "CREDIT";
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
      return new Result(true, effective, accrued, RELEASE_AFTER_SALES, null);
    }
    if ("CONFIRMED".equals(in.settlementState()) && in.settlementTime() != null) {
      return new Result(true, effective, accrued, RELEASE_CONFIRMED, in.settlementTime());
    }
    return new Result(true, effective, accrued, RELEASE_UNSETTLED, null);
  }
}
