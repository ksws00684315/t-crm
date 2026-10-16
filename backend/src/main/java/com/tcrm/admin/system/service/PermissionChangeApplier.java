package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import com.tcrm.admin.system.model.SysUserTenantDO;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Writes one permission replacement. A separate bean so {@link AuditLog} sees the before and after
 * sets as method arguments; a self-call would skip the aspect.
 */
@Service
public class PermissionChangeApplier {
  private final SysUserTenantMapper members;
  private final SysUserPermissionMapper permissions;
  private final CurrentUserContext current;

  public PermissionChangeApplier(
      SysUserTenantMapper members,
      SysUserPermissionMapper permissions,
      CurrentUserContext current) {
    this.members = members;
    this.permissions = permissions;
    this.current = current;
  }

  /** Writes the membership's data scope and codes in {@code change.tenantId()} only. */
  @AuditLog(module = "User", action = "Replace permissions")
  public void apply(PermissionChange change) {
    members.update(
        null,
        new LambdaUpdateWrapper<SysUserTenantDO>()
            .eq(SysUserTenantDO::getUserId, change.userId())
            .eq(SysUserTenantDO::getTenantId, change.tenantId())
            .set(SysUserTenantDO::getDataScope, change.dataScope())
            .set(SysUserTenantDO::getUpdateBy, current.id())
            .set(SysUserTenantDO::getUpdateTime, Instant.now()));
    permissions.delete(
        new LambdaQueryWrapper<SysUserPermissionDO>()
            .eq(SysUserPermissionDO::getUserId, change.userId())
            .eq(SysUserPermissionDO::getTenantId, change.tenantId()));
    for (String code : change.permissions()) {
      SysUserPermissionDO row = new SysUserPermissionDO();
      row.setUserId(change.userId());
      row.setTenantId(change.tenantId());
      row.setPermissionCode(code);
      permissions.insert(row);
    }
  }
}
