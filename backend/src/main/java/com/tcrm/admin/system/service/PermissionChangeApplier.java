package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Writes one permission replacement. A separate bean so {@link AuditLog} sees the before and after
 * sets as method arguments; a self-call would skip the aspect.
 */
@Service
public class PermissionChangeApplier {
  private final SysUserMapper users;
  private final SysUserPermissionMapper permissions;
  private final CurrentUserContext current;

  public PermissionChangeApplier(
      SysUserMapper users, SysUserPermissionMapper permissions, CurrentUserContext current) {
    this.users = users;
    this.permissions = permissions;
    this.current = current;
  }

  @AuditLog(module = "User", action = "Replace permissions")
  public void apply(PermissionChange change) {
    users.update(
        null,
        new LambdaUpdateWrapper<SysUserDO>()
            .eq(SysUserDO::getId, change.userId())
            .set(SysUserDO::getDataScope, change.dataScope())
            .set(SysUserDO::getUpdateBy, current.id())
            .set(SysUserDO::getUpdateTime, Instant.now()));
    permissions.delete(
        new LambdaQueryWrapper<SysUserPermissionDO>()
            .eq(SysUserPermissionDO::getUserId, change.userId()));
    for (String code : change.permissions()) {
      SysUserPermissionDO row = new SysUserPermissionDO();
      row.setUserId(change.userId());
      row.setTenantId(change.tenantId());
      row.setPermissionCode(code);
      permissions.insert(row);
    }
  }
}
