package com.tcrm.admin.stats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.stats.service.ShopPerformanceExport;
import com.tcrm.admin.stats.service.ShopPerformanceService;
import com.tcrm.admin.stats.service.ShopPerformanceService.ShopPerformanceDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class ShopPerformanceControllerTest {
  @Test
  void bothRoutesReuseTheSameAuthorizedQueryAndReturnWorkbook() {
    var service = mock(ShopPerformanceService.class);
    var export = mock(ShopPerformanceExport.class);
    var controller = new ShopPerformanceController(service, export);
    var filter = new StatsOverviewQuery(null, null, null, null, null, null);
    var ranking = new RankingQuery(2, 50, "units", "asc");
    var result =
        new ShopPerformanceDTO(
            null,
            null,
            null,
            null,
            new PageResult<>(List.of(), 0, 2, 50),
            List.of(),
            "units",
            "asc");
    when(service.query(new FilterDTO(null, null, null, null, null, null), 2, 50, "units", "asc"))
        .thenReturn(result);
    when(export.currentPage(result)).thenReturn(new byte[] {1, 2});
    assertThat(controller.list(filter, ranking).data()).isSameAs(result);
    var response = controller.export(filter, ranking);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .contains("shop-performance.xlsx");
    assertThat(response.getBody()).containsExactly(1, 2);
  }
}
