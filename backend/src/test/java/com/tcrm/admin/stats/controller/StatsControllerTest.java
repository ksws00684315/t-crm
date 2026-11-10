package com.tcrm.admin.stats.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tcrm.admin.stats.service.StatsOverviewService;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StatsControllerTest {
  @Test
  void forwardsBoundOverviewFilters() {
    var service = mock(StatsOverviewService.class);
    var controller = new StatsController(service);
    var date = LocalDate.of(2026, 11, 3);
    controller.overview(new StatsOverviewQuery(date, date, 1L, 2L, 3L, "WEIDIAN"));
    verify(service).overview(new FilterDTO(date, date, 1L, 2L, 3L, "WEIDIAN"));
  }
}
