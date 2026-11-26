package com.tcrm.admin.stats.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.order.api.OrderEvidenceApi.DetailSort;
import com.tcrm.admin.order.api.OrderEvidenceApi.Kind;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductBreakdown;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductGroup;
import com.tcrm.admin.order.api.OrderEvidenceApi.SettlementFlowRow;
import com.tcrm.admin.order.api.OrderStatsApi.CreatorIncomeOrder;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.stats.service.AnalyticsDetailService;
import com.tcrm.admin.stats.service.AnalyticsDetailService.Board;
import com.tcrm.admin.stats.service.AnalyticsDetailService.CreatorRow;
import com.tcrm.admin.stats.service.AnalyticsDetailService.Detail;
import com.tcrm.admin.stats.service.AnalyticsDetailService.SettlementLedger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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

  @GetMapping("/shop-performance/settlement-flows")
  @SaCheckPermission({"dashboard:shop", "order:view"})
  public R<PageResult<SettlementFlowRow>> shopSettlementFlows(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return R.ok(details.shopSettlementFlows(filters.toFilter(), page, size));
  }

  @GetMapping("/settlement-ledger")
  @SaCheckPermission("commission:view")
  public R<SettlementLedger> settlementLedger(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
      @RequestParam(required = false) @Positive Long shopId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return R.ok(details.settlementLedger(startDate, endDate, shopId, page, size));
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
            activity.size(),
            activity.sortBy(),
            activity.sortDirection()));
  }

  @GetMapping("/best-sellers/breakdowns")
  @SaCheckPermission("dashboard:shop")
  public R<PageResult<ProductGroup>> productBreakdown(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @RequestParam @Size(max = 64) String productCode,
      @RequestParam(required = false) @Size(max = 100) String keyword,
      @RequestParam ProductBreakdown group,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) DetailSort sortBy,
      @RequestParam(required = false) String sortDirection) {
    return R.ok(
        details.productBreakdown(
            filters.toFilter(),
            productCode,
            keyword == null || keyword.isBlank() ? null : keyword.strip(),
            group,
            page,
            size,
            sortBy,
            sortDirection));
  }

  @GetMapping("/kol-ranking/details")
  @SaCheckPermission("dashboard:kol")
  public R<Detail> creator(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute ActivityQuery activity,
      @RequestParam @PositiveOrZero Long entityId) {
    return query(Board.CREATOR, filters, entityId, null, activity);
  }

  @GetMapping("/creator-income/details")
  @SaCheckPermission("commission:view")
  public R<Detail> creatorIncome(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute ActivityQuery activity,
      @RequestParam @Positive Long entityId) {
    return activity.kind() == null
        ? R.ok(details.creatorIncome(filters.toFilter(), entityId))
        : query(Board.CREATOR, filters, entityId, null, activity);
  }

  @GetMapping("/creator-income/orders")
  @SaCheckPermission({"commission:view", "order:view"})
  public R<PageResult<CreatorIncomeOrder>> creatorIncomeOrders(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @RequestParam @Positive long entityId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) DetailSort sortBy,
      @RequestParam(required = false) String sortDirection) {
    return R.ok(
        details.creatorIncomeOrders(
            filters.toFilter(), entityId, page, size, sortBy, sortDirection));
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
      @RequestParam(required = false) Integer size,
      @RequestParam(defaultValue = "true") boolean currentTeam,
      @RequestParam(required = false) DetailSort sortBy,
      @RequestParam(required = false) String sortDirection) {
    return R.ok(
        details.inviterCreators(
            filters.toFilter(), entityId, page, size, currentTeam, sortBy, sortDirection));
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
            activity.size(),
            activity.sortBy(),
            activity.sortDirection()));
  }

  public record ActivityQuery(
      Kind kind, Integer page, Integer size, DetailSort sortBy, String sortDirection) {}
}
