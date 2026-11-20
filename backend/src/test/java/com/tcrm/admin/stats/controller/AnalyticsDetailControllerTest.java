package com.tcrm.admin.stats.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tcrm.admin.order.api.OrderEvidenceApi.DetailSort;
import com.tcrm.admin.order.api.OrderEvidenceApi.Kind;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductBreakdown;
import com.tcrm.admin.stats.controller.AnalyticsDetailController.ActivityQuery;
import com.tcrm.admin.stats.service.AnalyticsDetailService;
import com.tcrm.admin.stats.service.AnalyticsDetailService.Board;
import org.junit.jupiter.api.Test;

class AnalyticsDetailControllerTest {
  private final AnalyticsDetailService service = mock(AnalyticsDetailService.class);
  private final AnalyticsDetailController controller = new AnalyticsDetailController(service);
  private final StatsOverviewQuery filters =
      new StatsOverviewQuery(null, null, null, null, null, null, null, null);

  @Test
  void normalizesProductOrderAndBreakdownSearchAndForwardsPagingAndSort() {
    var page = new ActivityQuery(Kind.ORDERS, 2, 50, DetailSort.REFUND_AMOUNT, "asc");
    for (String keyword : new String[] {null, " ", " sample "}) {
      controller.product(filters, page, "product:20", keyword);
      controller.productBreakdown(
          filters,
          "product:20",
          keyword,
          ProductBreakdown.CREATORS,
          2,
          50,
          DetailSort.SALES_AMOUNT,
          "desc");
    }
    for (String normalized : new String[] {null, "sample"}) {
      int calls = normalized == null ? 2 : 1;
      verify(service, times(calls))
          .detail(
              Board.PRODUCT,
              filters.toFilter(),
              null,
              "product:20",
              normalized,
              Kind.ORDERS,
              2,
              50,
              DetailSort.REFUND_AMOUNT,
              "asc");
      verify(service, times(calls))
          .productBreakdown(
              filters.toFilter(),
              "product:20",
              normalized,
              ProductBreakdown.CREATORS,
              2,
              50,
              DetailSort.SALES_AMOUNT,
              "desc");
    }
  }

  @Test
  void forwardsTheCorrectScopeForShopCreatorAndInviterDetails() {
    var page = new ActivityQuery(Kind.REFUNDS, 3, 100, DetailSort.REFUND_AMOUNT, "desc");
    controller.shop(filters, page);
    controller.creator(filters, page, 11L);
    controller.inviter(filters, page, 9L);
    verify(service)
        .detail(
            Board.SHOP,
            filters.toFilter(),
            null,
            null,
            null,
            Kind.REFUNDS,
            3,
            100,
            DetailSort.REFUND_AMOUNT,
            "desc");
    verify(service)
        .detail(
            Board.CREATOR,
            filters.toFilter(),
            11L,
            null,
            null,
            Kind.REFUNDS,
            3,
            100,
            DetailSort.REFUND_AMOUNT,
            "desc");
    verify(service)
        .detail(
            Board.INVITER,
            filters.toFilter(),
            9L,
            null,
            null,
            Kind.REFUNDS,
            3,
            100,
            DetailSort.REFUND_AMOUNT,
            "desc");
  }
}
