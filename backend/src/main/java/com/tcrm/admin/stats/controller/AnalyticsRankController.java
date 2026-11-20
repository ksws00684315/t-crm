package com.tcrm.admin.stats.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.stats.service.AnalyticsRankingService;
import com.tcrm.admin.stats.service.AnalyticsRankingService.Board;
import com.tcrm.admin.stats.service.AnalyticsRankingService.Options;
import com.tcrm.admin.stats.service.AnalyticsRankingService.Result;
import com.tcrm.admin.stats.service.ShopPerformanceExport;
import com.tcrm.admin.task.api.ImportTaskApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin-api/stats")
public class AnalyticsRankController {
  private final AnalyticsRankingService rankings;
  private final ShopPerformanceExport export;

  public AnalyticsRankController(AnalyticsRankingService rankings, ShopPerformanceExport export) {
    this.rankings = rankings;
    this.export = export;
  }

  @GetMapping("/best-sellers")
  @SaCheckPermission("dashboard:shop")
  public R<Result> product(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute RankingQuery rank,
      @RequestParam(required = false) @Size(max = 100) String keyword) {
    return R.ok(query(Board.PRODUCT, filters, rank, keyword));
  }

  @GetMapping("/best-sellers/exports")
  @SaCheckPermission({"dashboard:shop", "data:export"})
  @AuditLog(module = "STATS", action = "Export current best-sellers page")
  public ResponseEntity<byte[]> productExport(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute RankingQuery rank,
      @RequestParam(required = false) @Size(max = 100) String keyword) {
    return response("best-sellers", Board.PRODUCT, query(Board.PRODUCT, filters, rank, keyword));
  }

  @GetMapping("/kol-ranking")
  @SaCheckPermission("dashboard:kol")
  public R<Result> creator(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute RankingQuery rank,
      @RequestParam(required = false) @Size(max = 100) String keyword) {
    return R.ok(query(Board.CREATOR, filters, rank, keyword));
  }

  @GetMapping("/kol-ranking/exports")
  @SaCheckPermission({"dashboard:kol", "data:export"})
  @AuditLog(module = "STATS", action = "Export current kol-ranking page")
  public ResponseEntity<byte[]> creatorExport(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute RankingQuery rank,
      @RequestParam(required = false) @Size(max = 100) String keyword) {
    return response("kol-ranking", Board.CREATOR, query(Board.CREATOR, filters, rank, keyword));
  }

  @GetMapping("/inviter-performance")
  @SaCheckPermission("dashboard:team")
  public R<Result> inviter(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute RankingQuery rank,
      @RequestParam(required = false) @Size(max = 100) String keyword) {
    return R.ok(query(Board.INVITER, filters, rank, keyword));
  }

  @GetMapping("/inviter-performance/exports")
  @SaCheckPermission({"dashboard:team", "data:export"})
  @AuditLog(module = "STATS", action = "Export current inviter-performance page")
  public ResponseEntity<byte[]> inviterExport(
      @Valid @ModelAttribute StatsOverviewQuery filters,
      @ModelAttribute RankingQuery rank,
      @RequestParam(required = false) @Size(max = 100) String keyword) {
    return response(
        "inviter-performance", Board.INVITER, query(Board.INVITER, filters, rank, keyword));
  }

  @GetMapping("/options")
  @SaCheckPermission(
      value = {"dashboard:shop", "dashboard:kol", "dashboard:team"},
      mode = SaMode.OR)
  public R<Options> options() {
    return R.ok(rankings.options());
  }

  private Result query(Board board, StatsOverviewQuery filters, RankingQuery rank, String keyword) {
    return rankings.query(
        board,
        filters.toFilter(),
        keyword == null || keyword.isBlank() ? null : keyword.strip(),
        rank.page(),
        rank.size(),
        rank.sortBy(),
        rank.sortDirection());
  }

  private ResponseEntity<byte[]> response(String filename, Board board, Result result) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(ImportTaskApi.XLSX_CONTENT_TYPE))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename + ".xlsx").build().toString())
        .body(export.currentPage(board, result));
  }
}
