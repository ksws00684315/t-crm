package com.tcrm.admin.commission.service;

import com.tcrm.admin.shared.api.CommissionSettlementApi.SourceSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Pure accounting rules shared by the settlement writer and rule tests. */
final class CommissionSettlementRules {
  private CommissionSettlementRules() {}

  static BigDecimal effectiveBasis(SourceSnapshot snapshot) {
    if (snapshot.basisCny() == null
        || !"WEIDIAN_COMMISSIONABLE_CNY".equals(snapshot.basisSemantics())
        || snapshot.basisIncludesRefund() == null) {
      return null;
    }
    BigDecimal basis = snapshot.basisCny();
    if (!snapshot.basisIncludesRefund()) {
      if (snapshot.successfulRefundCny() == null) {
        return null;
      }
      basis = basis.subtract(snapshot.successfulRefundCny());
    }
    return basis.signum() < 0 ? null : basis;
  }

  static BigDecimal commission(BigDecimal basis, BigDecimal rate) {
    return basis.multiply(rate).setScale(2, RoundingMode.HALF_UP);
  }

  static String releaseBasis(SourceSnapshot snapshot, boolean verified) {
    if (!verified) {
      return "UNVERIFIED";
    }
    if (snapshot.afterSales()) {
      return "AFTER_SALES";
    }
    if ("UNSETTLED".equals(snapshot.settlementState())) {
      return "UNSETTLED";
    }
    if ("CONFIRMED".equals(snapshot.settlementState())) {
      return snapshot.settlementTime() == null ? "UNSETTLED" : "CONFIRMED";
    }
    return snapshot.completedTime() == null ? "UNSETTLED" : "FALLBACK";
  }

  static Instant releaseAt(SourceSnapshot snapshot, String basis, int days) {
    return switch (basis) {
      case "CONFIRMED" -> snapshot.settlementTime();
      case "FALLBACK" -> snapshot.completedTime().plus(days, ChronoUnit.DAYS);
      default -> null;
    };
  }
}
