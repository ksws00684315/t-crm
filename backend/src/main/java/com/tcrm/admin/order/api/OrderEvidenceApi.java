package com.tcrm.admin.order.api;

import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Transaction evidence for a single dashboard ranking row. */
public interface OrderEvidenceApi {
  enum Kind {
    ORDERS,
    REFUNDS,
    DIRECT_ORDERS,
    UNCONFIRMED_ORDERS
  }

  record Selection(
      ScopeDTO scope,
      Long creator,
      Long inviter,
      String productCode,
      String keyword,
      Instant from,
      Instant until) {}

  record EvidenceRow(
      Long id,
      String orderNo,
      String refundNo,
      String product,
      String sku,
      Integer units,
      BigDecimal amount,
      Instant eventTime,
      Long shopId,
      String creatorName,
      String spuId,
      String imageUrl,
      BigDecimal refundAmount,
      BigDecimal netTransactionAmount,
      String transactionItemId,
      String shopName,
      String skuId,
      Integer soldUnits,
      BigDecimal salesAmount) {}

  record ShopCreatorCounts(long selling, long total) {}

  ShopCreatorCounts shopCreatorCounts(Selection selection);

  record SettlementFlowRow(
      Long id,
      String orderNo,
      String product,
      String sku,
      String imageUrl,
      Integer itemCount,
      String amountType,
      BigDecimal amount,
      Instant eventTime,
      String spuId) {}

  PageResult<SettlementFlowRow> shopSettlementFlows(
      long tenantId, long shopId, Instant from, Instant until, int page, int size);

  enum ProductBreakdown {
    SKUS,
    CREATORS,
    SHOPS
  }

  record ProductGroup(
      String code,
      String name,
      long paidOrderCount,
      long units,
      BigDecimal salesAmount,
      BigDecimal completedRefundAmount,
      BigDecimal netSalesAmount,
      String creatorCode,
      String inviterName,
      String skuId,
      String imageUrl,
      Long netUnits) {
    public ProductGroup withCreator(String creatorName, String creatorCode, String inviterName) {
      return new ProductGroup(
          code,
          creatorName,
          paidOrderCount,
          units,
          salesAmount,
          completedRefundAmount,
          netSalesAmount,
          creatorCode,
          inviterName,
          skuId,
          imageUrl,
          netUnits);
    }
  }

  record DailyAmount(LocalDate day, BigDecimal sales, BigDecimal refunds) {}

  PageResult<EvidenceRow> page(Selection selection, Kind kind, int page, int size);

  List<DailyAmount> daily(Selection selection);

  long refundedProductOrders(Selection selection);

  PageResult<ProductGroup> productGroups(
      Selection selection, ProductBreakdown group, int page, int size);
}
