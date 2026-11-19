package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.api.WithdrawalWorkbenchApi;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolWorkbenchApi;
import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.SalesWindowDTO;
import com.tcrm.admin.product.api.ProductWorkbenchApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
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
    when(orders.workbenchRanks(any(), any(), any()))
        .thenThrow(new DataRetrievalFailureException("fixture"));

    var result = service.overview();
    assertThat(result.businessDate())
        .isEqualTo(result.asOf().atZone(StatsPeriod.ZONE).toLocalDate());
    assertThat(result.sales().todayOrders()).isEqualTo(1);
    assertThat(result.sales().todayAmount()).isEqualByComparingTo("100.00");
    assertThat(result.sales().totalOrders()).isEqualTo(3);
    assertThat(result.unavailable()).containsExactly("topShops");
  }

  private void authorize(List<String> permissions) {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(1L);
    when(current.effectivePermissions(account)).thenReturn(permissions);
  }
}
