package com.tcrm.admin.kol.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.kol.service.KolClaimService;
import com.tcrm.admin.kol.service.KolCleanupService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KOL claim search and idle-cleanup config (task 25a / ADR-023). Cleanup preview and the scheduled
 * job live in the order module (architecture review).
 */
@RestController
@RequestMapping("/admin-api/kol")
public class KolOpsController {
  private final KolClaimService claims;
  private final KolCleanupService cleanup;

  public KolOpsController(KolClaimService claims, KolCleanupService cleanup) {
    this.claims = claims;
    this.cleanup = cleanup;
  }

  @GetMapping("/claimable")
  @SaCheckPermission("kol:edit")
  public R<ClaimableKolVO> claimable(@RequestParam @NotBlank String keyword) {
    var found = claims.findClaimable(keyword);
    return R.ok(found == null ? null : ClaimableKolVO.from(found));
  }

  @GetMapping("/cleanup-config")
  @SaCheckPermission("kol:edit")
  public R<CleanupConfigVO> cleanupConfig() {
    return R.ok(CleanupConfigVO.from(cleanup.getConfig()));
  }

  @PutMapping("/cleanup-config")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Save cleanup config")
  public R<Void> saveCleanupConfig(@Valid @RequestBody CleanupConfigRequest r) {
    cleanup.saveConfig(Boolean.TRUE.equals(r.enabled()), r.idleDays());
    return R.ok(null);
  }

  public record ClaimableKolVO(
      Long id, String kolCode, String name, String maskedEmail, String displayStatus) {
    static ClaimableKolVO from(KolClaimService.ClaimableKolDTO d) {
      return new ClaimableKolVO(d.id(), d.kolCode(), d.name(), d.maskedEmail(), d.displayStatus());
    }
  }

  public record CleanupConfigVO(boolean enabled, int idleDays) {
    static CleanupConfigVO from(KolCleanupService.CleanupConfigDTO d) {
      return new CleanupConfigVO(d.enabled(), d.idleDays());
    }
  }

  public record CleanupConfigRequest(
      @NotNull Boolean enabled, @NotNull @Min(7) @Max(365) Integer idleDays) {}
}
