package com.tcrm.admin.stats.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.commission.api.CreatorFundFlowApi;
import com.tcrm.admin.commission.api.CreatorFundFlowApi.Application;
import com.tcrm.admin.commission.api.CreatorFundFlowApi.FundFlow;
import com.tcrm.admin.commission.api.CreatorFundFlowApi.Query;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.stats.service.StatsPeriod;
import com.tcrm.admin.stats.service.StatsRanking;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CreatorFundFlowController {
  private final CreatorFundFlowApi flows;
  private final CurrentUserContext current;

  public CreatorFundFlowController(CreatorFundFlowApi flows, CurrentUserContext current) {
    this.flows = flows;
    this.current = current;
  }

  @GetMapping("/admin-api/stats/creator-income/fund-flows")
  @SaCheckPermission("commission:view")
  public R<PageResult<FundFlow>> list(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @RequestParam @Positive long entityId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return R.ok(flows.list(current.user(), query(filters, entityId, page, size)));
  }

  @GetMapping("/admin-api/stats/creator-income/fund-applications")
  @SaCheckPermission("commission:view")
  public R<PageResult<Application>> applications(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @RequestParam @Positive long entityId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return R.ok(flows.applications(current.user(), query(filters, entityId, page, size)));
  }

  private static Query query(
      StatsOverviewQuery filters, long entityId, Integer page, Integer size) {
    var period =
        StatsPeriod.resolve(
            filters.startDate(), filters.endDate(), Boolean.TRUE.equals(filters.cumulative()));
    var paging = StatsRanking.resolve(page, size, null);
    return new Query(
        entityId, filters.shopId(), period.from(), period.until(), paging.page(), paging.size());
  }
}
