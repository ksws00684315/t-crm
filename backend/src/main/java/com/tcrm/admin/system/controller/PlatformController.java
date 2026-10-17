package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.service.PlatformService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform administrators manage companies. Every other role is rejected. */
@RestController
@RequestMapping("/admin-api/platform/tenants")
@SaCheckRole("PLATFORM_ADMIN")
public class PlatformController {
  private final PlatformService platform;

  public PlatformController(PlatformService platform) {
    this.platform = platform;
  }

  @GetMapping
  public R<List<TenantVO>> list() {
    return R.ok(platform.list().stream().map(TenantVO::from).toList());
  }

  @PostMapping
  @AuditLog(module = "Platform", action = "Create tenant")
  public R<TenantVO> create(@Valid @RequestBody CreateTenantRequest r) {
    return R.ok(TenantVO.from(platform.create(r.tenantCode(), r.name(), r.remark())));
  }

  @PutMapping("/{id}")
  @AuditLog(module = "Platform", action = "Edit tenant")
  public R<Void> edit(@PathVariable @Min(1) Long id, @Valid @RequestBody EditTenantRequest r) {
    platform.update(id, r.name(), r.remark());
    return R.ok(null);
  }

  @PutMapping("/{id}/status")
  @AuditLog(module = "Platform", action = "Change tenant status")
  public R<Void> status(@PathVariable @Min(1) Long id, @Valid @RequestBody StatusRequest r) {
    platform.updateStatus(id, r.status());
    return R.ok(null);
  }

  @GetMapping("/{id}/admins")
  public R<List<TenantAdminVO>> admins(@PathVariable @Min(1) Long id) {
    return R.ok(platform.listAdmins(id).stream().map(TenantAdminVO::from).toList());
  }

  @PostMapping("/{id}/admins")
  @AuditLog(module = "Platform", action = "Create tenant administrator")
  public R<TenantAdminVO> createAdmin(
      @PathVariable @Min(1) Long id, @Valid @RequestBody CreateTenantAdminRequest r) {
    return R.ok(
        TenantAdminVO.from(
            platform.createAdmin(id, r.username(), r.password(), r.nickname(), r.email())));
  }

  /** Adds an existing account to this company; 409 USER_ALREADY_MEMBER when it already is one. */
  @PostMapping("/{id}/members")
  @AuditLog(module = "Platform", action = "Add tenant member")
  public R<PlatformUserVO> addMember(
      @PathVariable @Min(1) Long id, @Valid @RequestBody AddMemberRequest r) {
    return R.ok(
        PlatformUserVO.from(
            platform.addMember(id, r.userId(), r.role(), r.postTemplate(), r.dataScope())));
  }

  @PutMapping("/{id}/admins/{userId}/password")
  @AuditLog(module = "Platform", action = "Reset tenant administrator password")
  public R<Void> resetPassword(
      @PathVariable @Min(1) Long id,
      @PathVariable @Min(1) Long userId,
      @Valid @RequestBody ResetPasswordRequest r) {
    platform.resetAdminPassword(id, userId, r.password());
    return R.ok(null);
  }
}
