package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.api.WithdrawalWorkbenchApi;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.kol.api.KolWorkbenchApi;
import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.SalesWindowDTO;
import com.tcrm.admin.product.api.ProductWorkbenchApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

class WorkbenchServiceTest {
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final StatsOverviewService stats = mock(StatsOverviewService.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final OrderStatsApi orders = mock(OrderStatsApi.class);
  private final KolApi directory = mock(KolApi.class);
  private final ProductWorkbenchApi product = mock(ProductWorkbenchApi.class);
  private final KolWorkbenchApi kol = mock(KolWorkbenchApi.class);
  private final WithdrawalWorkbenchApi withdrawal = mock(WithdrawalWorkbenchApi.class);
  private final WorkbenchService service =
      new WorkbenchService(current, stats, orders, shops, directory, product, kol, withdrawal);
  private final AccountDTO account = mock(AccountDTO.class);

  @Test
  void unauthorizedFeaturesAreNeverQueried() {
    authorize(List.of());
    var result = service.overview();
    assertThat(result.sales()).isNull();
    assertThat(result.payouts()).isNull();
    assertThat(result.unavailable()).isEmpty();
    verifyNoInteractions(stats, orders, shops, directory, product, kol, withdrawal);
  }

  @Test
  void failedPanelDoesNotHideSuccessfulZeroCounts() {
    authorize(List.of("product:view"));
    when(product.shops()).thenThrow(new DataRetrievalFailureException("fixture"));
    when(product.processingJobs()).thenReturn(0L);
    when(product.failedJobs()).thenReturn(new ProductWorkbenchApi.FailedJobs(0, List.of()));

    var result = service.overview();
    assertThat(result.shops()).isNull();
    assertThat(result.processingJobs()).isZero();
    assertThat(result.jobs().total()).isZero();
    assertThat(result.unavailable()).containsExactly("shops");
  }

  @Test
  void salesUsesTheChinaBusinessDayAndAffiliateItemScope() {
    authorize(List.of("dashboard:shop"));
    when(stats.workbenchSales(any(StatsOverviewService.FilterDTO.class), any()))
        .thenAnswer(
            invocation -> {
              var filter = invocation.getArgument(0, StatsOverviewService.FilterDTO.class);
              assertThat(filter.startDate()).isEqualTo(filter.endDate());
              assertThat(filter.affiliateOnly()).isTrue();
              return new SalesWindowDTO(new BigDecimal("270.00"), 3, new BigDecimal("100.00"), 1);
            });
    when(orders.workbenchRanks(any(), any(), any(), any()))
        .thenThrow(new DataRetrievalFailureException("fixture"));

    var result = service.overview();
    assertThat(result.businessDate())
        .isEqualTo(result.asOf().atZone(StatsPeriod.ZONE).toLocalDate());
    assertThat(result.sales().todayOrders()).isEqualTo(1);
    assertThat(result.sales().todayAmount()).isEqualByComparingTo("100.00");
    assertThat(result.sales().totalOrders()).isEqualTo(3);
    assertThat(result.unavailable()).containsExactly("topShops");
  }

  @Test
  void ranksRejectsABoardTheUserCannotRead() {
    authorize(List.of("dashboard:shop"));

    assertThatThrownBy(
            () ->
                service.ranks(
                    WorkbenchService.Ranking.CREATOR, WorkbenchService.RankPeriod.TODAY, 1, 5))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  @Test
  void ranksValidatesPageAndSize() {
    authorize(List.of("dashboard:shop"));

    for (int[] request : new int[][] {{0, 5}, {PageResult.MAX_PAGE + 1, 5}, {1, 7}}) {
      assertThatThrownBy(
              () ->
                  service.ranks(
                      WorkbenchService.Ranking.SHOP,
                      WorkbenchService.RankPeriod.TODAY,
                      request[0],
                      request[1]))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error ->
                  assertThat(error.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    }
  }

  @Test
  void ranksRejectsAUserWithoutACompany() {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(null);

    assertThatThrownBy(
            () ->
                service.ranks(
                    WorkbenchService.Ranking.SHOP, WorkbenchService.RankPeriod.TODAY, 1, 5))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  @Test
  void shopRankingUsesTheVisibleShopIdentity() {
    authorize(List.of("dashboard:shop"));
    when(account.ownScope()).thenReturn(false);
    when(shops.listShops())
        .thenReturn(List.of(new ShopAccessDTO(1L, "A shop", "wd-1", 1, "SHOP-1")));
    when(orders.workbenchRanks(any(), any(), isNull(), any()))
        .thenReturn(List.of(new OrderStatsApi.WorkbenchRankDTO(1L, 3, new BigDecimal("90.00"))));

    var page =
        service.ranks(WorkbenchService.Ranking.SHOP, WorkbenchService.RankPeriod.ALL_TIME, 1, 5);

    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.name()).isEqualTo("A shop");
              assertThat(row.code()).isEqualTo("SHOP-1");
              assertThat(row.quantity()).isEqualTo(3);
              assertThat(row.amount()).isEqualByComparingTo("90.00");
            });
  }

  @Test
  void creatorRankingKeepsRowsWhoseCreatorIsGone() {
    authorize(List.of("dashboard:kol"));
    when(account.ownScope()).thenReturn(true);
    when(account.id()).thenReturn(9L);
    when(orders.workbenchRanks(any(), any(), notNull(), any()))
        .thenReturn(
            List.of(
                new OrderStatsApi.WorkbenchRankDTO(11L, 2, new BigDecimal("40.00")),
                new OrderStatsApi.WorkbenchRankDTO(12L, 1, new BigDecimal("10.00"))));
    when(directory.listByIdsIncludingDeleted(List.of(11L, 12L)))
        .thenReturn(List.of(new KolRefDTO(11L, "KOL-1", "Creator", true, 9L)));

    var page =
        service.ranks(
            WorkbenchService.Ranking.CREATOR, WorkbenchService.RankPeriod.LAST_7_DAYS, 1, 5);

    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).name()).isEqualTo("Creator");
    assertThat(page.items().get(0).code()).isEqualTo("KOL-1");
    assertThat(page.items().get(1).name()).isNull();
    assertThat(page.items().get(1).code()).isNull();
  }

  @Test
  void emptyPanelIsReportedAsUnavailable() {
    authorize(List.of("dashboard:shop"));
    when(stats.workbenchSales(any(StatsOverviewService.FilterDTO.class), any())).thenReturn(null);
    when(orders.workbenchRanks(any(), any(), any(), any())).thenReturn(List.of());
    when(shops.listShops()).thenReturn(List.of());

    var result = service.overview();

    assertThat(result.sales()).isNull();
    assertThat(result.unavailable()).containsExactly("sales");
  }

  @Test
  void financePanelsFollowTheirOwnPermissions() {
    authorize(
        List.of("dashboard:kol", "commission:view", "withdrawal:pay", "prepay:pay", "kol:view"));
    when(orders.workbenchRanks(any(), any(), any(), any())).thenReturn(List.of());
    when(stats.workbenchCommission(any(StatsOverviewService.FilterDTO.class), any()))
        .thenReturn(
            new OrderStatsApi.CommissionWindowDTO(new BigDecimal("8.00"), new BigDecimal("8.00")));
    when(withdrawal.pending(account)).thenReturn(new WithdrawalWorkbenchApi.Pending(1, List.of()));
    when(withdrawal.pendingPrepays(account)).thenReturn(0L);

    var result = service.overview();

    assertThat(result.commission().totalAmount()).isEqualByComparingTo("8.00");
    assertThat(result.payouts().total()).isEqualTo(1);
    assertThat(result.prepays()).isZero();
    assertThat(result.sales()).isNull();
  }

  private void authorize(List<String> permissions) {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(1L);
    when(current.effectivePermissions(account)).thenReturn(permissions);
  }
}
