package com.tcrm.admin.order.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.order.service.IdleKolCleanupService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manual KOL cleanup; cleanup details remain internal for the background Weidian delete worker. */
@RestController
@RequestMapping("/admin-api/kol/kols")
public class IdleKolCleanupController {
  private final IdleKolCleanupService cleanup;

  public IdleKolCleanupController(IdleKolCleanupService cleanup) {
    this.cleanup = cleanup;
  }

  @GetMapping("/cleanup-remaining")
  @SaCheckPermission("kol:view")
  public R<Map<Long, Integer>> remaining(@RequestParam List<Long> ids) {
    return R.ok(cleanup.remainingDays(ids));
  }

  @PostMapping("/{id}/cleanup")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Manual KOL cleanup")
  public R<Void> cleanupManual(
      @PathVariable @Min(1) Long id, @Valid @RequestBody CleanupKolRequest request) {
    cleanup.cleanupManual(id, request.reason());
    return R.ok(null);
  }
}
