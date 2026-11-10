package com.tcrm.admin.stats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.stats.service.AnalyticsRankingService;
import com.tcrm.admin.stats.service.AnalyticsRankingService.Board;
import com.tcrm.admin.stats.service.AnalyticsRankingService.Options;
import com.tcrm.admin.stats.service.AnalyticsRankingService.Result;
import com.tcrm.admin.stats.service.ShopPerformanceExport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class AnalyticsRankControllerTest {
  @Test
  void listAndExportUseIdenticalNormalizedQuery() {
    var service = mock(AnalyticsRankingService.class);
    var export = mock(ShopPerformanceExport.class);
    var controller = new AnalyticsRankController(service, export);
    var filter = new StatsOverviewQuery(null, null, null, null, null, null);
    var ranking = new RankingQuery(2, 50, "units", "asc");
    var options = new Options(List.of(), List.of(), List.of());
    when(service.options()).thenReturn(options);
    assertThat(controller.options().data()).isSameAs(options);
    for (Board board : Board.values()) {
      String filename =
          switch (board) {
            case PRODUCT -> "best-sellers";
            case CREATOR -> "kol-ranking";
            case INVITER -> "inviter-performance";
          };
      var result =
          new Result(
              null,
              null,
              null,
              null,
              new PageResult<>(List.of(), 0, 2, 50),
              List.of(),
              "units",
              "asc",
              "item");
      when(service.query(board, filter.toFilter(), "item", 2, 50, "units", "asc"))
          .thenReturn(result);
      when(export.currentPage(board, result)).thenReturn(new byte[] {1, 2});
      var listed =
          switch (board) {
            case PRODUCT -> controller.product(filter, ranking, " item ");
            case CREATOR -> controller.creator(filter, ranking, " item ");
            case INVITER -> controller.inviter(filter, ranking, " item ");
          };
      ResponseEntity<byte[]> file =
          switch (board) {
            case PRODUCT -> controller.productExport(filter, ranking, "item");
            case CREATOR -> controller.creatorExport(filter, ranking, "item");
            case INVITER -> controller.inviterExport(filter, ranking, "item");
          };
      assertThat(listed.data()).isSameAs(result);
      assertThat(file.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
          .contains(filename + ".xlsx");
      assertThat(file.getBody()).containsExactly(1, 2);
    }
    controller.product(filter, ranking, null);
    controller.product(filter, ranking, " ");
    org.mockito.Mockito.verify(service, org.mockito.Mockito.times(2))
        .query(Board.PRODUCT, filter.toFilter(), null, 2, 50, "units", "asc");
  }
}
