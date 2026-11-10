package com.tcrm.admin.stats.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.stats.service.StatsOverviewService;
import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import com.tcrm.admin.stats.service.StatsOverviewService.OverviewDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Management statistics endpoints. */
@RestController
@RequestMapping("/admin-api/stats")
public class StatsController {
  private final StatsOverviewService stats;

  public StatsController(StatsOverviewService stats) {
    this.stats = stats;
  }

  @GetMapping("/overview")
  @SaCheckPermission(
      value = {"dashboard:shop", "dashboard:team"},
      mode = SaMode.OR)
  public R<OverviewDTO> overview(@Valid @ModelAttribute StatsOverviewQuery query) {
    return R.ok(
        stats.overview(
            new FilterDTO(
                query.startDate(),
                query.endDate(),
                query.shopId(),
                query.kolId(),
                query.inviterId(),
                query.channel())));
  }
}
