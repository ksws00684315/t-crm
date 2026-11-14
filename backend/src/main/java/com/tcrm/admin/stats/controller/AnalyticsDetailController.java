package com.tcrm.admin.stats.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.order.api.OrderEvidenceApi.Kind;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductBreakdown;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductGroup;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.stats.service.AnalyticsDetailService;
import com.tcrm.admin.stats.service.AnalyticsDetailService.Board;
import com.tcrm.admin.stats.service.AnalyticsDetailService.CreatorRow;
import com.tcrm.admin.stats.service.AnalyticsDetailService.Detail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin-api/stats")
public class AnalyticsDetailController {
  private final AnalyticsDetailService details;

  public AnalyticsDetailController(AnalyticsDetailService details) {
    this.details = details;
  }

  @GetMapping("/shop-performance/details")
  @SaCheckPermission("dashboard:shop")
  public R<Detail> shop(
      @Valid @ModelAttribute StatsOverviewQuery filters, @ModelAttribute ActivityQuery activity) {
    return query(Board.SHOP, filters, null, null, activity);
  }

  @GetMapping("/best-sellers/details")
  @SaCheckPermission("dashboard:shop")
  public R<Detail> product(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute ActivityQuery activity,
      @RequestParam @Size(max = 64) String productCode,
      @RequestParam(required = false) @Size(max = 100) String keyword) {
    return R.ok(
        details.detail(
            Board.PRODUCT,
            filters.toFilter(),
            null,
            productCode,
            keyword == null || keyword.isBlank() ? null : keyword.strip(),
            activity.kind(),
            activity.page(),
            activity.size()));
  }

  @GetMapping("/best-sellers/breakdowns")
  @SaCheckPermission("dashboard:shop")
  public R<PageResult<ProductGroup>> productBreakdown(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @RequestParam @Size(max = 64) String productCode,
      @RequestParam(required = false) @Size(max = 100) String keyword,
      @RequestParam ProductBreakdown group,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return R.ok(
        details.productBreakdown(
            filters.toFilter(),
            productCode,
            keyword == null || keyword.isBlank() ? null : keyword.strip(),
            group,
            page,
            size));
  }

  @GetMapping("/kol-ranking/details")
  @SaCheckPermission("dashboard:kol")
  public R<Detail> creator(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute ActivityQuery activity,
      @RequestParam @PositiveOrZero Long entityId) {
    return query(Board.CREATOR, filters, entityId, null, activity);
  }

  @GetMapping("/inviter-performance/details")
  @SaCheckPermission("dashboard:team")
  public R<Detail> inviter(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute ActivityQuery activity,
      @RequestParam @PositiveOrZero Long entityId) {
    return query(Board.INVITER, filters, entityId, null, activity);
  }

  @GetMapping("/inviter-performance/creators")
  @SaCheckPermission("dashboard:team")
  public R<PageResult<CreatorRow>> inviterCreators(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @RequestParam @Positive long entityId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return R.ok(details.inviterCreators(filters.toFilter(), entityId, page, size));
  }

  private R<Detail> query(
      Board board,
      StatsOverviewQuery filters,
      Long entityId,
      String productCode,
      ActivityQuery activity) {
    return R.ok(
        details.detail(
            board,
            filters.toFilter(),
            entityId,
            productCode,
            null,
            activity.kind(),
            activity.page(),
            activity.size()));
  }

  public record ActivityQuery(Kind kind, Integer page, Integer size) {}
}
