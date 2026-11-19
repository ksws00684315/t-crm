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
import com.tcrm.admin.order.api.OrderEvidenceApi.SettlementFlowRow;
import com.tcrm.admin.order.api.OrderEvidenceApi.ShopCreatorCounts;
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
    if ((activity == Kind.DIRECT_ORDERS || activity == Kind.UNCONFIRMED_ORDERS)
        && board != Board.PRODUCT) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    var selection = select(resolved, board, entityId, productCode, keyword);
    return activity == null
        ? new Detail(
            resolved.period(),
            evidence.daily(selection),
            null,
            board == Board.SHOP ? shopOverview(resolved, selection) : null,
            board == Board.PRODUCT && overview.canView("order:view")
                ? evidence.refundedProductOrders(selection)
                : null)
        : new Detail(
            resolved.period(),
            null,
            evidence.page(selection, activity, ranking.page(), ranking.size()),
            null,
            null);
  }

  private ShopOverview shopOverview(
      StatsOverviewService.ResolvedFilter resolved, Selection selection) {
    var scope = selection.scope();
    var settlement =
        settlementAllowed(resolved)
            ? orders.shopSettlement(
                scope.tenantId(), List.of(scope.shopId()), selection.from(), selection.until())
            : null;
    return new ShopOverview(
        settlement == null ? null : settlement.net(), evidence.shopCreatorCounts(selection));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult<SettlementFlowRow> shopSettlementFlows(
      FilterDTO filters, Integer page, Integer size) {
    if (!overview.canView("order:view")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    var ranking = StatsRanking.resolve(page, size, null);
    var resolved = overview.resolve(filters);
    var scope = resolved.scope();
    if (!settlementAllowed(resolved)) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    validate(Board.SHOP, scope, null, null);
    if (shops.findShop(scope.shopId()) == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return evidence.shopSettlementFlows(
        scope.tenantId(),
        scope.shopId(),
        resolved.period().from(),
        resolved.period().until(),
        ranking.page(),
        ranking.size());
  }

  private boolean settlementAllowed(StatsOverviewService.ResolvedFilter resolved) {
    var scope = resolved.scope();
    return overview.canView("order:view")
        && resolved.dataScope() == DataScope.ALL
        && scope.ownerId() == null
        && scope.kolId() == null
        && scope.inviterId() == null
        && scope.matchedKolIds() == null
        && scope.matchedInviterIds() == null
        && scope.productKeyword() == null;
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
    if (group != ProductBreakdown.CREATORS || result.items().isEmpty()) {
      return result;
    }
    var directory =
        creators
            .listByIdsIncludingDeleted(
                result.items().stream().map(row -> Long.valueOf(row.code())).toList())
            .stream()
            .collect(Collectors.toMap(creator -> creator.id().toString(), Function.identity()));
    var ownerIds =
        directory.values().stream()
            .map(KolRefDTO::bdUserId)
            .filter(Objects::nonNull)
            .filter(
                id -> selection.scope().ownerId() == null || id.equals(selection.scope().ownerId()))
            .distinct()
            .toList();
    var owners =
        ownerIds.isEmpty()
            ? Map.<Long, String>of()
            : inviters.listByIds(ownerIds).stream()
                .collect(
                    Collectors.toMap(
                        BdUserDTO::id,
                        owner ->
                            owner.nickname() == null || owner.nickname().isBlank()
                                ? owner.username()
                                : owner.nickname()));
    return result.map(
        row -> {
          var creator = directory.get(row.code());
          if (creator == null) return row;
          String inviter = creator.bdUserId() == null ? null : owners.get(creator.bdUserId());
          return row.withCreator(creator.name(), creator.kolCode(), inviter);
        });
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
            visible,
            original.matchedKolIds(),
            original.matchedInviterIds(),
            board == Board.PRODUCT ? null : original.productKeyword());
    var period = resolved.period();
    return new Selection(
        scope,
        board == Board.CREATOR ? entityId : null,
        board == Board.INVITER ? entityId : null,
        board == Board.PRODUCT ? productCode : null,
        board == Board.PRODUCT && original.productKeyword() != null
            ? original.productKeyword()
            : keyword,
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
            visible,
            original.matchedKolIds(),
            original.matchedInviterIds(),
            original.productKeyword());
    var period = resolved.period();
    var facts = orders.rankFacts(scope, RankDimension.KOL, period.from(), period.until());
    var metricsById =
        facts.stream().collect(Collectors.toMap(RankFactsDTO::entityId, Function.identity()));
    var directory =
        currentTeam
            ? creators.listByBdUserId(inviterId).stream()
                .filter(k -> original.kolId() == null || Objects.equals(k.id(), original.kolId()))
                .filter(
                    k ->
                        original.matchedKolIds() == null
                            || original.matchedKolIds().contains(k.id()))
                .filter(k -> original.productKeyword() == null || metricsById.containsKey(k.id()))
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
              entityId == null
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

  public record ShopOverview(BigDecimal settlementAmount, ShopCreatorCounts creatorCounts) {}

  public record Detail(
      StatsPeriod period,
      List<DailyAmount> daily,
      PageResult<EvidenceRow> activity,
      ShopOverview shopOverview,
      Long refundedProductOrderCount) {}
}
