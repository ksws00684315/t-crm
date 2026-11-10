package com.tcrm.admin.stats.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.stats.service.ShopPerformanceExport;
import com.tcrm.admin.stats.service.ShopPerformanceService;
import com.tcrm.admin.stats.service.ShopPerformanceService.ShopPerformanceDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reference ranking and current-page export for the shared dashboard contract. */
@RestController
@RequestMapping("/admin-api/stats/shop-performance")
public class ShopPerformanceController {
  private final ShopPerformanceService shops;
  private final ShopPerformanceExport export;

  public ShopPerformanceController(ShopPerformanceService shops, ShopPerformanceExport export) {
    this.shops = shops;
    this.export = export;
  }

  @GetMapping
  @SaCheckPermission("dashboard:shop")
  public R<ShopPerformanceDTO> list(
      @Valid @ModelAttribute StatsOverviewQuery filters, @ModelAttribute RankingQuery ranking) {
    return R.ok(query(filters, ranking));
  }

  @GetMapping("/exports")
  @SaCheckPermission({"dashboard:shop", "data:export"})
  @AuditLog(module = "STATS", action = "Export current shop performance page")
  public ResponseEntity<byte[]> export(
      @Valid @ModelAttribute StatsOverviewQuery filters, @ModelAttribute RankingQuery ranking) {
    byte[] workbook = export.currentPage(query(filters, ranking));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(ImportTaskApi.XLSX_CONTENT_TYPE))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("shop-performance.xlsx").build().toString())
        .body(workbook);
  }

  private ShopPerformanceDTO query(StatsOverviewQuery filters, RankingQuery ranking) {
    return shops.query(
        filters.toFilter(),
        ranking.page(),
        ranking.size(),
        ranking.sortBy(),
        ranking.sortDirection());
  }
}
