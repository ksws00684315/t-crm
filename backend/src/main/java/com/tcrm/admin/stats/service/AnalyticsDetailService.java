package com.tcrm.admin.stats.service;

import com.tcrm.admin.commission.api.CreatorFundFlowApi;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderEvidenceApi;
import com.tcrm.admin.order.api.OrderEvidenceApi.DailyAmount;
import com.tcrm.admin.order.api.OrderEvidenceApi.DetailSort;
import com.tcrm.admin.order.api.OrderEvidenceApi.EvidenceRow;
import com.tcrm.admin.order.api.OrderEvidenceApi.Kind;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductBreakdown;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductGroup;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductOrderCounts;
import com.tcrm.admin.order.api.OrderEvidenceApi.Selection;
import com.tcrm.admin.order.api.OrderEvidenceApi.SettlementFlowRow;
import com.tcrm.admin.order.api.OrderEvidenceApi.ShopCreatorCounts;
import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.RankDimension;
import com.tcrm.admin.order.api.OrderStatsApi.RankFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.MetricsDTO;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
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
  private final CreatorFundFlowApi funds;
  private final CurrentUserContext current;

  public AnalyticsDetailService(
      StatsOverviewService overview,
      OrderEvidenceApi evidence,
      ShopAccessApi shops,
      KolApi creators,
      OrderStatsApi orders,
      BdUserApi inviters,
      CreatorFundFlowApi funds,
      CurrentUserContext current) {
    this.overview = overview;
    this.evidence = evidence;
    this.shops = shops;
    this.creators = creators;
    this.orders = orders;
    this.inviters = inviters;
    this.funds = funds;
    this.current = current;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Detail creatorIncome(FilterDTO filters, long creatorId) {
    var resolved = overview.resolve(filters);
    var selection = select(resolved, Board.CREATOR, creatorId, null, null);
    var scope = creatorIncomeScope(selection);
    var cutoff = StatsPeriod.resolve(null, null, true);
    var history = orders.totals(scope, cutoff.from(), cutoff.until());
    var period = orders.totals(scope, selection.from(), selection.until());
    var commission =
        overview.canViewCommission()
            ? orders.creatorCommission(scope, selection.from(), selection.until())
            : null;
    var canViewFunds =
        overview.canViewCommission()
            && (scope.ownerId() == null
                || creators.listByIds(List.of(creatorId)).stream()
                    .anyMatch(creator -> scope.ownerId().equals(creator.bdUserId())));
    var totals =
        canViewFunds
            ? funds.totals(
                current.user(),
                new CreatorFundFlowApi.Query(
                    creatorId, scope.shopId(), cutoff.from(), cutoff.until(), 1, 1))
            : null;
    var counts = overview.canView("order:view") ? evidence.productOrderCounts(selection) : null;
    return new Detail(
        resolved.period(),
        null,
        null,
        null,
        null,
        new CreatorOverview(null, commission == null ? null : commission.total(), counts),
        null,
        new IncomeOverview(
            StatsOverviewService.metrics(period),
            orders.dailyNetSales(scope, selection.from(), selection.until()),
            cutoff.endDate(),
            history.sales(),
            history.cohortRefunds(),
            history.sales().subtract(history.cohortRefunds()),
            totals == null ? null : totals.commission(),
            totals));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult<OrderStatsApi.CreatorIncomeOrder> creatorIncomeOrders(
      FilterDTO filters,
      long creatorId,
      Integer page,
      Integer size,
      DetailSort sortBy,
      String sortDirection) {
    if (!overview.canView("order:view") || !overview.canViewCommission()) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    if (sortBy == DetailSort.PAID_ORDER_COUNT || sortBy == DetailSort.UNITS) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    var ranking = StatsRanking.resolve(page, size, sortDirection);
    var selection = select(overview.resolve(filters), Board.CREATOR, creatorId, null, null);
    return orders.creatorIncomeOrders(
        creatorIncomeScope(selection),
        selection.from(),
        selection.until(),
        ranking.page(),
        ranking.size(),
        sortBy,
        !ranking.descending());
  }

  private static ScopeDTO creatorIncomeScope(Selection selection) {
    var scope = selection.scope();
    if (scope.kolId() != null && !scope.kolId().equals(selection.creator())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return new ScopeDTO(
        scope.tenantId(),
        scope.ownerId(),
        scope.shopId(),
        selection.creator(),
        scope.inviterId(),
        scope.visibleShopIds(),
        scope.matchedKolIds(),
        scope.matchedInviterIds(),
        scope.productKeyword(),
        scope.affiliateOnly());
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
    return detail(board, filters, entityId, productCode, keyword, activity, page, size, null, null);
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
      Integer size,
      DetailSort sortBy,
      String sortDirection) {
    if (activity != null && !overview.canView("order:view")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    var ranking = StatsRanking.resolve(page, size, sortDirection);
    if (sortBy == DetailSort.PAID_ORDER_COUNT
        || (activity == Kind.REFUNDS && sortBy == DetailSort.NET_SALES_AMOUNT)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    var resolved = overview.resolve(filters);
    var selection = select(resolved, board, entityId, productCode, keyword);
    return activity == null
        ? new Detail(
            resolved.period(),
            evidence.daily(selection),
            null,
            board == Board.SHOP ? shopOverview(resolved, selection) : null,
            board == Board.PRODUCT && overview.canView("order:view")
                ? evidence.productOrderCounts(selection)
                : null,
            board == Board.CREATOR ? creatorOverview(selection) : null,
            board == Board.INVITER ? inviterOverview(selection) : null,
            null)
        : new Detail(
            resolved.period(),
            null,
            sortBy != null
                ? evidence.sortedActivity(
                    selection.groupedByOrder(),
                    activity,
                    ranking.page(),
                    ranking.size(),
                    sortBy,
                    !ranking.descending())
                : evidence.page(
                    selection.groupedByOrder(), activity, ranking.page(), ranking.size()),
            null,
            null,
            null,
            null,
            null);
  }

  private CreatorOverview creatorOverview(Selection selection) {
    var scope = selection.scope();
    long creatorId = selection.creator();
    boolean canViewOrders = overview.canView("order:view");
    var counts = canViewOrders ? evidence.productOrderCounts(selection) : null;
    if (scope.visibleShopIds().isEmpty()
        || creatorId == 0L
        || (scope.kolId() != null && scope.kolId() != creatorId)) {
      return new CreatorOverview(null, null, counts);
    }
    var creatorScope =
        new ScopeDTO(
            scope.tenantId(),
            scope.ownerId(),
            scope.shopId(),
            creatorId,
            scope.inviterId(),
            scope.visibleShopIds(),
            scope.matchedKolIds(),
            scope.matchedInviterIds(),
            scope.productKeyword());
    var settlement = canViewOrders ? settlementFor(creatorScope, selection) : null;
    var commission =
        overview.canViewCommission()
            ? orders.creatorCommission(creatorScope, selection.from(), selection.until())
            : null;
    return new CreatorOverview(settlement, commission == null ? null : commission.total(), counts);
  }

  private InviterOverview inviterOverview(Selection selection) {
    if (!overview.canView("order:view")) {
      return new InviterOverview(null, null);
    }
    var scope = forInviter(selection.scope(), selection.inviter());
    var settlement = selection.inviter() == 0L ? null : settlementFor(scope, selection);
    return new InviterOverview(settlement, evidence.productOrderCounts(selection));
  }

  private BigDecimal settlementFor(ScopeDTO scope, Selection selection) {
    // Whole-order ledger postings cannot be assigned to selected product lines.
    return scope.visibleShopIds().isEmpty() || scope.productKeyword() != null
        ? null
        : orders.scopedSettlement(scope, selection.from(), selection.until());
  }

  private static ScopeDTO forInviter(ScopeDTO scope, long inviterId) {
    if ((scope.ownerId() != null && scope.ownerId() != inviterId)
        || (scope.inviterId() != null && scope.inviterId() != inviterId)) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return new ScopeDTO(
        scope.tenantId(),
        scope.ownerId(),
        scope.shopId(),
        scope.kolId(),
        inviterId,
        scope.visibleShopIds(),
        scope.matchedKolIds(),
        scope.matchedInviterIds(),
        scope.productKeyword());
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
    return productBreakdown(filters, productCode, keyword, group, page, size, null, null);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult<ProductGroup> productBreakdown(
      FilterDTO filters,
      String productCode,
      String keyword,
      ProductBreakdown group,
      Integer page,
      Integer size,
      DetailSort sortBy,
      String sortDirection) {
    var ranking = StatsRanking.resolve(page, size, sortDirection);
    var selection = select(overview.resolve(filters), Board.PRODUCT, null, productCode, keyword);
    var result =
        sortBy == null
            ? evidence.productGroups(selection, group, ranking.page(), ranking.size())
            : evidence.sortedProductGroups(
                selection, group, ranking.page(), ranking.size(), sortBy, !ranking.descending());
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
          if (creator == null) {
            return row;
          }
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
    return inviterCreators(filters, inviterId, page, size, currentTeam, null, null);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageResult<CreatorRow> inviterCreators(
      FilterDTO filters,
      long inviterId,
      Integer page,
      Integer size,
      boolean currentTeam,
      DetailSort sortBy,
      String sortDirection) {
    if (!overview.canView("kol:view")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    var ranking = StatsRanking.resolve(page, size, sortDirection);
    var resolved = overview.resolve(filters);
    var original = resolved.scope();
    var selection = select(resolved, Board.INVITER, inviterId, null, null);
    var scope = forInviter(selection.scope(), inviterId);
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
    boolean showCommission = !currentTeam && !ids.isEmpty() && overview.canViewCommission();
    var rateIds =
        showCommission
            ? directory.stream()
                .filter(k -> original.ownerId() == null || Objects.equals(k.bdUserId(), inviterId))
                .map(KolRefDTO::id)
                .toList()
            : List.<Long>of();
    var rates =
        rateIds.isEmpty()
            ? Map.<Long, BigDecimal>of()
            : creators.listRatesByIds(rateIds).stream()
                .filter(rate -> rate.rateText() != null)
                .collect(
                    Collectors.toMap(
                        rate -> rate.kolId(), rate -> new BigDecimal(rate.rateText())));
    var commissionTotals =
        showCommission
            ? orders.inviterCreatorCommissions(scope, period.from(), period.until()).stream()
                .collect(
                    Collectors.toMap(
                        OrderStatsApi.CreatorCommissionTotal::creatorId,
                        OrderStatsApi.CreatorCommissionTotal::total))
            : Map.<Long, BigDecimal>of();
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
                      rates.get(id),
                      commissionTotals.get(id),
                      transferred,
                      owner == null
                          ? null
                          : owner.nickname() == null || owner.nickname().isBlank()
                              ? owner.username()
                              : owner.nickname());
                })
            .toList();
    Comparator<CreatorRow> sort =
        switch (sortBy == null ? DetailSort.NET_SALES_AMOUNT : sortBy) {
          case PAID_ORDER_COUNT -> ranking.compare(row -> row.metrics().paidOrderCount());
          case UNITS -> ranking.compare(row -> row.metrics().units());
          case SALES_AMOUNT -> ranking.compare(row -> row.metrics().salesAmount());
          case REFUND_AMOUNT -> ranking.compare(row -> row.metrics().completedRefundAmount());
          case NET_SALES_AMOUNT -> ranking.compare(row -> row.metrics().netSalesAmount());
        };
    return ranking.slice(rows, sort.thenComparing(CreatorRow::id));
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
      BigDecimal commissionRate,
      BigDecimal totalEstimatedCommission,
      boolean transferred,
      String currentInviter) {}

  public record ShopOverview(BigDecimal settlementAmount, ShopCreatorCounts creatorCounts) {}

  public record CreatorOverview(
      BigDecimal settlementAmount, BigDecimal totalCommission, ProductOrderCounts orderCounts) {}

  public record InviterOverview(BigDecimal settlementAmount, ProductOrderCounts orderCounts) {}

  public record IncomeOverview(
      MetricsDTO metrics,
      List<OrderStatsApi.DailyNetSales> dailyNetSales,
      LocalDate through,
      BigDecimal cumulativeSales,
      BigDecimal cumulativeRefunds,
      BigDecimal cumulativeNetSales,
      BigDecimal totalCommission,
      CreatorFundFlowApi.Totals funds) {}

  public record Detail(
      StatsPeriod period,
      List<DailyAmount> daily,
      PageResult<EvidenceRow> activity,
      ShopOverview shopOverview,
      ProductOrderCounts productOrderCounts,
      CreatorOverview creatorOverview,
      InviterOverview inviterOverview,
      IncomeOverview incomeOverview) {}
}
