package com.tcrm.admin.order.service;

import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.mapper.OrderDailyStatsMapper;
import com.tcrm.admin.order.mapper.OrderStatsMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/** Aggregates order and refund facts without exposing their persistence models. */
@Service
public class OrderStatsService implements OrderStatsApi {
  private final OrderStatsMapper mapper;
  private final OrderDailyStatsMapper daily;

  public OrderStatsService(OrderStatsMapper mapper, OrderDailyStatsMapper daily) {
    this.mapper = mapper;
    this.daily = daily;
  }

  @Override
  public TotalsDTO totals(ScopeDTO scope, Instant from, Instant until) {
    return mapper.totals(scope, from, until);
  }

  @Override
  public List<RankFactsDTO> rankFacts(
      ScopeDTO scope, RankDimension dimension, Instant from, Instant until) {
    return mapper.rankFacts(scope, dimension, from, until);
  }

  @Override
  public List<HighlightDTO> rankHighlights(
      ScopeDTO scope, RankDimension dimension, Instant from, Instant until) {
    return mapper.rankHighlights(scope, dimension, from, until);
  }

  @Override
  public long sellingCreators(ScopeDTO scope, Instant from, Instant until) {
    return mapper.sellingCreators(scope, from, until);
  }

  @Override
  public List<LocalDate> changedDays(long tenantId, Instant from, Instant until) {
    return daily.changedDays(tenantId, from, until);
  }

  @Override
  public List<ProductFactsDTO> productFacts(
      ScopeDTO scope, String keyword, Instant from, Instant until) {
    return daily.productFacts(scope, keyword, from, until, false);
  }

  @Override
  public List<ProductFactsDTO> productTrend(
      ScopeDTO scope, String keyword, Instant from, Instant until) {
    return daily.productFacts(scope, keyword, from, until, true);
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
