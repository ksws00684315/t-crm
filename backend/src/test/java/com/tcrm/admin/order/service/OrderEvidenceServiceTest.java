package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.order.api.OrderEvidenceApi.DailyAmount;
import com.tcrm.admin.order.api.OrderEvidenceApi.DetailSort;
import com.tcrm.admin.order.api.OrderEvidenceApi.EvidenceRow;
import com.tcrm.admin.order.api.OrderEvidenceApi.Kind;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductBreakdown;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductGroup;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductOrderCounts;
import com.tcrm.admin.order.api.OrderEvidenceApi.Selection;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.order.mapper.OrderEvidenceMapper;
import com.tcrm.admin.order.mapper.OrderSettlementFlowMapper;
import com.tcrm.admin.shared.PageResult;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderEvidenceServiceTest {

  private static final Selection SELECTION =
      new Selection(
          new ScopeDTO(1, null, null, null, null, List.of(10L), null, null),
          null,
          null,
          null,
          null,
          null,
          null);

  private final OrderEvidenceMapper evidence = mock(OrderEvidenceMapper.class);
  private final OrderEvidenceService service =
      new OrderEvidenceService(evidence, mock(OrderSettlementFlowMapper.class));

  @Test
  void returnsEmptyEvidenceWithoutReadingShopsOutsideScope() {
    var selection =
        new Selection(
            SELECTION.scope().withVisibleShops(List.of()), null, null, null, null, null, null);

    assertThat(service.page(selection, Kind.ORDERS, 1, 10))
        .isEqualTo(new PageResult<EvidenceRow>(List.of(), 0, 1, 10));
    assertThat(service.productGroups(selection, ProductBreakdown.SKUS, 1, 10))
        .isEqualTo(new PageResult<ProductGroup>(List.of(), 0, 1, 10));
    assertThat(service.productOrderCounts(selection)).isEqualTo(new ProductOrderCounts(0, 0));
    assertThat(service.daily(selection)).isEmpty();
    verifyNoInteractions(evidence);
  }

  @ParameterizedTest
  @CsvSource({"1,0,0,0", "2,1,0,3", "2,0,1,1", "1,2,5,5"})
  void reportsAccurateTotalsForRefundPages(int page, int rowCount, long count, long total) {
    long offset = (page - 1L) * 2;
    var rows = Collections.nCopies(rowCount, mock(EvidenceRow.class));
    when(evidence.pageWithSort(SELECTION, Kind.REFUNDS, 2, offset, null, false)).thenReturn(rows);
    when(evidence.count(SELECTION, Kind.REFUNDS)).thenReturn(count);

    assertThat(service.page(SELECTION, Kind.REFUNDS, page, 2))
        .isEqualTo(new PageResult<>(rows, total, page, 2));
  }

  @Test
  void countsGroupedOrdersEvenWhenASortedPageIsShort() {
    var grouped = SELECTION.groupedByOrder();
    var rows = List.of(mock(EvidenceRow.class));
    when(evidence.pageWithSort(grouped, Kind.ORDERS, 10, 0, DetailSort.SALES_AMOUNT, true))
        .thenReturn(rows);
    when(evidence.count(grouped, Kind.ORDERS)).thenReturn(12L);

    assertThat(service.sortedActivity(grouped, Kind.ORDERS, 1, 10, DetailSort.SALES_AMOUNT, true))
        .isEqualTo(new PageResult<>(rows, 12, 1, 10));
  }

  @ParameterizedTest
  @CsvSource({"1,0,0,0", "2,1,0,3", "2,0,1,1", "1,2,5,5"})
  void reportsAccurateTotalsForSortedProductGroups(int page, int rowCount, long count, long total) {
    long offset = (page - 1L) * 2;
    var rows = Collections.nCopies(rowCount, mock(ProductGroup.class));
    var group = ProductBreakdown.SKUS;
    var sort = DetailSort.NET_SALES_AMOUNT;
    when(evidence.productGroupsWithSort(SELECTION, group, 2, offset, sort, false)).thenReturn(rows);
    when(evidence.countProductGroups(SELECTION, group)).thenReturn(count);

    assertThat(service.sortedProductGroups(SELECTION, group, page, 2, sort, false))
        .isEqualTo(new PageResult<>(rows, total, page, 2));
  }

  @Test
  void returnsProductCountsAndDailyAmountsForVisibleShops() {
    var counts = new ProductOrderCounts(8, 2);
    var daily = List.of(mock(DailyAmount.class));
    when(evidence.productOrderCounts(SELECTION)).thenReturn(counts);
    when(evidence.daily(SELECTION)).thenReturn(daily);

    assertThat(service.productOrderCounts(SELECTION)).isEqualTo(counts);
    assertThat(service.daily(SELECTION)).isEqualTo(daily);
  }
}
