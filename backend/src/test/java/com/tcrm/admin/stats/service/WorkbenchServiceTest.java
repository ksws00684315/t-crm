package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.api.WithdrawalWorkbenchApi;
import com.tcrm.admin.kol.api.KolWorkbenchApi;
import com.tcrm.admin.product.api.ProductWorkbenchApi;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

class WorkbenchServiceTest {
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final StatsOverviewService stats = mock(StatsOverviewService.class);
  private final ShopPerformanceService shops = mock(ShopPerformanceService.class);
  private final AnalyticsRankingService ranks = mock(AnalyticsRankingService.class);
  private final ProductWorkbenchApi product = mock(ProductWorkbenchApi.class);
  private final KolWorkbenchApi kol = mock(KolWorkbenchApi.class);
  private final WithdrawalWorkbenchApi withdrawal = mock(WithdrawalWorkbenchApi.class);
  private final WorkbenchService service =
      new WorkbenchService(current, stats, shops, ranks, product, kol, withdrawal);
  private final AccountDTO account = mock(AccountDTO.class);

  @Test
  void unauthorizedFeaturesAreNeverQueried() {
    authorize(List.of());
    var result = service.overview();
    assertThat(result.sales()).isNull();
    assertThat(result.payouts()).isNull();
    assertThat(result.unavailable()).isEmpty();
    verifyNoInteractions(stats, shops, ranks, product, kol, withdrawal);
  }

  @Test
  void failedPanelDoesNotHideSuccessfulZeroCounts() {
    authorize(List.of("product:view"));
    when(product.shops()).thenThrow(new DataRetrievalFailureException("fixture"));
    when(product.failedJobs()).thenReturn(new ProductWorkbenchApi.FailedJobs(0, List.of()));

    var result = service.overview();
    assertThat(result.shops()).isNull();
    assertThat(result.activeLinks()).isZero();
    assertThat(result.jobs().total()).isZero();
    assertThat(result.unavailable()).containsExactly("shops");
  }

  @Test
  void salesUsesTheChinaBusinessDayAndAffiliateItemScope() {
    authorize(List.of("dashboard:shop"));
    when(stats.overview(any(StatsOverviewService.FilterDTO.class)))
        .thenAnswer(
            invocation -> {
              var filter = invocation.getArgument(0, StatsOverviewService.FilterDTO.class);
              assertThat(filter.startDate()).isEqualTo(filter.endDate());
              assertThat(filter.affiliateOnly()).isTrue();
              return new StatsOverviewService.OverviewDTO(
                  null,
                  new StatsOverviewService.MetricsDTO(
                      1, 3, new BigDecimal("100.00"), null, null, null, 0, null, null, null),
                  null,
                  null,
                  List.of());
            });
    when(shops.query(any(), any(), any(), any(), any()))
        .thenThrow(new DataRetrievalFailureException("fixture"));

    var result = service.overview();
    assertThat(result.businessDate())
        .isEqualTo(result.asOf().atZone(StatsPeriod.ZONE).toLocalDate());
    assertThat(result.sales().orders()).isEqualTo(1);
    assertThat(result.sales().amount()).isEqualByComparingTo("100.00");
    assertThat(result.unavailable()).containsExactly("topShops");
  }

  private void authorize(List<String> permissions) {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(1L);
    when(current.effectivePermissions(account)).thenReturn(permissions);
  }
}
