package com.tcrm.admin.stats.service;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.order.api.OrderEvidenceApi;
import com.tcrm.admin.order.api.OrderEvidenceApi.DailyAmount;
import com.tcrm.admin.order.api.OrderEvidenceApi.EvidenceRow;
import com.tcrm.admin.order.api.OrderEvidenceApi.Kind;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductBreakdown;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductGroup;
import com.tcrm.admin.order.api.OrderEvidenceApi.Selection;
import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.RankDimension;
import com.tcrm.admin.order.api.OrderStatsApi.RankFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.MetricsDTO;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Resolves drawer queries using the same account scope and business dates as the ranking. */
@Service
public class AnalyticsDetailService {
  public enum Board {
    SHOP,
    PRODUCT,
    CREATOR,
    INVITER
  }

  private final StatsOverviewService overview;
  private final OrderEvidenceApi evidence;
  private final ShopAccessApi shops;
  private final KolApi creators;
  private final OrderStatsApi orders;

  public AnalyticsDetailService(
      StatsOverviewService overview,
      OrderEvidenceApi evidence,
      ShopAccessApi shops,
      KolApi creators,
      OrderStatsApi orders) {
    this.overview = overview;
    this.evidence = evidence;
    this.shops = shops;
    this.creators = creators;
    this.orders = orders;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Detail detail(
      Board board,
      FilterDTO filters,
      Long entityId,
      String productCode,
      String keyword,
      Kind activity,
      Integer page,
      Integer size) {
    if (activity != null && !overview.canView("order:view")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    var ranking = StatsRanking.resolve(page, size, null);
    var resolved = overview.resolve(filters);
    var selection = select(resolved, board, entityId, productCode, keyword);
    return activity == null
        ? new Detail(resolved.period(), evidence.daily(selection), null)
        : new Detail(
            resolved.period(),
            null,
            evidence.page(selection, activity, ranking.page(), ranking.size()));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult<ProductGroup> productBreakdown(
      FilterDTO filters,
      String productCode,
      String keyword,
      ProductBreakdown group,
      Integer page,
      Integer size) {
    var ranking = StatsRanking.resolve(page, size, null);
    var selection = select(overview.resolve(filters), Board.PRODUCT, null, productCode, keyword);
    var result = evidence.productGroups(selection, group, ranking.page(), ranking.size());
    if (group == ProductBreakdown.SKUS || result.items().isEmpty()) {
      return result;
    }
    var names =
        creators
            .listByIdsIncludingDeleted(
                result.items().stream().map(row -> Long.valueOf(row.code())).toList())
            .stream()
            .collect(
                Collectors.toMap(creator -> creator.id().toString(), creator -> creator.name()));
    return result.map(
        row ->
            new ProductGroup(
                row.code(),
                names.get(row.code()),
                row.paidOrderCount(),
                row.units(),
                row.salesAmount(),
                row.completedRefundAmount(),
                row.netSalesAmount()));
  }

  private Selection select(
      StatsOverviewService.ResolvedFilter resolved,
      Board board,
      Long entityId,
      String productCode,
      String keyword) {
    var original = resolved.scope();
    var visible = shops.listShops().stream().map(ShopAccessDTO::id).toList();
    if (original.shopId() != null && !visible.contains(original.shopId())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    validate(board, original, entityId, productCode);
    var scope =
        new ScopeDTO(
            original.tenantId(),
            original.ownerId(),
            original.shopId(),
            original.kolId(),
            original.inviterId(),
            visible);
    var period = resolved.period();
    return new Selection(
        scope,
        board == Board.CREATOR ? entityId : null,
        board == Board.INVITER ? entityId : null,
        board == Board.PRODUCT ? productCode : null,
        keyword,
        period.from(),
        period.until());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult<CreatorRow> inviterCreators(
      FilterDTO filters, long inviterId, Integer page, Integer size) {
    if (!overview.canView("kol:view")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    var ranking = StatsRanking.resolve(page, size, null);
    var resolved = overview.resolve(filters);
    var original = resolved.scope();
    if ((original.ownerId() != null && original.ownerId() != inviterId)
        || (original.inviterId() != null && original.inviterId() != inviterId)) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    var visible = shops.listShops().stream().map(ShopAccessDTO::id).toList();
    if (original.shopId() != null && !visible.contains(original.shopId())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    var directory =
        creators.listByBdUserId(inviterId).stream()
            .filter(
                creator ->
                    original.kolId() == null || Objects.equals(creator.id(), original.kolId()))
            .toList();
    var scope =
        new ScopeDTO(
            original.tenantId(),
            original.ownerId(),
            original.shopId(),
            original.kolId(),
            inviterId,
            visible,
            directory.stream().map(creator -> creator.id()).toList(),
            null);
    var period = resolved.period();
    var facts =
        directory.isEmpty()
            ? java.util.Map.<Long, RankFactsDTO>of()
            : orders.rankFacts(scope, RankDimension.KOL, period.from(), period.until()).stream()
                .collect(Collectors.toMap(RankFactsDTO::entityId, Function.identity()));
    var rows =
        directory.stream()
            .map(
                creator ->
                    new CreatorRow(
                        creator.id(),
                        creator.name(),
                        creator.kolCode(),
                        creator.enabled(),
                        creatorMetrics(facts.get(creator.id()))))
            .toList();
    return ranking.slice(
        rows,
        ranking
            .compare((CreatorRow row) -> row.metrics().netSalesAmount())
            .thenComparing(CreatorRow::id, Comparator.naturalOrder()));
  }

  private static MetricsDTO creatorMetrics(RankFactsDTO fact) {
    var zero = BigDecimal.ZERO;
    var totals =
        fact == null
            ? new OrderStatsApi.TotalsDTO(0, 0, zero, zero, zero, 0, null)
            : new OrderStatsApi.TotalsDTO(
                fact.paidOrders(),
                fact.units(),
                fact.sales(),
                fact.cohortRefunds(),
                fact.completedRefunds(),
                fact.refundedOrders(),
                null);
    return StatsOverviewService.metrics(totals);
  }

  private static void validate(Board board, ScopeDTO scope, Long entityId, String productCode) {
    boolean valid =
        switch (board) {
          case SHOP -> scope.shopId() != null && entityId == null && productCode == null;
          case PRODUCT ->
              scope.shopId() != null
                  && entityId == null
                  && productCode != null
                  && !productCode.isBlank()
                  && scope.inviterId() == null;
          case CREATOR, INVITER -> entityId != null && entityId >= 0 && productCode == null;
        };
    if (!valid) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  public record CreatorRow(
      Long id, String name, String code, boolean enabled, MetricsDTO metrics) {}

  public record Detail(
      StatsPeriod period, List<DailyAmount> daily, PageResult<EvidenceRow> activity) {}
}
