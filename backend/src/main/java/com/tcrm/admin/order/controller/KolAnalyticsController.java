package com.tcrm.admin.order.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.order.service.KolAnalyticsOverviewDTO;
import com.tcrm.admin.order.service.KolAnalyticsService;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator workbench overview (task 64): sales and paid-order trend over a caller-supplied range.
 * Commission amounts are provided by the commission module (task 62) and read zero until then.
 */
@RestController
public class KolAnalyticsController {
  private final KolAnalyticsService analytics;

  public KolAnalyticsController(KolAnalyticsService analytics) {
    this.analytics = analytics;
  }

  /** Requires a KOL token; identity and company scope come from the session. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/analytics/overview")
  public R<KolAnalyticsOverviewDTO> overview(
      @RequestParam("from") String from, @RequestParam("to") String to) {
    return R.ok(analytics.overview(from, to));
  }
}
