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
    REFUNDS
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
      Instant eventTime) {}

  enum ProductBreakdown {
    SKUS,
    CREATORS
  }

  record ProductGroup(
      String code,
      String name,
      long paidOrderCount,
      long units,
      BigDecimal salesAmount,
      BigDecimal completedRefundAmount,
      BigDecimal netSalesAmount) {}

  record DailyAmount(LocalDate day, BigDecimal sales, BigDecimal refunds) {}

  PageResult<EvidenceRow> page(Selection selection, Kind kind, int page, int size);

  List<DailyAmount> daily(Selection selection);

  PageResult<ProductGroup> productGroups(
      Selection selection, ProductBreakdown group, int page, int size);
}
