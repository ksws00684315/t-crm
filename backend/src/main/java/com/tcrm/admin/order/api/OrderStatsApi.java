package com.tcrm.admin.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read-only aggregate of order facts for the management dashboard. */
public interface OrderStatsApi {
  TotalsDTO totals(ScopeDTO scope, Instant from, Instant until);

  List<RankFactsDTO> rankFacts(
      ScopeDTO scope, RankDimension dimension, Instant from, Instant until);

  enum RankDimension {
    SHOP,
    KOL,
    INVITER
  }

  List<LocalDate> changedDays(long tenantId, Instant from, Instant until);

  List<LocalDate> orderDays(long tenantId, long orderId);

  List<DailyFactDTO> rollup(long tenantId, Instant from, Instant until);

  /**
   * Tenant and optional business owner are derived by the caller from the authenticated account.
   */
  record ScopeDTO(
      long tenantId,
      Long ownerId,
      Long shopId,
      Long kolId,
      Long inviterId,
      List<Long> visibleShopIds) {
    public ScopeDTO(long tenantId, Long ownerId, Long shopId, Long kolId, Long inviterId) {
      this(tenantId, ownerId, shopId, kolId, inviterId, null);
    }
  }

  record TotalsDTO(
      long paidOrders,
      long units,
      BigDecimal sales,
      BigDecimal cohortRefunds,
      BigDecimal completedRefunds,
      long refundedOrders,
      Instant updatedAt) {}

  /** One entity's facts, grouped before combining different fact grains; 0 denotes no owner. */
  record RankFactsDTO(
      Long entityId,
      long paidOrders,
      long units,
      long spuCount,
      long skuCount,
      long kolCount,
      BigDecimal sales,
      BigDecimal cohortRefunds,
      BigDecimal completedRefunds,
      Instant lastPaidAt,
      Instant lastRefundedAt) {}

  record DailyFactDTO(
      String grain,
      long shopId,
      long kolId,
      long ownerId,
      String productCode,
      String skuCode,
      long paidOrders,
      long units,
      BigDecimal sales,
      BigDecimal cohortRefunds,
      BigDecimal completedRefunds) {}
}
