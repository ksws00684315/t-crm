package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.order.api.OrderStatsApi.TotalsDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import com.tcrm.admin.system.api.BdUserApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsOverviewServiceTest {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
  private static final Instant FROM = Instant.parse("2026-11-02T16:00:00Z");
  private static final Instant UNTIL = Instant.parse("2026-11-03T16:00:00Z");
  private static final Instant PRIOR = Instant.parse("2026-11-01T16:00:00Z");
  private final OrderStatsApi orders = mock(OrderStatsApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final AccountDTO account = mock(AccountDTO.class);
  private final KolApi creators = mock(KolApi.class);
  private final BdUserApi inviters = mock(BdUserApi.class);
  private final StatsOverviewService service =
      new StatsOverviewService(orders, current, creators, inviters);

  @Test
  void scopesBothPeriodsAndKeepsRefundClocksSeparate() {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(40L);
    when(account.id()).thenReturn(99L);
    when(account.ownScope()).thenReturn(true);
    var scope = new ScopeDTO(40, 99L, 6L, 8L, 99L);
    when(orders.totals(scope, FROM, UNTIL)).thenReturn(totals(2, 3, "100", "30", "10", 1));
    when(orders.totals(scope, PRIOR, FROM)).thenReturn(totals(1, 2, "50", "5", "0", 0));

    var result = service.overview(new FilterDTO(DAY, DAY, 6L, 8L, 99L, "WEIDIAN"));
    assertThat(result.meta().dataScope()).isEqualTo(DataScope.OWN);
    assertThat(result.meta().timezone()).isEqualTo("Asia/Shanghai");
    assertThat(result.meta().updatedAt()).isEqualTo(UNTIL);
    assertThat(result.summary().netSalesAmount()).isEqualByComparingTo("70");
    assertThat(result.summary().completedRefundAmount()).isEqualByComparingTo("10");
    assertThat(result.summary().amountRefundRate()).isEqualByComparingTo("0.1");
    assertThat(result.summary().periodRefundedOrderRate()).isEqualByComparingTo("0.5");
    assertThat(result.summary().averageOrderAmount()).isEqualByComparingTo("50");
    assertThat(result.change().salesAmount()).isEqualByComparingTo("1");
    assertThat(result.change().completedRefundAmount()).isNull();
    assertThat(result.unavailable()).hasSize(3);
    verify(orders).totals(scope, FROM, UNTIL);
    verify(orders).totals(scope, PRIOR, FROM);
  }

  @Test
  void resolvesCreatorNameCodeAndIdWithinTheAccountOrderScope() {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(40L);
    when(account.id()).thenReturn(99L);
    when(account.ownScope()).thenReturn(true);
    when(creators.listAll())
        .thenReturn(
            List.of(
                new KolRefDTO(8L, "KOL-001", "北岸达人", true, 99L),
                new KolRefDTO(9L, "KOL-002", "纸飞机达人", true, 12L)));

    for (String keyword : List.of("北岸", " kol-001 ", "8")) {
      var result = service.resolve(new FilterDTO(DAY, DAY, 6L, null, null, null, keyword));
      assertThat(result.scope().matchedKolIds()).containsExactly(8L);
      assertThat(result.scope().tenantId()).isEqualTo(40L);
      assertThat(result.scope().ownerId()).isEqualTo(99L);
      assertThat(result.filters().creatorKeyword()).isEqualTo(keyword.strip());
    }
    assertThat(
            service
                .resolve(new FilterDTO(DAY, DAY, 6L, null, null, null, "missing"))
                .scope()
                .matchedKolIds())
        .isEmpty();
    assertThat(
            service
                .resolve(new FilterDTO(DAY, DAY, 6L, null, null, null, " "))
                .scope()
                .matchedKolIds())
        .isNull();
  }

  @Test
  void keepsInviterKeywordIntersectedWithCreatorAndAccountScopes() {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(40L);
    when(account.id()).thenReturn(99L);
    when(account.ownScope()).thenReturn(true);
    when(creators.listAll()).thenReturn(List.of(new KolRefDTO(8L, "K1", "Alice", true, 99L)));
    when(inviters.findMemberIdsByKeyword("bd")).thenReturn(List.of(99L, 100L));

    var result = service.resolve(new FilterDTO(DAY, DAY, 6L, null, null, null, "Alice", " bd "));
    assertThat(result.scope())
        .isEqualTo(new ScopeDTO(40, 99L, 6L, null, null, null, List.of(8L), List.of(99L, 100L)));
    assertThat(result.filters().inviterKeyword()).isEqualTo("bd");
    assertThat(
            service
                .resolve(new FilterDTO(DAY, DAY, null, null, null, null, null, "unmatched"))
                .scope()
                .matchedInviterIds())
        .isEmpty();
  }

  @Test
  void zeroDenominatorsAreUnavailableEvenWithRefunds() {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(40L);
    when(orders.totals(any(), any(), any())).thenReturn(totals(0, 0, "0", "0", "20", 1));
    var result = service.overview(new FilterDTO(DAY, DAY, null, null, null, ""));
    assertThat(result.meta().dataScope()).isEqualTo(DataScope.ALL);
    assertThat(result.summary().averageOrderAmount()).isNull();
    assertThat(result.summary().amountRefundRate()).isNull();
    assertThat(result.summary().periodRefundedOrderRate()).isNull();
    assertThat(result.change().averageOrderAmount()).isNull();
  }

  @Test
  void rejectsRequestsWithoutTenantOrKnownChannel() {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(null);
    assertThatThrownBy(() -> service.overview(new FilterDTO(DAY, DAY, null, null, null, null)))
        .isInstanceOf(BusinessException.class);
    when(account.tenantId()).thenReturn(40L);
    assertThatThrownBy(() -> service.overview(new FilterDTO(DAY, DAY, null, null, null, "OTHER")))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(orders);
  }

  private static TotalsDTO totals(
      long orders, long units, String sales, String cohort, String completed, long refunded) {
    return new TotalsDTO(
        orders,
        units,
        new BigDecimal(sales),
        new BigDecimal(cohort),
        new BigDecimal(completed),
        refunded,
        UNTIL);
  }
}
