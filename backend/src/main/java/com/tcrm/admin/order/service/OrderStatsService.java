package com.tcrm.admin.order.service;

import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.mapper.OrderDailyStatsMapper;
import com.tcrm.admin.order.mapper.OrderSettlementFlowMapper;
import com.tcrm.admin.order.mapper.OrderStatsMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/** Aggregates order and refund facts without exposing their persistence models. */
@Service
public class OrderStatsService implements OrderStatsApi {
  private final OrderStatsMapper mapper;
  private final OrderDailyStatsMapper daily;

  private final OrderSettlementFlowMapper settlements;

  public OrderStatsService(
      OrderStatsMapper mapper, OrderDailyStatsMapper daily, OrderSettlementFlowMapper settlements) {
    this.mapper = mapper;
    this.daily = daily;
    this.settlements = settlements;
  }

  @Override
  public long countShopsWithPaidOrders(ScopeDTO scope) {
    return mapper.countShopsWithPaidOrders(scope);
  }

  @Override
  public ShopSettlementDTO shopSettlement(
      long tenantId, List<Long> shopIds, Instant from, Instant until) {
    return shopIds.isEmpty() ? null : settlements.shopReceivables(tenantId, shopIds, from, until);
  }

  @Override
  public List<WorkbenchRankDTO> workbenchRanks(
      ScopeDTO scope, RankDimension dimension, Instant from, Instant asOf) {
    return mapper.workbenchRanks(scope, dimension, from, asOf);
  }

  @Override
  public SalesWindowDTO salesWindow(ScopeDTO scope, Instant dayStart, Instant asOf) {
    return mapper.salesWindow(scope, dayStart, asOf);
  }

  @Override
  public CommissionWindowDTO commissionWindow(ScopeDTO scope, Instant dayStart, Instant asOf) {
    return mapper.commissionWindow(scope, dayStart, asOf);
  }

  @Override
  public TotalsDTO totals(ScopeDTO scope, Instant from, Instant until) {
    return mapper.totals(scope, from, until);
  }

  @Override
  public BigDecimal unsettledCommission(ScopeDTO scope, Instant from, Instant until) {
    return mapper.unsettledCommission(scope, from, until);
  }

  @Override
  public List<RankFactsDTO> rankFacts(
      ScopeDTO scope, RankDimension dimension, Instant from, Instant until) {
    return mapper.rankFacts(scope, dimension, from, until);
  }

  @Override
  public long sellingCreators(ScopeDTO scope, Instant from, Instant until) {
    return mapper.sellingCreators(scope, from, until);
  }

  @Override
  public List<Long> paidCreatorIds(ScopeDTO scope) {
    return mapper.paidCreatorIds(scope);
  }

  @Override
  public long creatorSpuCount(ScopeDTO scope, Instant from, Instant until) {
    return mapper.creatorSpuCount(scope, from, until);
  }

  @Override
  public BigDecimal scopedSettlement(ScopeDTO scope, Instant from, Instant until) {
    return mapper.scopedSettlement(scope, from, until);
  }

  @Override
  public CreatorCommissionDTO creatorCommission(ScopeDTO scope, Instant from, Instant until) {
    return mapper.creatorCommission(scope, from, until);
  }

  @Override
  public List<OrderStatsApi.CreatorRankingCommission> creatorRankingCommissions(
      ScopeDTO scope, Instant from, Instant until) {
    return mapper.creatorRankingCommissions(scope, from, until);
  }

  @Override
  public List<InviterCommissionDTO> inviterCommissions(
      ScopeDTO scope, Instant from, Instant until) {
    return mapper.inviterCommissions(scope, from, until);
  }

  @Override
  public List<CreatorCommissionTotal> inviterCreatorCommissions(
      ScopeDTO scope, Instant from, Instant until) {
    return mapper.inviterCreatorCommissions(scope, from, until);
  }

  @Override
  public List<BestCreatorDTO> bestCreators(ScopeDTO scope, Instant from, Instant until) {
    return mapper.bestCreators(scope, from, until);
  }

  @Override
  public List<LocalDate> changedDays(long tenantId, Instant from, Instant until) {
    return daily.changedDays(tenantId, from, until);
  }

  @Override
  public List<ProductFactsDTO> productFacts(
      ScopeDTO scope, String keyword, Instant from, Instant until) {
    return daily.productFacts(scope, keyword, from, until);
  }

  @Override
  public List<LocalDate> orderDays(long tenantId, long orderId) {
    return daily.orderDays(tenantId, orderId);
  }

  @Override
  public List<DailyFactDTO> rollup(long tenantId, Instant from, Instant until) {
    return daily.rollup(tenantId, from, until);
  }
}
