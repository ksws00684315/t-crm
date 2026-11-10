package com.tcrm.admin.stats.service;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolOwnershipApi;
import com.tcrm.admin.kol.api.KolOwnershipDTO;
import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.ProductFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.RankDimension;
import com.tcrm.admin.order.api.OrderStatsApi.RankFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.order.api.OrderStatsApi.TotalsDTO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ProductAccessApi.ProductRefDTO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.stats.service.StatsOverviewService.ChangesDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.MetaDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.MetricsDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.UnavailableDTO;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsRankingService {
  private static final String NET = "netSalesAmount";
  private final StatsOverviewService overview;
  private final OrderStatsApi orders;
  private final ShopAccessApi shops;
  private final ProductAccessApi catalog;
  private final KolApi creators;
  private final BdUserApi inviters;
  private final KolOwnershipApi ownership;

  public AnalyticsRankingService(
      StatsOverviewService overview,
      OrderStatsApi orders,
      ShopAccessApi shops,
      ProductAccessApi catalog,
      KolApi creators,
      BdUserApi inviters,
      KolOwnershipApi ownership) {
    this.overview = overview;
    this.orders = orders;
    this.shops = shops;
    this.catalog = catalog;
    this.creators = creators;
    this.inviters = inviters;
    this.ownership = ownership;
  }

  public enum Board {
    PRODUCT,
    CREATOR,
    INVITER
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Result query(
      Board board,
      FilterDTO filters,
      String keyword,
      Integer page,
      Integer size,
      String sortBy,
      String direction) {
    var sorting = StatsRanking.resolve(page, size, direction);
    String key = sortBy == null ? NET : sortBy;
    var comparator = comparator(board, sorting, key);
    var scope = overview.resolve(filters);
    var data = board == Board.PRODUCT ? products(scope, keyword) : people(board, scope, keyword);
    return new Result(
        data.meta(),
        data.summary(),
        data.previous(),
        data.change(),
        sorting.slice(data.rows(), comparator),
        data.unavailable(),
        key,
        sorting.descending() ? "desc" : "asc",
        keyword);
  }

  @Transactional(readOnly = true)
  public Options options() {
    var scope = overview.resolve(new FilterDTO(null, null, null, null, null, null)).scope();
    var kol =
        scope.ownerId() == null ? creators.listAll() : creators.listByBdUserId(scope.ownerId());
    var bd =
        scope.ownerId() == null
            ? inviters.listUsable()
            : inviters.listByIds(List.of(scope.ownerId()));
    return new Options(
        shops.listShops().stream().map(s -> new Option(s.id(), s.name(), s.shopCode())).toList(),
        kol.stream().map(k -> new Option(k.id(), k.name(), k.kolCode())).toList(),
        bd.stream().map(b -> new Option(b.id(), display(b), null)).toList());
  }

  private Data products(StatsOverviewService.ResolvedFilter filter, String keyword) {
    var period = filter.period();
    var prior = period.previous();
    var shopNames =
        shops.listShops().stream().collect(Collectors.toMap(s -> s.id(), s -> s.name()));
    var original = filter.scope();
    var scope =
        new ScopeDTO(
            original.tenantId(),
            original.ownerId(),
            original.shopId(),
            original.kolId(),
            null,
            List.copyOf(shopNames.keySet()));
    var current = orders.productFacts(scope, keyword, period.from(), period.until());
    var previous = orders.productFacts(scope, keyword, prior.from(), prior.until());
    var totals = current.stream().filter(p -> p.shopId() == null).findFirst().orElseThrow();
    var previousTotals =
        previous.stream().filter(p -> p.shopId() == null).findFirst().orElseThrow();
    var products =
        catalog
            .listProducts(
                current.stream()
                    .map(ProductFactsDTO::productId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(ProductRefDTO::id, Function.identity()));
    var now = productRows(current, shopNames, products);
    var before = productRows(previous, shopNames, Map.of());
    var ranks = new HashMap<String, Integer>();
    for (int i = 0; i < before.size(); i++) {
      ranks.put(before.get(i).id(), i + 1);
    }
    var trendStart = period.endDate().minusDays(6);
    if (trendStart.isBefore(period.startDate())) {
      trendStart = period.startDate();
    }
    var days = trendStart.datesUntil(period.endDate().plusDays(1)).toList();
    var trend =
        orders
            .productTrend(
                scope,
                keyword,
                trendStart.atStartOfDay(StatsPeriod.ZONE).toInstant(),
                period.until())
            .stream()
            .collect(
                Collectors.groupingBy(
                    p -> p.shopId() + ":" + p.spu(),
                    Collectors.toMap(
                        ProductFactsDTO::day, p -> p.sales().subtract(p.cohortRefunds()))));
    var rows = new ArrayList<Row>();
    for (int i = 0; i < now.size(); i++) {
      var row = now.get(i);
      int rank = i + 1;
      Integer previousRank = ranks.get(row.id());
      var values = trend.getOrDefault(row.id(), Map.of());
      var series =
          days.stream()
              .map(day -> new TrendPoint(day, values.getOrDefault(day, BigDecimal.ZERO)))
              .toList();
      rows.add(row.withRank(rank, previousRank == null ? null : previousRank - rank, series));
    }
    var metrics = StatsOverviewService.metrics(productTotals(totals));
    var priorMetrics = StatsOverviewService.metrics(productTotals(previousTotals));
    var meta =
        new MetaDTO(
            period,
            prior,
            StatsPeriod.ZONE.getId(),
            filter.filters(),
            filter.dataScope(),
            totals.updatedAt());
    return new Data(
        meta,
        new Summary(metrics, selling(now), null, null, null, null),
        new Summary(priorMetrics, selling(before), null, null, null, null),
        StatsOverviewService.changes(metrics, priorMetrics),
        rows,
        List.of(
            new UnavailableDTO("refundUnits", "REFUND_QUANTITY_PENDING"),
            new UnavailableDTO("settlementAmount", "WEIDIAN_SETTLEMENT_PENDING"),
            new UnavailableDTO("netUnits", "REFUND_QUANTITY_PENDING")));
  }

  private static List<Row> productRows(
      List<ProductFactsDTO> facts, Map<Long, String> shops, Map<Long, ProductRefDTO> products) {
    return facts.stream()
        .filter(p -> p.shopId() != null)
        .map(
            p -> {
              var product = p.productId() == null ? null : products.get(p.productId());
              return new Row(
                  p.shopId() + ":" + p.spu(),
                  p.name(),
                  p.spu(),
                  shops.get(p.shopId()),
                  null,
                  null,
                  p.productId(),
                  p.shopId(),
                  null,
                  null,
                  null,
                  null,
                  p.kolCount(),
                  null,
                  p.lastPaidAt(),
                  p.lastRefundedAt(),
                  StatsOverviewService.metrics(productTotals(p)),
                  product == null ? null : product.imageUrl(),
                  product == null ? null : product.category(),
                  List.of(),
                  List.of(),
                  product == null ? null : product.sourceItemId(),
                  null,
                  null,
                  null,
                  null);
            })
        .sorted(comparator(Board.PRODUCT, new StatsRanking(1, 20, true), NET))
        .toList();
  }

  private static TotalsDTO productTotals(ProductFactsDTO fact) {
    return new TotalsDTO(
        fact.paidOrders(),
        fact.units(),
        fact.sales(),
        fact.cohortRefunds(),
        fact.completedRefunds(),
        fact.cohortRefundedOrders(),
        fact.updatedAt());
  }

  private Data people(Board board, StatsOverviewService.ResolvedFilter filter, String keyword) {
    var scope = filter.scope();
    var period = filter.period();
    var prior = period.previous();
    var dimension = board == Board.CREATOR ? RankDimension.KOL : RankDimension.INVITER;
    var now = orders.rankFacts(scope, dimension, period.from(), period.until());
    var before = orders.rankFacts(scope, dimension, prior.from(), prior.until());
    var ids = new java.util.HashSet<Long>();
    now.forEach(r -> ids.add(r.entityId()));
    before.forEach(r -> ids.add(r.entityId()));
    var directory =
        scope.ownerId() == null ? creators.listAll() : creators.listByBdUserId(scope.ownerId());
    var kolIds = board == Board.CREATOR ? ids : List.<Long>of();
    var kols =
        creators.listByIdsIncludingDeleted(kolIds).stream()
            .collect(Collectors.toMap(k -> k.id(), Function.identity()));
    directory.forEach(k -> kols.put(k.id(), k));
    var invited = board == Board.INVITER ? invitedCounts() : Map.<Long, Long>of();
    var ownerIds = new java.util.HashSet<Long>();
    kols.values().stream().map(k -> k.bdUserId()).filter(Objects::nonNull).forEach(ownerIds::add);
    if (board == Board.INVITER) {
      ownerIds.addAll(ids);
      if (scope.ownerId() != null) {
        ownerIds.add(scope.ownerId());
      } else {
        inviters.listUsable().forEach(b -> ownerIds.add(b.id()));
        ownerIds.addAll(invited.keySet());
      }
    }
    ownerIds.remove(0L);
    var bd =
        inviters.listByIds(ownerIds).stream()
            .collect(Collectors.toMap(BdUserDTO::id, Function.identity()));
    var profiles =
        board == Board.CREATOR
            ? creators.analyticsProfiles(kols.keySet(), bd)
            : Map.<Long, KolApi.AnalyticsProfileDTO>of();
    var rates = new HashMap<Long, BigDecimal>();
    boolean commissionVisible = board == Board.CREATOR && overview.canViewCommission();
    if (commissionVisible) {
      var allowed = directory.stream().map(k -> k.id()).toList();
      for (var rate : creators.listRatesByIds(allowed)) {
        if (rate.rateText() != null) {
          rates.put(rate.kolId(), new BigDecimal(rate.rateText()));
        }
      }
    }
    var effective = new HashMap<Long, Long>();
    directory.stream()
        .filter(k -> k.enabled() && k.bdUserId() != null)
        .forEach(k -> effective.merge(k.bdUserId(), 1L, Long::sum));
    var identities = new HashMap<Long, Identity>();
    if (board == Board.CREATOR) {
      kols.forEach(
          (id, k) -> {
            var profile = profiles.get(id);
            identities.put(
                id,
                new Identity(
                    k.name(),
                    k.kolCode(),
                    bd.containsKey(k.bdUserId()) ? display(bd.get(k.bdUserId())) : null,
                    k.enabled(),
                    profile == null ? null : profile.handles(),
                    null,
                    null,
                    profile == null ? null : profile.primaryPlatform(),
                    rates.get(id),
                    profile == null ? null : profile.relationStatus()));
          });
    } else {
      bd.forEach(
          (id, b) ->
              identities.put(
                  id,
                  new Identity(
                      display(b),
                      b.username(),
                      null,
                      b.enabled(),
                      null,
                      invited.getOrDefault(id, 0L),
                      effective.getOrDefault(id, 0L),
                      null,
                      null,
                      null)));
    }
    var matched =
        identities.entrySet().stream()
            .filter(
                e ->
                    board == Board.CREATOR
                        ? (scope.kolId() == null || scope.kolId().equals(e.getKey()))
                            && (scope.inviterId() == null
                                || (kols.containsKey(e.getKey())
                                    && scope.inviterId().equals(kols.get(e.getKey()).bdUserId()))
                                || ids.contains(e.getKey()))
                        : scope.inviterId() == null || scope.inviterId().equals(e.getKey()))
            .filter(e -> matches(e.getValue(), keyword))
            .map(Map.Entry::getKey)
            .toList();
    boolean search = keyword != null && !keyword.isBlank();
    var scoped =
        search
            ? new ScopeDTO(
                scope.tenantId(),
                scope.ownerId(),
                scope.shopId(),
                scope.kolId(),
                scope.inviterId(),
                scope.visibleShopIds(),
                board == Board.CREATOR ? matched : null,
                board == Board.INVITER ? matched : null)
            : scope;
    var total =
        overview.overview(
            new StatsOverviewService.ResolvedFilter(
                scoped, period, filter.filters(), filter.dataScope()));
    var facts = orders.rankHighlights(scoped, dimension, period.from(), period.until());
    var highlightedCreators =
        creators
            .listByIdsIncludingDeleted(
                facts.stream()
                    .map(OrderStatsApi.HighlightDTO::creatorId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(k -> k.id(), k -> k.name()));
    var highlights = new HashMap<Long, List<String>>();
    for (var fact : facts) {
      String name =
          fact.creatorId() == null ? fact.name() : highlightedCreators.get(fact.creatorId());
      if (name != null) {
        highlights.computeIfAbsent(fact.entityId(), id -> new ArrayList<>()).add(name);
      }
    }
    var rows =
        peopleRows(
            board == Board.INVITER ? withInactivePeriods(now, matched) : now,
            identities,
            matched,
            search,
            highlights);
    var previous = peopleRows(before, identities, matched, search, Map.of());
    var matchedIds = new java.util.HashSet<>(matched);
    long active =
        board == Board.CREATOR
            ? directory.stream().filter(k -> k.enabled() && matchedIds.contains(k.id())).count()
            : bd.values().stream().filter(b -> b.usable() && matchedIds.contains(b.id())).count();
    Long invitedCount =
        board == Board.INVITER
            ? matched.stream().mapToLong(invitedId -> invited.getOrDefault(invitedId, 0L)).sum()
            : null;
    Long effectiveCount =
        board == Board.INVITER
            ? matched.stream().mapToLong(invitedId -> effective.getOrDefault(invitedId, 0L)).sum()
            : null;
    Long sellingCreators =
        board == Board.INVITER
            ? orders.sellingCreators(scoped, period.from(), period.until())
            : null;
    return new Data(
        total.meta(),
        new Summary(
            total.summary(), selling(rows), active, invitedCount, effectiveCount, sellingCreators),
        new Summary(total.previous(), selling(previous), null, null, null, null),
        total.change(),
        rows,
        board == Board.CREATOR
            ? creatorUnavailable(commissionVisible)
            : List.of(
                new UnavailableDTO("netCommission", "WEIDIAN_SETTLEMENT_PENDING"),
                new UnavailableDTO("prepayExpense", "INVITER_FINANCE_DASHBOARD_PENDING"),
                new UnavailableDTO("totalEstimatedCommission", "INVITER_FINANCE_DASHBOARD_PENDING"),
                new UnavailableDTO("totalExpense", "INVITER_FINANCE_DASHBOARD_PENDING")));
  }

  private static List<UnavailableDTO> creatorUnavailable(boolean authorized) {
    var fields = new ArrayList<UnavailableDTO>();
    fields.add(new UnavailableDTO("netCommission", "WEIDIAN_SETTLEMENT_PENDING"));
    if (!authorized) {
      fields.add(new UnavailableDTO("commissionRate", "COMMISSION_FORBIDDEN"));
    }
    String reason = authorized ? "CREATOR_COMMISSION_DASHBOARD_PENDING" : "COMMISSION_FORBIDDEN";
    for (String field :
        List.of("estimatedCommission", "commissionExpense", "cumulativeCommission")) {
      fields.add(new UnavailableDTO(field, reason));
    }
    return List.copyOf(fields);
  }

  private Map<Long, Long> invitedCounts() {
    var firstOwner = new HashMap<Long, KolOwnershipDTO>();
    ownership
        .createdBetween(Instant.EPOCH, Instant.now())
        .forEach(
            entry ->
                firstOwner.merge(
                    entry.kolId(), entry, (a, b) -> a.startTime().isAfter(b.startTime()) ? b : a));
    return firstOwner.values().stream()
        .map(KolOwnershipDTO::bdUserId)
        .filter(Objects::nonNull)
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }

  private static List<RankFactsDTO> withInactivePeriods(
      List<RankFactsDTO> facts, List<Long> matched) {
    var existing = facts.stream().map(RankFactsDTO::entityId).collect(Collectors.toSet());
    var result = new ArrayList<>(facts);
    matched.stream()
        .filter(id -> !existing.contains(id))
        .forEach(
            id ->
                result.add(
                    new RankFactsDTO(
                        id,
                        0,
                        0,
                        0,
                        0,
                        0,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0,
                        null,
                        null,
                        0)));
    return result;
  }

  private static List<Row> peopleRows(
      List<OrderStatsApi.RankFactsDTO> facts,
      Map<Long, Identity> identities,
      List<Long> matched,
      boolean search,
      Map<Long, List<String>> highlights) {
    var matches = new java.util.HashSet<>(matched);
    return facts.stream()
        .filter(f -> !search || matches.contains(f.entityId()))
        .map(
            f -> {
              var identity = identities.get(f.entityId());
              var metrics =
                  StatsOverviewService.metrics(
                      new TotalsDTO(
                          f.paidOrders(),
                          f.units(),
                          f.sales(),
                          f.cohortRefunds(),
                          f.completedRefunds(),
                          f.refundedOrders(),
                          null));
              return new Row(
                  f.entityId().toString(),
                  identity == null ? null : identity.name(),
                  identity == null ? null : identity.code(),
                  identity == null ? null : identity.related(),
                  identity == null ? null : identity.enabled(),
                  identity == null ? null : identity.social(),
                  f.entityId(),
                  null,
                  null,
                  null,
                  identity == null ? null : identity.invited(),
                  identity == null ? null : identity.effective(),
                  f.kolCount(),
                  f.spuCount(),
                  f.lastPaidAt(),
                  f.lastRefundedAt(),
                  metrics,
                  null,
                  null,
                  highlights.getOrDefault(f.entityId(), List.of()),
                  List.of(),
                  null,
                  identity == null ? null : identity.primaryPlatform(),
                  f.linkCount(),
                  identity == null ? null : identity.commissionRate(),
                  identity == null ? null : identity.relationStatus());
            })
        .toList();
  }

  private static long selling(List<Row> rows) {
    return rows.stream()
        .filter(r -> r.metrics().paidOrderCount() > 0 && !"0".equals(r.id()))
        .count();
  }

  private static boolean matches(Identity person, String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return true;
    }
    String needle = keyword.strip().toLowerCase(Locale.ROOT);
    return Objects.toString(person.name(), "").toLowerCase(Locale.ROOT).contains(needle)
        || Objects.toString(person.code(), "").toLowerCase(Locale.ROOT).contains(needle)
        || (person.social() != null
            && person.social().stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains(needle)));
  }

  private static String display(BdUserDTO bd) {
    return bd.nickname() == null || bd.nickname().isBlank() ? bd.username() : bd.nickname();
  }

  static Comparator<Row> comparator(Board board, StatsRanking sorting, String key) {
    boolean valid =
        switch (key) {
          case "rank", "rankChange", "refundedOrderCount" -> board == Board.PRODUCT;
          case "invitedCount", "effectiveCount" -> board == Board.INVITER;
          case "kolCount" -> board != Board.CREATOR;
          case "spuCount", "linkCount", "commissionRate" -> board == Board.CREATOR;
          case "averageOrderAmount", "amountRefundRate" -> board != Board.PRODUCT;
          case "paidOrderCount",
              "units",
              "salesAmount",
              "completedRefundAmount",
              NET,
              "periodRefundedOrderRate" ->
              true;
          default -> false;
        };
    if (!valid) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    Comparator<Row> primary =
        switch (key) {
          case "rank" -> sorting.compare(Row::rank);
          case "rankChange" -> sorting.compare(Row::rankChange);
          case "invitedCount" -> sorting.compare(Row::invitedCount);
          case "effectiveCount" -> sorting.compare(Row::effectiveCount);
          case "kolCount" -> sorting.compare(Row::kolCount);
          case "spuCount" -> sorting.compare(Row::spuCount);
          case "linkCount" -> sorting.compare(Row::linkCount);
          case "commissionRate" -> sorting.compare(Row::commissionRate);
          case "paidOrderCount" -> sorting.compare(r -> r.metrics().paidOrderCount());
          case "units" -> sorting.compare(r -> r.metrics().units());
          case "refundedOrderCount" -> sorting.compare(r -> r.metrics().refundedOrderCount());
          case "salesAmount" -> sorting.compare(r -> r.metrics().salesAmount());
          case "completedRefundAmount" -> sorting.compare(r -> r.metrics().completedRefundAmount());
          case "averageOrderAmount" -> sorting.compare(r -> r.metrics().averageOrderAmount());
          case "amountRefundRate" -> sorting.compare(r -> r.metrics().amountRefundRate());
          case "periodRefundedOrderRate" ->
              sorting.compare(r -> r.metrics().periodRefundedOrderRate());
          default -> sorting.compare(r -> r.metrics().netSalesAmount());
        };
    return primary
        .thenComparing(
            "completedRefundAmount".equals(key) ? Row::lastRefundedAt : Row::lastPaidAt,
            Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(Row::id);
  }

  private record Identity(
      String name,
      String code,
      String related,
      boolean enabled,
      List<String> social,
      Long invited,
      Long effective,
      String primaryPlatform,
      BigDecimal commissionRate,
      String relationStatus) {}

  private record Data(
      MetaDTO meta,
      Summary summary,
      Summary previous,
      ChangesDTO change,
      List<Row> rows,
      List<UnavailableDTO> unavailable) {}

  public record Summary(
      MetricsDTO metrics,
      long sellingCount,
      Long activeCount,
      Long invitedCount,
      Long effectiveCount,
      Long sellingCreatorCount) {}

  public record TrendPoint(LocalDate day, BigDecimal netSalesAmount) {}

  public record Row(
      String id,
      String name,
      String code,
      String related,
      Boolean enabled,
      List<String> social,
      Long entityId,
      Long shopId,
      Integer rank,
      Integer rankChange,
      Long invitedCount,
      Long effectiveCount,
      Long kolCount,
      Long spuCount,
      Instant lastPaidAt,
      Instant lastRefundedAt,
      MetricsDTO metrics,
      String imageUrl,
      String category,
      List<String> highlights,
      List<TrendPoint> trend,
      String sourceItemId,
      String primaryPlatform,
      Long linkCount,
      BigDecimal commissionRate,
      String relationStatus) {
    Row withRank(int nextRank, Integer change, List<TrendPoint> series) {
      return new Row(
          id,
          name,
          code,
          related,
          enabled,
          social,
          entityId,
          shopId,
          nextRank,
          change,
          invitedCount,
          effectiveCount,
          kolCount,
          spuCount,
          lastPaidAt,
          lastRefundedAt,
          metrics,
          imageUrl,
          category,
          highlights,
          series,
          sourceItemId,
          primaryPlatform,
          linkCount,
          commissionRate,
          relationStatus);
    }
  }

  public record Result(
      MetaDTO meta,
      Summary summary,
      Summary previous,
      ChangesDTO change,
      PageResult<Row> ranking,
      List<UnavailableDTO> unavailable,
      String sortBy,
      String sortDirection,
      String keyword) {}

  public record Option(Long id, String name, String code) {}

  public record Options(List<Option> shops, List<Option> creators, List<Option> inviters) {}
}
