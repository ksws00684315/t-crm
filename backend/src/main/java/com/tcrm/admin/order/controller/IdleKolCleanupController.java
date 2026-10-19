package com.tcrm.admin.order.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.order.service.IdleKolCleanupService;
import com.tcrm.admin.product.api.KolCleanupItemApi.KolCleanupItemDTO;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Idle-KOL cleanup preview, manual cleanup and cleanup-item APIs (task 25a / 29). Paths stay under
 * {@code /admin-api/kol}; orchestration lives in the order module.
 */
@RestController
@RequestMapping("/admin-api/kol")
public class IdleKolCleanupController {
  private final IdleKolCleanupService cleanup;

  public IdleKolCleanupController(IdleKolCleanupService cleanup) {
    this.cleanup = cleanup;
  }

  @GetMapping("/cleanup-preview")
  @SaCheckPermission("kol:edit")
  public R<PageResult<CleanupPreviewVO>> cleanupPreview(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return R.ok(cleanup.preview(page, size).map(CleanupPreviewVO::from));
  }

  @PostMapping("/kols/{id}/cleanup")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Manual KOL cleanup")
  public R<Void> cleanupManual(
      @PathVariable @Min(1) Long id, @Valid @RequestBody CleanupKolRequest r) {
    cleanup.cleanupManual(id, r.reason());
    return R.ok(null);
  }

  @GetMapping("/cleanup-items")
  @SaCheckPermission("kol:edit")
  public R<PageResult<CleanupItemVO>> cleanupItems(
      @RequestParam(required = false) String kolCode,
      @RequestParam(required = false) @Pattern(regexp = "PENDING|SUCCESS|FAILED") String status,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return R.ok(cleanup.pageCleanupItems(kolCode, status, page, size).map(CleanupItemVO::from));
  }

  @PostMapping("/cleanup-items/{id}/retry")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Retry cleanup item delete")
  public R<Void> retryCleanupItem(@PathVariable @Min(1) Long id) {
    cleanup.retryCleanupItem(id);
    return R.ok(null);
  }

  public record CleanupPreviewVO(
      Long id,
      String kolCode,
      String name,
      String bdUserName,
      Instant lastOrderAt,
      Instant idleSince) {
    static CleanupPreviewVO from(IdleKolCleanupService.CleanupPreviewDTO d) {
      return new CleanupPreviewVO(
          d.id(), d.kolCode(), d.name(), d.bdUserName(), d.lastOrderAt(), d.idleSince());
    }
  }

  public record CleanupItemVO(
      Long id,
      Long kolId,
      String kolCode,
      Long kolItemId,
      Long shopId,
      String shopName,
      String weidianItemId,
      String status,
      String trigger,
      String reason,
      String failReason,
      Instant cleanupTime,
      Instant finishTime) {
    static CleanupItemVO from(KolCleanupItemDTO d) {
      return new CleanupItemVO(
          d.id(),
          d.kolId(),
          d.kolCode(),
          d.kolItemId(),
          d.shopId(),
          d.shopName(),
          d.weidianItemId(),
          d.status(),
          d.trigger(),
          d.reason(),
          d.failReason(),
          d.cleanupTime(),
          d.finishTime());
    }
  }
}
