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

  List<ProductFactsDTO> productFacts(ScopeDTO scope, String keyword, Instant from, Instant until);

  List<ProductFactsDTO> productTrend(ScopeDTO scope, String keyword, Instant from, Instant until);

  List<HighlightDTO> rankHighlights(
      ScopeDTO scope, RankDimension dimension, Instant from, Instant until);

  long sellingCreators(ScopeDTO scope, Instant from, Instant until);

  record HighlightDTO(Long entityId, String name, Long creatorId) {}

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
      List<Long> visibleShopIds,
      List<Long> matchedKolIds,
      List<Long> matchedInviterIds) {
    public ScopeDTO(
        long tenantId,
        Long ownerId,
        Long shopId,
        Long kolId,
        Long inviterId,
        List<Long> visibleShopIds) {
      this(tenantId, ownerId, shopId, kolId, inviterId, visibleShopIds, null, null);
    }

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
      long refundedOrders,
      Instant lastPaidAt,
      Instant lastRefundedAt) {}

  /** A shop/SPU row, or the exact cross-product total when shopId is null. */
  record ProductFactsDTO(
      Long shopId,
      String spu,
      String name,
      Long productId,
      long paidOrders,
      long units,
      long kolCount,
      BigDecimal sales,
      BigDecimal cohortRefunds,
      BigDecimal completedRefunds,
      long refundedOrders,
      long cohortRefundedOrders,
      LocalDate day,
      Instant lastPaidAt,
      Instant lastRefundedAt,
      Instant updatedAt) {}

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
