package com.tcrm.admin.shared.api;

import java.math.BigDecimal;
import java.time.Instant;

/** Shared write port used by order and KOL modules to maintain commission state. */
public interface CommissionSettlementApi {
  record SourceSnapshot(
      long tenantId,
      long orderItemId,
      String sourceKey,
      String sourceVersion,
      Instant sourceUpdatedAt,
      BigDecimal basisCny,
      BigDecimal successfulRefundCny,
      String basisSemantics,
      Boolean basisIncludesRefund,
      String settlementState,
      Instant settlementTime,
      Instant completedTime,
      boolean afterSales,
      String evidence) {}

  void ensureOrderItems(long tenantId, long kolId);

  void applySourceSnapshot(SourceSnapshot snapshot);

  void refreshRefundState(long tenantId, long orderItemId);

  long recordRateVersion(
      long tenantId, long kolId, BigDecimal rate, Instant effectiveAt, long actorId, String note);
}
