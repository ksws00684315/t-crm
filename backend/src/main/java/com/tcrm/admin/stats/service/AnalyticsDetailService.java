package com.tcrm.admin.stats.service;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
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
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
  private final BdUserApi inviters;

  public AnalyticsDetailService(
      StatsOverviewService overview,
      OrderEvidenceApi evidence,
      ShopAccessApi shops,
      KolApi creators,
      OrderStatsApi orders,
      BdUserApi inviters) {
    this.overview = overview;
    this.evidence = evidence;
    this.shops = shops;
    this.creators = creators;
    this.orders = orders;
    this.inviters = inviters;
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
      FilterDTO filters, long inviterId, Integer page, Integer size, boolean currentTeam) {
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
    var scope =
        new ScopeDTO(
            original.tenantId(),
            original.ownerId(),
            original.shopId(),
            original.kolId(),
            inviterId,
            visible);
    var period = resolved.period();
    var facts = orders.rankFacts(scope, RankDimension.KOL, period.from(), period.until());
    var metricsById =
        facts.stream().collect(Collectors.toMap(RankFactsDTO::entityId, Function.identity()));
    var directory =
        currentTeam
            ? creators.listByBdUserId(inviterId).stream()
                .filter(k -> original.kolId() == null || Objects.equals(k.id(), original.kolId()))
                .toList()
            : creators.listByIdsIncludingDeleted(
                facts.stream().map(RankFactsDTO::entityId).filter(id -> id != 0L).toList());
    var people = directory.stream().collect(Collectors.toMap(KolRefDTO::id, Function.identity()));
    var ids =
        currentTeam
            ? directory.stream().map(KolRefDTO::id).toList()
            : facts.stream().map(RankFactsDTO::entityId).toList();
    var owners =
        original.ownerId() == null
            ? inviters
                .listByIds(
                    directory.stream()
                        .map(KolRefDTO::bdUserId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(BdUserDTO::id, Function.identity()))
            : Map.<Long, BdUserDTO>of();
    var rows =
        ids.stream()
            .map(
                id -> {
                  var creator = people.get(id);
                  var ownerId = creator == null ? null : creator.bdUserId();
                  boolean transferred = creator != null && !Objects.equals(ownerId, inviterId);
                  var owner = ownerId == null ? null : owners.get(ownerId);
                  return new CreatorRow(
                      id,
                      creator == null ? null : creator.name(),
                      creator == null ? null : creator.kolCode(),
                      creator == null || (transferred && original.ownerId() != null)
                          ? null
                          : creator.enabled(),
                      creatorMetrics(metricsById.get(id)),
                      transferred,
                      owner == null
                          ? null
                          : owner.nickname() == null || owner.nickname().isBlank()
                              ? owner.username()
                              : owner.nickname());
                })
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
      Long id,
      String name,
      String code,
      Boolean enabled,
      MetricsDTO metrics,
      boolean transferred,
      String currentInviter) {}

  public record Detail(
      StatsPeriod period, List<DailyAmount> daily, PageResult<EvidenceRow> activity) {}
}
