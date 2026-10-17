package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.service.PlatformService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform administrators manage accounts across companies (ADR-022): exact-username lookup, name
 * and e-mail, password reset, account-wide status. Every other role is rejected. Missing 404.
 */
@RestController
@RequestMapping("/admin-api/platform/users")
@SaCheckRole("PLATFORM_ADMIN")
public class PlatformUserController {
  private final PlatformService platform;

  public PlatformUserController(PlatformService platform) {
    this.platform = platform;
  }

  /** Exact, case-insensitive username; {@code data} is null when no account has it. */
  @GetMapping
  public R<PlatformUserVO> find(@RequestParam(defaultValue = "") String username) {
    return R.ok(PlatformUserVO.from(platform.findUser(username)));
  }

  @PutMapping("/{userId}")
  @AuditLog(module = "Platform", action = "Edit account")
  public R<Void> edit(@PathVariable @Min(1) Long userId, @Valid @RequestBody EditAccountRequest r) {
    platform.updateUser(userId, r.nickname(), r.email());
    return R.ok(null);
  }

  @PutMapping("/{userId}/password")
  @AuditLog(module = "Platform", action = "Reset account password")
  public R<Void> password(
      @PathVariable @Min(1) Long userId, @Valid @RequestBody ResetPasswordRequest r) {
    platform.resetPassword(userId, r.password());
    return R.ok(null);
  }

  @PutMapping("/{userId}/status")
  @AuditLog(module = "Platform", action = "Change account status")
  public R<Void> status(@PathVariable @Min(1) Long userId, @Valid @RequestBody StatusRequest r) {
    platform.updateUserStatus(userId, r.status());
    return R.ok(null);
  }
}
