package com.tcrm.admin.order.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.order.service.IdleKolCleanupService;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Idle-KOL cleanup preview (task 25a review). Path stays under {@code /admin-api/kol};
 * orchestration lives in the order module so it can read attributed pay times without cross-module
 * SQL.
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
}
