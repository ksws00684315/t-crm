package com.tcrm.admin.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read-only aggregate of order facts for the management dashboard. */
public interface OrderStatsApi {
  /** Shops in the business scope with any paid order, regardless of the selected dates. */
  long countShopsWithPaidOrders(ScopeDTO scope);

  /** Posted Weidian store receivables, in yuan, without fees or profit shares. */
  ShopSettlementDTO shopSettlement(long tenantId, List<Long> shopIds, Instant from, Instant until);

  record ShopSettlementDTO(BigDecimal increase, BigDecimal decrease) {
    public BigDecimal net() {
      return increase.subtract(decrease);
    }
  }

  List<WorkbenchRankDTO> workbenchRanks(ScopeDTO scope, RankDimension dimension, Instant asOf);

  record WorkbenchRankDTO(long entityId, long quantity, BigDecimal amount) {}

  SalesWindowDTO salesWindow(ScopeDTO scope, Instant dayStart, Instant asOf);

  CommissionWindowDTO commissionWindow(ScopeDTO scope, Instant dayStart, Instant asOf);

  record SalesWindowDTO(
      BigDecimal totalAmount, long totalOrders, BigDecimal todayAmount, long todayOrders) {}

  record CommissionWindowDTO(BigDecimal totalAmount, BigDecimal todayAmount) {}

  TotalsDTO totals(ScopeDTO scope, Instant from, Instant until);

  /** Null when a paid affiliate line has no reliable current commission snapshot. */
  BigDecimal unsettledCommission(ScopeDTO scope, Instant from, Instant until);

  List<RankFactsDTO> rankFacts(
      ScopeDTO scope, RankDimension dimension, Instant from, Instant until);

  List<ProductFactsDTO> productFacts(ScopeDTO scope, String keyword, Instant from, Instant until);

  long sellingCreators(ScopeDTO scope, Instant from, Instant until);

  /** Store participation by creators, independent of the selected reporting dates. */
  List<Long> paidCreatorIds(ScopeDTO scope);

  long creatorSpuCount(ScopeDTO scope, Instant from, Instant until);

  /** Read-only CRM commission snapshots for the same paid-order cohort. */
  CreatorCommissionDTO creatorCommission(ScopeDTO scope, Instant from, Instant until);

  List<InviterCommissionDTO> inviterCommissions(ScopeDTO scope, Instant from, Instant until);

  List<BestCreatorDTO> bestCreators(ScopeDTO scope, Instant from, Instant until);

  record InviterCommissionDTO(Long inviterId, BigDecimal estimated, BigDecimal actual) {}

  record BestCreatorDTO(Long inviterId, Long creatorId, BigDecimal netSalesAmount) {}

  record CreatorCommissionDTO(BigDecimal total, BigDecimal clawback) {}

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
      List<Long> matchedInviterIds,
      String productKeyword,
      boolean affiliateOnly) {
    public ScopeDTO withProductKeyword(String keyword) {
      return new ScopeDTO(
          tenantId,
          ownerId,
          shopId,
          kolId,
          inviterId,
          visibleShopIds,
          matchedKolIds,
          matchedInviterIds,
          keyword,
          affiliateOnly);
    }

    public ScopeDTO withVisibleShops(List<Long> shopIds) {
      return new ScopeDTO(
          tenantId,
          ownerId,
          shopId,
          kolId,
          inviterId,
          shopIds,
          matchedKolIds,
          matchedInviterIds,
          productKeyword,
          affiliateOnly);
    }

    public ScopeDTO(
        long tenantId,
        Long ownerId,
        Long shopId,
        Long kolId,
        Long inviterId,
        List<Long> visibleShopIds,
        List<Long> matchedKolIds,
        List<Long> matchedInviterIds) {
      this(
          tenantId,
          ownerId,
          shopId,
          kolId,
          inviterId,
          visibleShopIds,
          matchedKolIds,
          matchedInviterIds,
          null);
    }

    public ScopeDTO(
        long tenantId,
        Long ownerId,
        Long shopId,
        Long kolId,
        Long inviterId,
        List<Long> visibleShopIds,
        List<Long> matchedKolIds,
        List<Long> matchedInviterIds,
        String productKeyword) {
      this(
          tenantId,
          ownerId,
          shopId,
          kolId,
          inviterId,
          visibleShopIds,
          matchedKolIds,
          matchedInviterIds,
          productKeyword,
          false);
    }

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
      Instant lastRefundedAt,
      long linkCount) {}

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
      Instant lastPaidAt,
      Instant lastRefundedAt,
      Instant updatedAt,
      String spuId) {}

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
