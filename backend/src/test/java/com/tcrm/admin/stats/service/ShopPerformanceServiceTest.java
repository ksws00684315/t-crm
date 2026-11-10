package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.RankDimension;
import com.tcrm.admin.order.api.OrderStatsApi.RankFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.order.api.OrderStatsApi.TotalsDTO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.stats.service.ShopPerformanceService.ShopRankDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShopPerformanceServiceTest {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
  private static final Instant FROM = Instant.parse("2026-11-02T16:00:00Z");
  private static final Instant UNTIL = Instant.parse("2026-11-03T16:00:00Z");
  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final FilterDTO ALL = new FilterDTO(DAY, DAY, null, null, null, null);
  private final AccountDTO account = mock(AccountDTO.class);
  private final OrderStatsApi orders = mock(OrderStatsApi.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ShopPerformanceService service =
      new ShopPerformanceService(new StatsOverviewService(orders, current), orders, shops);

  @BeforeEach
  void setup() {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(40L);
    when(shops.listShops()).thenReturn(List.of(shop(3), shop(2), shop(1), shop(4)));
    when(orders.totals(any(), any(), any()))
        .thenReturn(new TotalsDTO(4, 8, ZERO, ZERO, ZERO, 0, UNTIL));
    when(orders.rankFacts(any(), any(), any(), any()))
        .thenReturn(
            List.of(
                facts(1, 2, "80", FROM.plusSeconds(2)),
                facts(2, 2, "80", FROM.plusSeconds(1)),
                facts(3, 0, "0", null)));
  }

  @Test
  void sortsAllNumericFieldsAndKeepsNullAveragesLast() {
    var result = service.query(ALL, null, null, null, null);
    assertThat(result.sortBy()).isEqualTo("netSalesAmount");
    assertThat(result.sortDirection()).isEqualTo("desc");
    assertThat(result.summary().sellingShopCount()).isEqualTo(2);
    assertThat(result.summary().shopCount()).isEqualTo(4);
    assertThat(result.ranking().items())
        .extracting(ShopRankDTO::shopId)
        .containsExactly(2L, 1L, 3L, 4L);
    for (String column :
        List.of(
            "paidOrderCount",
            "units",
            "spuCount",
            "skuCount",
            "kolCount",
            "salesAmount",
            "completedRefundAmount",
            "netSalesAmount",
            "averageOrderAmount")) {
      assertThat(service.query(ALL, 1, 50, column, "desc").sortBy()).isEqualTo(column);
      assertThat(service.query(ALL, 1, 100, column, "asc").sortDirection()).isEqualTo("asc");
    }
    assertThat(service.query(ALL, 1, 20, "averageOrderAmount", "asc").ranking().items())
        .extracting(ShopRankDTO::shopId)
        .containsExactly(2L, 1L, 3L, 4L);
    assertThat(service.query(ALL, 2, 20, null, null).ranking().items()).isEmpty();
  }

  @Test
  void restrictsShopMetadataAndFactsToTheSameVisibleScope() {
    when(account.ownScope()).thenReturn(true);
    when(account.id()).thenReturn(9L);
    var scope = new ScopeDTO(40, 9L, 1L, 8L, 9L, List.of(1L));
    when(orders.rankFacts(scope, RankDimension.SHOP, FROM, UNTIL))
        .thenReturn(List.of(facts(1, 2, "80", FROM)));
    var result = service.query(new FilterDTO(DAY, DAY, 1L, 8L, 9L, null), null, null, null, null);
    assertThat(result.ranking().items()).extracting(ShopRankDTO::shopId).containsExactly(1L);
    assertThat(result.meta().filters().shopId()).isEqualTo(1L);

    when(account.ownScope()).thenReturn(false);
    assertThat(
            service
                .query(new FilterDTO(DAY, DAY, null, 8L, null, null), 1, 20, null, null)
                .summary()
                .shopCount())
        .isEqualTo(3);
    assertThat(
            service
                .query(new FilterDTO(DAY, DAY, null, null, 9L, null), 1, 20, null, null)
                .summary()
                .shopCount())
        .isEqualTo(3);
  }

  @Test
  void rejectsInvalidPageSizeDirectionAndSortFields() {
    assertThatThrownBy(() -> service.query(ALL, 0, null, null, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.query(ALL, 10001, null, null, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.query(ALL, 1, 21, null, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.query(ALL, 1, 20, null, "foo"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.query(ALL, 1, 20, "name", null))
        .isInstanceOf(BusinessException.class);
  }

  private static ShopAccessDTO shop(long id) {
    return new ShopAccessDTO(id, "Store " + id, "remote-" + id, 1, "SHOP-" + id);
  }

  private static RankFactsDTO facts(long id, long orders, String sales, Instant achieved) {
    return new RankFactsDTO(
        id,
        orders,
        orders,
        orders,
        orders,
        orders,
        new BigDecimal(sales),
        ZERO,
        ZERO,
        0,
        achieved,
        achieved);
  }
}
