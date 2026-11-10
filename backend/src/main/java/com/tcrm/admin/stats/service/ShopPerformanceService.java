package com.tcrm.admin.stats.service;

import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.RankDimension;
import com.tcrm.admin.order.api.OrderStatsApi.RankFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.stats.service.StatsOverviewService.ChangesDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.MetaDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.MetricsDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.ResolvedFilter;
import com.tcrm.admin.stats.service.StatsOverviewService.UnavailableDTO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reference ranking using the same scoped facts and filters as the management overview. */
@Service
public class ShopPerformanceService {
  private final StatsOverviewService overview;
  private final OrderStatsApi orders;
  private final ShopAccessApi shops;

  public ShopPerformanceService(
      StatsOverviewService overview, OrderStatsApi orders, ShopAccessApi shops) {
    this.overview = overview;
    this.orders = orders;
    this.shops = shops;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ShopPerformanceDTO query(
      FilterDTO filter, Integer page, Integer size, String sortBy, String direction) {
    var ranking = StatsRanking.resolve(page, size, direction);
    var column = SortColumn.resolve(sortBy);
    var resolved = overview.resolve(filter);
    var period = resolved.period();
    var filters = resolved.scope();
    var visibleShops =
        shops.listShops().stream()
            .filter(shop -> filters.shopId() == null || Objects.equals(shop.id(), filters.shopId()))
            .toList();
    var scope =
        new ScopeDTO(
            filters.tenantId(),
            filters.ownerId(),
            filters.shopId(),
            filters.kolId(),
            filters.inviterId(),
            visibleShops.stream().map(ShopAccessDTO::id).toList());
    var facts = new HashMap<Long, RankFactsDTO>();
    for (var row : orders.rankFacts(scope, RankDimension.SHOP, period.from(), period.until())) {
      facts.put(row.entityId(), row);
    }
    boolean includeShopsWithoutFacts =
        resolved.dataScope() == DataScope.ALL && scope.kolId() == null && scope.inviterId() == null;
    var rows =
        visibleShops.stream()
            .filter(shop -> includeShopsWithoutFacts || facts.containsKey(shop.id()))
            .map(shop -> row(shop, facts.get(shop.id())))
            .toList();
    var order =
        ranking
            .compare(column.value)
            .thenComparing(column.achieved, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ShopRankDTO::shopId);
    var paged = ranking.slice(rows, order);
    var totals =
        overview.overview(
            new ResolvedFilter(scope, period, resolved.filters(), resolved.dataScope()));
    long activeCount = rows.stream().filter(row -> row.paidOrderCount() > 0).count();
    return new ShopPerformanceDTO(
        totals.meta(),
        new ShopSummaryDTO(totals.summary(), activeCount, rows.size()),
        totals.previous(),
        totals.change(),
        paged,
        totals.unavailable(),
        column.name,
        ranking.descending() ? "desc" : "asc");
  }

  private static ShopRankDTO row(ShopAccessDTO shop, RankFactsDTO facts) {
    if (facts == null) {
      return new ShopRankDTO(
          shop.id(),
          shop.name(),
          shop.shopCode(),
          shop.status(),
          0,
          0,
          0,
          0,
          0,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          null,
          null,
          null);
    }
    BigDecimal average =
        facts.paidOrders() == 0
            ? null
            : facts.sales().divide(BigDecimal.valueOf(facts.paidOrders()), 2, RoundingMode.HALF_UP);
    return new ShopRankDTO(
        shop.id(),
        shop.name(),
        shop.shopCode(),
        shop.status(),
        facts.paidOrders(),
        facts.units(),
        facts.spuCount(),
        facts.skuCount(),
        facts.kolCount(),
        facts.sales(),
        facts.completedRefunds(),
        facts.sales().subtract(facts.cohortRefunds()),
        average,
        facts.lastPaidAt(),
        facts.lastRefundedAt());
  }

  public record ShopPerformanceDTO(
      MetaDTO meta,
      ShopSummaryDTO summary,
      MetricsDTO previous,
      ChangesDTO change,
      PageResult<ShopRankDTO> ranking,
      List<UnavailableDTO> unavailable,
      String sortBy,
      String sortDirection) {}

  public record ShopSummaryDTO(MetricsDTO metrics, long sellingShopCount, long shopCount) {}

  public record ShopRankDTO(
      Long shopId,
      String shopName,
      String shopCode,
      Integer status,
      long paidOrderCount,
      long units,
      long spuCount,
      long skuCount,
      long kolCount,
      BigDecimal salesAmount,
      BigDecimal completedRefundAmount,
      BigDecimal netSalesAmount,
      BigDecimal averageOrderAmount,
      @com.fasterxml.jackson.annotation.JsonIgnore Instant lastPaidAt,
      @com.fasterxml.jackson.annotation.JsonIgnore Instant lastRefundedAt) {}

  enum SortColumn {
    PAID_ORDERS("paidOrderCount", row -> BigDecimal.valueOf(row.paidOrderCount())),
    UNITS("units", row -> BigDecimal.valueOf(row.units())),
    SPUS("spuCount", row -> BigDecimal.valueOf(row.spuCount())),
    SKUS("skuCount", row -> BigDecimal.valueOf(row.skuCount())),
    KOLS("kolCount", row -> BigDecimal.valueOf(row.kolCount())),
    SALES("salesAmount", ShopRankDTO::salesAmount),
    REFUNDS(
        "completedRefundAmount", ShopRankDTO::completedRefundAmount, ShopRankDTO::lastRefundedAt),
    NET("netSalesAmount", ShopRankDTO::netSalesAmount),
    AVERAGE("averageOrderAmount", ShopRankDTO::averageOrderAmount);

    private final String name;
    private final Function<ShopRankDTO, BigDecimal> value;
    private final Function<ShopRankDTO, Instant> achieved;

    SortColumn(String name, Function<ShopRankDTO, BigDecimal> value) {
      this(name, value, ShopRankDTO::lastPaidAt);
    }

    SortColumn(
        String name,
        Function<ShopRankDTO, BigDecimal> value,
        Function<ShopRankDTO, Instant> achieved) {
      this.name = name;
      this.value = value;
      this.achieved = achieved;
    }

    static SortColumn resolve(String name) {
      if (name == null) {
        return NET;
      }
      for (var column : values()) {
        if (column.name.equals(name)) {
          return column;
        }
      }
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }
}
