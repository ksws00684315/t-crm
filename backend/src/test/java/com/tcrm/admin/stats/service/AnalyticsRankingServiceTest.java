package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolOwnershipApi;
import com.tcrm.admin.kol.api.KolOwnershipDTO;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderStatsApi;
import com.tcrm.admin.order.api.OrderStatsApi.HighlightDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ProductFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.RankFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.TotalsDTO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ProductAccessApi.ProductRefDTO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.stats.service.AnalyticsRankingService.Board;
import com.tcrm.admin.stats.service.AnalyticsRankingService.Row;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalyticsRankingServiceTest {
  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
  private static final Instant FROM = Instant.parse("2026-11-02T16:00:00Z");
  private static final FilterDTO FILTER = new FilterDTO(DAY, DAY, null, null, null, null);
  private final OrderStatsApi orders = mock(OrderStatsApi.class);
  private final KolApi creators = mock(KolApi.class);
  private final KolOwnershipApi ownership = mock(KolOwnershipApi.class);
  private final BdUserApi inviters = mock(BdUserApi.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final ProductAccessApi catalog = mock(ProductAccessApi.class);
  private final AccountDTO account = mock(AccountDTO.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final AnalyticsRankingService service =
      new AnalyticsRankingService(
          new StatsOverviewService(orders, current),
          orders,
          shops,
          catalog,
          creators,
          inviters,
          ownership);

  @BeforeEach
  void setup() {
    when(current.user()).thenReturn(account);
    when(account.tenantId()).thenReturn(40L);
    when(account.id()).thenReturn(9L);
    when(shops.listShops()).thenReturn(List.of(new ShopAccessDTO(1L, "Shop", "wd", 1, "SHOP1")));
    when(orders.totals(any(), any(), any()))
        .thenReturn(new TotalsDTO(2, 4, ZERO, ZERO, ZERO, 0, FROM));
  }

  @Test
  void keepsProductBusinessRankOnAlternateSortAndUsesCohortOrderRate() {
    var total = product(null, null, 3, 100);
    when(orders.productFacts(any(), any(), any(), any()))
        .thenReturn(List.of(total, product(1L, "B", 1, 40), product(1L, "A", 2, 60)));
    when(orders.productFacts(any(), any(), any(), eq(FROM)))
        .thenReturn(
            List.of(
                total, product(1L, "B", 1, 70), product(1L, "A", 2, 30), product(1L, "C", 1, 20)));
    when(catalog.listProducts(List.of(50L)))
        .thenReturn(
            List.of(
                new ProductRefDTO(
                    50L, "P1", "Item", "https://example.test/a", 1, "Category", "source-50")));
    when(orders.productTrend(any(), any(), any(), any()))
        .thenReturn(List.of(product(1L, "A", 2, 60)));
    var result = service.query(Board.PRODUCT, FILTER, "item", 1, 20, "rank", "desc");
    assertThat(result.ranking().items().getLast().trend().getFirst().netSalesAmount())
        .isEqualByComparingTo("60");
    assertThat(result.summary().metrics().paidOrderCount()).isEqualTo(3);
    assertThat(result.ranking().items()).extracting(Row::rank).containsExactly(2, 1);
    assertThat(result.ranking().items()).extracting(Row::rankChange).containsExactly(-1, 1);
    assertThat(result.ranking().items().getLast().metrics().periodRefundedOrderRate())
        .isEqualByComparingTo("0.5");
    assertThat(result.ranking().items().getFirst().category()).isEqualTo("Category");
    assertThat(result.ranking().items().getFirst().sourceItemId()).isEqualTo("source-50");
    assertThat(result.ranking().items().getFirst().code()).isEqualTo("B");
    for (String key :
        List.of(
            "rankChange",
            "refundedOrderCount",
            "kolCount",
            "paidOrderCount",
            "units",
            "salesAmount",
            "completedRefundAmount",
            "netSalesAmount",
            "periodRefundedOrderRate")) {
      assertThat(service.query(Board.PRODUCT, FILTER, null, 1, 20, key, "asc").ranking().total())
          .isEqualTo(2);
    }
    assertThatThrownBy(
            () -> service.query(Board.PRODUCT, FILTER, null, 1, 20, "averageOrderAmount", null))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void creatorSocialSearchScopesRankAndTotalsAndOwnOptions() {
    var a = new KolRefDTO(11L, "K1", "Alice", true, 9L);
    var b = new KolRefDTO(12L, "K2", "Bettina", false, 9L);
    when(account.ownScope()).thenReturn(true);
    when(creators.listByBdUserId(9L)).thenReturn(List.of(a, b));
    when(creators.analyticsProfiles(any(), any()))
        .thenReturn(
            Map.of(
                11L,
                new KolApi.AnalyticsProfileDTO(List.of("creator-abc"), "TikTok", "PROMOTING")));
    when(current.effectivePermissions(account)).thenReturn(List.of("commission:view"));
    when(creators.listRatesByIds(any()))
        .thenReturn(List.of(new com.tcrm.admin.kol.api.KolRateDTO(11L, "0.1500")));
    when(inviters.listByIds(any())).thenReturn(List.of(new BdUserDTO(9L, "bd", null, true)));
    when(orders.rankFacts(any(), any(), any(), any()))
        .thenReturn(List.of(person(11, 2), person(12, 0)));
    when(orders.rankHighlights(any(), any(), any(), any()))
        .thenReturn(List.of(new HighlightDTO(11L, "Item", null)));
    var result = service.query(Board.CREATOR, FILTER, "ABC", 1, 20, "spuCount", "asc");
    assertThat(result.summary().activeCount()).isEqualTo(1);
    assertThat(result.ranking().items())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.name()).isEqualTo("Alice");
              assertThat(row.primaryPlatform()).isEqualTo("TikTok");
              assertThat(row.relationStatus()).isEqualTo("PROMOTING");
              assertThat(row.linkCount()).isEqualTo(1);
              assertThat(row.commissionRate()).isEqualByComparingTo("0.15");
              assertThat(row.highlights()).containsExactly("Item");
            });
    org.mockito.Mockito.verify(orders, org.mockito.Mockito.atLeastOnce())
        .totals(
            org.mockito.ArgumentMatchers.argThat(
                s -> s.ownerId().equals(9L) && s.matchedKolIds().equals(List.of(11L))),
            any(),
            any());
    assertThat(service.options().creators()).hasSize(2);
    for (String key : List.of("averageOrderAmount", "amountRefundRate", "commissionRate")) {
      var all = service.query(Board.CREATOR, FILTER, null, 1, 20, key, "desc");
      assertThat(all.ranking().items()).extracting(Row::id).containsExactly("11", "12");
    }
  }

  @Test
  void invitationConversionKeepsOriginalInviterAndCurrentAssignmentSeparate() {
    var a = new KolRefDTO(11L, "K1", "Alice", true, 10L);
    var b = new KolRefDTO(12L, "K2", "Bettina", false, 9L);
    var bd1 = new BdUserDTO(9L, "bd1", "First", true);
    var bd2 = new BdUserDTO(10L, "bd2", "Second", true);
    when(creators.listAll()).thenReturn(List.of(a, b));
    when(creators.listByIdsIncludingDeleted(any())).thenReturn(List.of(a, b));
    when(inviters.listUsable()).thenReturn(List.of(bd1, bd2));
    when(inviters.listByIds(any())).thenReturn(List.of(bd1, bd2));
    when(ownership.createdBetween(any(), any()))
        .thenReturn(
            List.of(
                new KolOwnershipDTO(11L, 10L, FROM, null),
                new KolOwnershipDTO(11L, 9L, FROM.minusSeconds(1), FROM),
                new KolOwnershipDTO(12L, null, FROM, null)));
    when(orders.rankFacts(any(), any(), any(), any()))
        .thenReturn(List.of(person(9, 1), person(10, 1), person(0, 1)));
    when(orders.rankHighlights(any(), any(), any(), any()))
        .thenReturn(List.of(new HighlightDTO(9L, null, 11L)));
    when(orders.sellingCreators(any(), any(), any())).thenReturn(1L);
    var result = service.query(Board.INVITER, FILTER, null, 1, 20, "invitedCount", "desc");
    assertThat(result.summary().invitedCount()).isEqualTo(1);
    assertThat(result.summary().effectiveCount()).isEqualTo(1);
    assertThat(result.summary().sellingCreatorCount()).isEqualTo(1);
    assertThat(result.summary().sellingCount()).isEqualTo(2);
    assertThat(result.ranking().items()).extracting(Row::id).containsExactly("9", "10", "0");
    assertThat(result.ranking().items().getFirst().highlights()).containsExactly("Alice");
    assertThat(
            service
                .query(Board.INVITER, FILTER, "bd2", 1, 20, "effectiveCount", "asc")
                .ranking()
                .items())
        .extracting(Row::id)
        .containsExactly("10");
    assertThat(service.options().inviters()).hasSize(2);
  }

  @Test
  void putsEarlierAchievementFirstInEitherDirection() {
    when(orders.rankFacts(any(), any(), any(), any()))
        .thenReturn(
            List.of(
                new RankFactsDTO(
                    11L, 1, 1, 0, 0, 0, ZERO, ZERO, ZERO, 0, FROM.plusSeconds(1), null, 0),
                new RankFactsDTO(12L, 1, 1, 0, 0, 0, ZERO, ZERO, ZERO, 0, FROM, null, 0)));
    var restricted = service.query(Board.CREATOR, FILTER, null, 1, 20, "commissionRate", "desc");
    assertThat(restricted.unavailable())
        .contains(
            new StatsOverviewService.UnavailableDTO("commissionRate", "COMMISSION_FORBIDDEN"));
    org.mockito.Mockito.verify(creators, org.mockito.Mockito.never()).listRatesByIds(any());
    for (String direction : List.of("asc", "desc")) {
      assertThat(
              service
                  .query(Board.CREATOR, FILTER, null, 1, 20, "netSalesAmount", direction)
                  .ranking()
                  .items())
          .extracting(Row::id)
          .containsExactly("12", "11");
    }
  }

  private static ProductFactsDTO product(Long shop, String spu, long orders, int amount) {
    return new ProductFactsDTO(
        shop,
        spu,
        spu,
        shop == null ? null : 50L,
        orders,
        orders,
        1,
        BigDecimal.valueOf(amount),
        ZERO,
        ZERO,
        1,
        1,
        DAY,
        FROM,
        null,
        FROM);
  }

  private static RankFactsDTO person(long id, long paid) {
    return new RankFactsDTO(
        id, paid, paid, 1, 1, 1, BigDecimal.valueOf(paid), ZERO, ZERO, 0, FROM, null, 1);
  }
}
