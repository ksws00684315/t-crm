package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderEvidenceApi;
import com.tcrm.admin.order.api.OrderEvidenceApi.DetailSort;
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
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.stats.service.AnalyticsDetailService.Board;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.ResolvedFilter;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnalyticsDetailServiceTest {
  private final StatsOverviewService overview = mock(StatsOverviewService.class);
  private final OrderEvidenceApi evidence = mock(OrderEvidenceApi.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final KolApi creators = mock(KolApi.class);
  private final OrderStatsApi orders = mock(OrderStatsApi.class);
  private final BdUserApi inviters = mock(BdUserApi.class);
  private final AnalyticsDetailService details =
      new AnalyticsDetailService(overview, evidence, shops, creators, orders, inviters);
  private final LocalDate day = LocalDate.of(2026, 11, 3);

  @Test
  void carriesProductCodeKeywordAndOwnerScopeToEvidencePage() {
    var filters = filters(1L);
    var scope = new ScopeDTO(4, 9L, 1L, 11L, null).withProductKeyword("Sample");
    when(overview.canView("order:view")).thenReturn(true);
    when(overview.resolve(filters)).thenReturn(resolved(scope));
    when(shops.listShops()).thenReturn(List.of(new ShopAccessDTO(1L, "Shop", "remote", 1, "S1")));
    when(evidence.page(any(), eq(Kind.REFUNDS), eq(2), eq(20)))
        .thenReturn(new PageResult<>(List.of(), 0, 2, 20));

    assertThat(
            details
                .detail(Board.PRODUCT, filters, null, "SPU", null, Kind.REFUNDS, 2, 20)
                .activity()
                .page())
        .isEqualTo(2);
    var selection = ArgumentCaptor.forClass(Selection.class);
    verify(evidence).page(selection.capture(), eq(Kind.REFUNDS), eq(2), eq(20));
    assertThat(selection.getValue().scope())
        .isEqualTo(new ScopeDTO(4, 9L, 1L, 11L, null, List.of(1L)));
    assertThat(selection.getValue().productCode()).isEqualTo("SPU");
    assertThat(selection.getValue().keyword()).isEqualTo("Sample");
  }

  @Test
  void productContributionIncludesCreatorCodeAndCurrentInviter() {
    var filters = filters(null);
    when(overview.resolve(filters)).thenReturn(resolved(new ScopeDTO(4, null, null, null, null)));
    when(shops.listShops()).thenReturn(List.of(new ShopAccessDTO(1L, "Shop", "remote", 1, "S1")));
    when(evidence.productGroups(any(), eq(ProductBreakdown.CREATORS), eq(1), eq(20)))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new ProductGroup(
                        "11",
                        null,
                        2,
                        4,
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        BigDecimal.TEN,
                        null,
                        null,
                        null,
                        null)),
                1,
                1,
                20));
    when(creators.listByIdsIncludingDeleted(List.of(11L)))
        .thenReturn(List.of(new KolRefDTO(11L, "C11", "Creator", true, 9L)));
    when(inviters.listByIds(List.of(9L)))
        .thenReturn(List.of(new BdUserDTO(9L, "bd", "Inviter", true)));

    assertThat(
            details
                .productBreakdown(filters, "product:20", null, ProductBreakdown.CREATORS, 1, 20)
                .items())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.creatorCode()).isEqualTo("C11");
              assertThat(row.inviterName()).isEqualTo("Inviter");
            });
  }

  @Test
  void ownUsersCannotOpenOtherInvitersAssignedCreators() {
    var filters = filters(null);
    when(overview.canView("kol:view")).thenReturn(true);
    when(overview.resolve(filters)).thenReturn(resolved(new ScopeDTO(4, 9L, null, null, null)));
    assertThatThrownBy(() -> details.inviterCreators(filters, 10L, 1, 20, false))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(creators, orders);
  }

  @Test
  void assignedCreatorsIncludesCreatorsWithoutOrders() {
    var filters = filters(null);
    when(overview.canView("kol:view")).thenReturn(true);
    when(overview.resolve(filters)).thenReturn(resolved(new ScopeDTO(4, 9L, null, null, null)));
    when(shops.listShops()).thenReturn(List.of(new ShopAccessDTO(1L, "Shop", "remote", 1, "S1")));
    when(creators.listByBdUserId(9L))
        .thenReturn(List.of(new KolRefDTO(11L, "C11", "Creator", true, 9L)));
    assertThat(details.inviterCreators(filters, 9L, 1, 20, true).items())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.id()).isEqualTo(11L);
              assertThat(row.metrics().paidOrderCount()).isZero();
            });
  }

  @Test
  void periodContributorsIncludeTransferredCreatorsWithOnlyRefunds() {
    var filters = filters(null);
    when(overview.canView("kol:view")).thenReturn(true);
    when(overview.resolve(filters)).thenReturn(resolved(new ScopeDTO(4, 9L, null, null, null)));
    when(shops.listShops()).thenReturn(List.of(new ShopAccessDTO(1L, "Shop", "remote", 1, "S1")));
    var scope = new ScopeDTO(4, 9L, null, null, 9L, List.of(1L));
    var period = new StatsPeriod(day, day);
    when(orders.rankFacts(scope, OrderStatsApi.RankDimension.KOL, period.from(), period.until()))
        .thenReturn(
            List.of(
                new OrderStatsApi.RankFactsDTO(
                    11L,
                    0,
                    0,
                    0,
                    0,
                    0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    new BigDecimal("30.00"),
                    1,
                    null,
                    period.from(),
                    0)));
    when(creators.listByIdsIncludingDeleted(List.of(11L)))
        .thenReturn(List.of(new KolRefDTO(11L, "C11", "Creator", true, 10L)));

    assertThat(details.inviterCreators(filters, 9L, 1, 20, false).items())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.transferred()).isTrue();
              assertThat(row.currentInviter()).isNull();
              assertThat(row.enabled()).isNull();
              assertThat(row.metrics().completedRefundAmount()).isEqualByComparingTo("30.00");
            });
  }

  @Test
  void dashboardOnlyPermissionCannotReadIndividualOrderEvidence() {
    assertThatThrownBy(
            () -> details.detail(Board.SHOP, filters(1L), null, null, null, Kind.ORDERS, 1, 20))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(evidence);
  }

  @Test
  void shopOverviewReturnsPeriodCreatorCountsAndCompanySettlement() {
    var filters = filters(1L);
    var period = new StatsPeriod(day, day);
    var scope = new ScopeDTO(4, null, 1L, null, null);
    var counts = new OrderEvidenceApi.ShopCreatorCounts(3, 5);
    when(overview.canView("order:view")).thenReturn(true);
    when(shops.listShops()).thenReturn(List.of(new ShopAccessDTO(1L, "Shop", "remote", 1, "S1")));
    when(evidence.shopCreatorCounts(any())).thenReturn(counts);
    when(orders.shopSettlement(4L, List.of(1L), period.from(), period.until()))
        .thenReturn(
            new OrderStatsApi.ShopSettlementDTO(new BigDecimal("170"), new BigDecimal("20")));
    when(overview.resolve(filters))
        .thenReturn(new ResolvedFilter(scope, period, null, DataScope.ALL));

    var result = details.detail(Board.SHOP, filters, null, null, null, null, null, null);
    assertThat(result.shopOverview().creatorCounts()).isEqualTo(counts);
    assertThat(result.shopOverview().settlementAmount()).isEqualByComparingTo("150");

    when(overview.canView("order:view")).thenReturn(false);
    assertThat(
            details
                .detail(Board.SHOP, filters, null, null, null, null, null, null)
                .shopOverview()
                .settlementAmount())
        .isNull();
  }

  @Test
  void settlementFlowsUseShopAndPeriodAndEnforceOrderAndCompanyScope() {
    var filters = filters(1L);
    assertThatThrownBy(() -> details.shopSettlementFlows(filters, 1, 20))
        .isInstanceOf(BusinessException.class);
    when(overview.canView("order:view")).thenReturn(true);
    when(overview.resolve(filters)).thenReturn(resolved(new ScopeDTO(4, 9L, 1L, null, null)));
    assertThatThrownBy(() -> details.shopSettlementFlows(filters, 1, 20))
        .isInstanceOf(BusinessException.class);
    var period = new StatsPeriod(day, day);
    var expected = new PageResult<OrderEvidenceApi.SettlementFlowRow>(List.of(), 0, 1, 20);
    when(overview.resolve(filters))
        .thenReturn(
            new ResolvedFilter(new ScopeDTO(4, null, 1L, null, null), period, null, DataScope.ALL));
    when(shops.findShop(1L)).thenReturn(new ShopAccessDTO(1L, "Shop", "remote", 1, "S1"));
    when(evidence.shopSettlementFlows(4L, 1L, period.from(), period.until(), 1, 20))
        .thenReturn(expected);
    assertThat(details.shopSettlementFlows(filters, 1, 20)).isEqualTo(expected);
  }

  @Test
  void orderEvidenceUsesTheRequestedSortAndRejectsUnsupportedMetrics() {
    var filters = filters(1L);
    var selection =
        new Selection(
            new ScopeDTO(4, null, 1L, null, null, List.of(1L)),
            null,
            null,
            null,
            null,
            new StatsPeriod(day, day).from(),
            new StatsPeriod(day, day).until());
    when(overview.canView("order:view")).thenReturn(true);
    useScope(filters, new ScopeDTO(4, null, 1L, null, null));
    var page = new PageResult<OrderEvidenceApi.EvidenceRow>(List.of(), 40, 2, 20);
    when(evidence.sortedActivity(selection, Kind.ORDERS, 2, 20, DetailSort.REFUND_AMOUNT, true))
        .thenReturn(page);

    var result =
        details.detail(
            Board.SHOP,
            filters,
            null,
            null,
            null,
            Kind.ORDERS,
            2,
            20,
            DetailSort.REFUND_AMOUNT,
            "asc");
    assertThat(result.activity()).isSameAs(page);
    for (var sort : List.of(DetailSort.PAID_ORDER_COUNT, DetailSort.NET_SALES_AMOUNT)) {
      assertThatThrownBy(
              () ->
                  details.detail(
                      Board.SHOP, filters, null, null, null, Kind.REFUNDS, 1, 20, sort, "desc"))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    }
  }

  @Test
  void productOverviewCountsRequireOrderReadPermission() {
    var filters = filters(null);
    useScope(filters, new ScopeDTO(4, null, null, null, null));
    var counts = new OrderEvidenceApi.ProductOrderCounts(2, 1);
    when(evidence.productOrderCounts(any())).thenReturn(counts);

    assertThat(
            details
                .detail(Board.PRODUCT, filters, null, "product:20", null, null, null, null)
                .productOrderCounts())
        .isNull();
    verifyNoInteractions(orders);
    when(overview.canView("order:view")).thenReturn(true);
    assertThat(
            details
                .detail(Board.PRODUCT, filters, null, "product:20", null, null, null, null)
                .productOrderCounts())
        .isEqualTo(counts);
    verify(evidence).productOrderCounts(any());
  }

  private record Target(Board board, Long entityId, String productCode) {}

  @Test
  void detailRejectsIncompatibleKeysAndInvisibleShops() {
    var filters = filters(1L);
    useScope(filters, new ScopeDTO(4, null, 1L, null, null));
    var invalid =
        List.of(
            new Target(Board.SHOP, 11L, null),
            new Target(Board.SHOP, null, "product:20"),
            new Target(Board.PRODUCT, 11L, "product:20"),
            new Target(Board.PRODUCT, null, null),
            new Target(Board.PRODUCT, null, " "),
            new Target(Board.CREATOR, null, null),
            new Target(Board.CREATOR, -1L, null),
            new Target(Board.INVITER, 9L, "product:20"));
    for (var target : invalid) {
      assertInvalidTarget(filters, target);
    }
    useScope(filters, new ScopeDTO(4, null, null, null, 9L));
    assertInvalidTarget(filters, new Target(Board.SHOP, null, null));
    assertInvalidTarget(filters, new Target(Board.PRODUCT, null, "product:20"));

    useScope(filters, new ScopeDTO(4, null, 2L, null, null));
    assertThatThrownBy(
            () -> details.detail(Board.SHOP, filters, null, null, null, null, null, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
    verifyNoInteractions(evidence);
  }

  @Test
  void peopleOverviewSelectsOnlyTheRequestedEntityDimension() {
    var filters = filters(null);
    var scope = new ScopeDTO(4, null, null, null, null, List.of(1L)).withProductKeyword("Item");
    useScope(filters, scope);
    var period = new StatsPeriod(day, day);
    details.detail(Board.CREATOR, filters, 11L, null, null, null, null, null);
    details.detail(Board.INVITER, filters, 9L, null, null, null, null, null);
    verify(evidence)
        .daily(new Selection(scope, 11L, null, null, null, period.from(), period.until()));
    verify(evidence)
        .daily(new Selection(scope, null, 9L, null, null, period.from(), period.until()));
  }

  @Test
  void rejectsSettlementLedgerForPartialScopesAndMissingShop() {
    var filters = filters(1L);
    var period = new StatsPeriod(day, day);
    when(overview.canView("order:view")).thenReturn(true);
    var partial =
        List.of(
            new ScopeDTO(4, 9L, 1L, null, null),
            new ScopeDTO(4, null, 1L, 11L, null),
            new ScopeDTO(4, null, 1L, null, 9L),
            new ScopeDTO(4, null, 1L, null, null, List.of(1L), List.of(11L), null),
            new ScopeDTO(4, null, 1L, null, null, List.of(1L), null, List.of(9L)),
            new ScopeDTO(4, null, 1L, null, null).withProductKeyword("Item"));
    for (var scope : partial) {
      when(overview.resolve(filters))
          .thenReturn(new ResolvedFilter(scope, period, null, DataScope.ALL));
      assertThatThrownBy(() -> details.shopSettlementFlows(filters, 1, 20))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }
    when(overview.resolve(filters))
        .thenReturn(
            new ResolvedFilter(new ScopeDTO(4, null, 1L, null, null), period, null, DataScope.ALL));
    assertThatThrownBy(() -> details.shopSettlementFlows(filters, 1, 20))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
    verifyNoInteractions(evidence);
  }

  @Test
  void sortedSkuBreakdownKeepsSourceScopeAndDoesNotQueryPeople() {
    var filters = filters(null);
    useScope(filters, new ScopeDTO(4, null, null, null, null));
    var expected = new PageResult<ProductGroup>(List.of(group("skuid")), 1, 1, 20);
    when(evidence.sortedProductGroups(
            any(),
            eq(ProductBreakdown.SKUS),
            eq(1),
            eq(20),
            eq(DetailSort.NET_SALES_AMOUNT),
            eq(false)))
        .thenReturn(expected);

    assertThat(
            details.productBreakdown(
                filters,
                "product:20",
                "Search",
                ProductBreakdown.SKUS,
                1,
                20,
                DetailSort.NET_SALES_AMOUNT,
                "desc"))
        .isSameAs(expected);
    var selection = ArgumentCaptor.forClass(Selection.class);
    verify(evidence)
        .sortedProductGroups(
            selection.capture(),
            eq(ProductBreakdown.SKUS),
            eq(1),
            eq(20),
            eq(DetailSort.NET_SALES_AMOUNT),
            eq(false));
    assertThat(selection.getValue().keyword()).isEqualTo("Search");
    assertThat(selection.getValue().scope().visibleShopIds()).containsExactly(1L);
    verifyNoInteractions(creators, inviters);
  }

  @Test
  void creatorBreakdownHidesOtherInvitersAndKeepsHistoricalRows() {
    var filters = filters(null);
    useScope(filters, new ScopeDTO(4, 9L, null, null, null));
    when(evidence.productGroups(any(), eq(ProductBreakdown.CREATORS), eq(1), eq(20)))
        .thenReturn(
            new PageResult<>(
                List.of(group("11"), group("12"), group("13"), group("14")), 4, 1, 20));
    when(creators.listByIdsIncludingDeleted(List.of(11L, 12L, 13L, 14L)))
        .thenReturn(
            List.of(
                new KolRefDTO(11L, "C11", "A", true, 9L),
                new KolRefDTO(12L, "C12", "B", false, 10L),
                new KolRefDTO(13L, "C13", "C", true, null)));
    when(inviters.listByIds(List.of(9L)))
        .thenReturn(List.of(new BdUserDTO(9L, "owner-login", "  ", true)));

    var rows =
        details
            .productBreakdown(filters, "product:20", null, ProductBreakdown.CREATORS, 1, 20)
            .items();
    assertThat(rows)
        .extracting(ProductGroup::inviterName)
        .containsExactly("owner-login", null, null, null);
    assertThat(rows)
        .extracting(ProductGroup::creatorCode)
        .containsExactly("C11", "C12", "C13", null);
    verify(inviters).listByIds(List.of(9L));
  }

  @Test
  void emptyCreatorBreakdownDoesNotQueryTheDirectory() {
    var filters = filters(null);
    useScope(filters, new ScopeDTO(4, null, null, null, null));
    var empty = new PageResult<ProductGroup>(List.of(), 0, 1, 20);
    when(evidence.productGroups(any(), eq(ProductBreakdown.CREATORS), eq(1), eq(20)))
        .thenReturn(empty);
    assertThat(details.productBreakdown(filters, "P", null, ProductBreakdown.CREATORS, 1, 20))
        .isSameAs(empty);
    verifyNoInteractions(creators, inviters);
  }

  @Test
  void inviterContributorsRequirePermissionAndRespectInviterAndShopFilters() {
    var filters = filters(null);
    assertThatThrownBy(() -> details.inviterCreators(filters, 9L, 1, 20, false))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    when(overview.canView("kol:view")).thenReturn(true);
    for (var scope :
        List.of(new ScopeDTO(4, 9L, null, null, 10L), new ScopeDTO(4, null, 2L, null, null))) {
      useScope(filters, scope);
      assertThatThrownBy(() -> details.inviterCreators(filters, 9L, 1, 20, false))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
    }
    verifyNoInteractions(orders, creators);
  }

  @Test
  void currentTeamCombinesCreatorAndProductFilters() {
    var filters = filters(1L);
    var scope =
        new ScopeDTO(4, 9L, 1L, 11L, null, List.of(1L), List.of(11L), List.of(9L))
            .withProductKeyword("Item");
    useScope(filters, scope);
    when(overview.canView("kol:view")).thenReturn(true);
    when(creators.listByBdUserId(9L))
        .thenReturn(
            List.of(
                new KolRefDTO(11L, "C11", "Matched", true, 9L),
                new KolRefDTO(12L, "C12", "Different creator", true, 9L)));
    assertThat(details.inviterCreators(filters, 9L, 1, 20, true).items()).isEmpty();

    when(orders.rankFacts(any(), eq(RankDimension.KOL), any(), any()))
        .thenReturn(List.of(facts(11L, 2, 3, 80, 15, 20)));
    assertThat(details.inviterCreators(filters, 9L, 1, 20, true).items())
        .extracting(AnalyticsDetailService.CreatorRow::id)
        .containsExactly(11L);

    useScope(filters, new ScopeDTO(4, 9L, 1L, null, null, List.of(1L), List.of(11L), null));
    assertThat(details.inviterCreators(filters, 9L, 1, 20, true).items())
        .extracting(AnalyticsDetailService.CreatorRow::id)
        .containsExactly(11L);
  }

  @Test
  void companyContributorsSortByEveryMetricAndKeepTransferredAndUnattributedRows() {
    var filters = filters(null);
    var scope = new ScopeDTO(4, null, null, null, 9L);
    useScope(filters, scope);
    when(overview.canView("kol:view")).thenReturn(true);
    when(orders.rankFacts(any(), eq(RankDimension.KOL), any(), any()))
        .thenReturn(
            List.of(
                facts(0L, 0, 0, 0, 0, 0),
                facts(11L, 2, 2, 60, 10, 20),
                facts(12L, 1, 3, 70, 30, 10),
                facts(13L, 0, 0, 0, 0, 0)));
    when(creators.listByIdsIncludingDeleted(List.of(11L, 12L, 13L)))
        .thenReturn(
            List.of(
                new KolRefDTO(11L, "C11", "Current", true, 9L),
                new KolRefDTO(12L, "C12", "Transferred", false, 10L),
                new KolRefDTO(13L, "C13", "Unassigned now", false, null)));
    when(inviters.listByIds(List.of(9L, 10L)))
        .thenReturn(
            List.of(
                new BdUserDTO(9L, "current-login", null, true),
                new BdUserDTO(10L, "another-login", "Another", true)));
    var metrics =
        List.of(
            DetailSort.PAID_ORDER_COUNT,
            DetailSort.UNITS,
            DetailSort.SALES_AMOUNT,
            DetailSort.REFUND_AMOUNT,
            DetailSort.NET_SALES_AMOUNT);
    var expectedLeaders = List.of(11L, 12L, 12L, 11L, 11L);

    for (int i = 0; i < metrics.size(); i++) {
      var page = details.inviterCreators(filters, 9L, 1, 20, false, metrics.get(i), "desc");
      assertThat(page.items().getFirst().id()).isEqualTo(expectedLeaders.get(i));
    }
    var rows =
        details
            .inviterCreators(filters, 9L, 1, 20, false, DetailSort.NET_SALES_AMOUNT, "asc")
            .items();
    assertThat(rows)
        .extracting(AnalyticsDetailService.CreatorRow::id)
        .containsExactly(0L, 13L, 12L, 11L);
    assertThat(rows.getFirst().name()).isNull();
    assertThat(rows.get(1).currentInviter()).isNull();
    assertThat(rows.get(2).transferred()).isTrue();
    assertThat(rows.get(2).enabled()).isFalse();
    assertThat(rows.get(2).currentInviter()).isEqualTo("Another");
    assertThat(rows.getLast().currentInviter()).isEqualTo("current-login");
  }

  private void assertInvalidTarget(FilterDTO filters, Target target) {
    assertThatThrownBy(
            () ->
                details.detail(
                    target.board(),
                    filters,
                    target.entityId(),
                    target.productCode(),
                    null,
                    null,
                    null,
                    null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  private void useScope(FilterDTO filters, ScopeDTO scope) {
    when(overview.resolve(filters)).thenReturn(resolved(scope));
    when(shops.listShops()).thenReturn(List.of(new ShopAccessDTO(1L, "Shop", "remote", 1, "S1")));
  }

  private static ProductGroup group(String code) {
    return new ProductGroup(
        code, null, 2, 4, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, null, null, null);
  }

  private static RankFactsDTO facts(
      long id, int number, int units, int sales, int cohort, int refund) {
    return new RankFactsDTO(
        id,
        number,
        units,
        0,
        0,
        0,
        BigDecimal.valueOf(sales),
        BigDecimal.valueOf(cohort),
        BigDecimal.valueOf(refund),
        refund == 0 ? 0 : 1,
        null,
        null,
        0);
  }

  private FilterDTO filters(Long shopId) {
    return new FilterDTO(day, day, shopId, null, null, "WEIDIAN");
  }

  private ResolvedFilter resolved(ScopeDTO scope) {
    return new ResolvedFilter(scope, new StatsPeriod(day, day), null, DataScope.OWN);
  }
}
