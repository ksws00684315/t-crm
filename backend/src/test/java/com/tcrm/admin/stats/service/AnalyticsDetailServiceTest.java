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
import com.tcrm.admin.order.api.OrderEvidenceApi.Kind;
import com.tcrm.admin.order.api.OrderEvidenceApi.Selection;
import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.stats.service.AnalyticsDetailService.Board;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.ResolvedFilter;
import com.tcrm.admin.system.api.BdUserApi;
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
  private final AnalyticsDetailService details =
      new AnalyticsDetailService(
          overview, evidence, shops, creators, orders, mock(BdUserApi.class));
  private final LocalDate day = LocalDate.of(2026, 11, 3);

  @Test
  void carriesProductCodeKeywordAndOwnerScopeToEvidencePage() {
    var filters = filters(1L);
    var scope = new ScopeDTO(4, 9L, 1L, 11L, null);
    when(overview.canView("order:view")).thenReturn(true);
    when(overview.resolve(filters)).thenReturn(resolved(scope));
    when(shops.listShops()).thenReturn(List.of(new ShopAccessDTO(1L, "Shop", "remote", 1, "S1")));
    when(evidence.page(any(), eq(Kind.REFUNDS), eq(2), eq(20)))
        .thenReturn(new PageResult<>(List.of(), 0, 2, 20));

    assertThat(
            details
                .detail(Board.PRODUCT, filters, null, "SPU", "Sample", Kind.REFUNDS, 2, 20)
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

  private FilterDTO filters(Long shopId) {
    return new FilterDTO(day, day, shopId, null, null, "WEIDIAN");
  }

  private ResolvedFilter resolved(ScopeDTO scope) {
    return new ResolvedFilter(scope, new StatsPeriod(day, day), null, DataScope.OWN);
  }
}
