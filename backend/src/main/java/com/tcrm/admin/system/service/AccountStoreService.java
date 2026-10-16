package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.mapper.SysTenantMapper;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.model.SysTenantDO;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Account lookups run in system scope: login does not know the company yet, and re-reading the
 * account is how the company is learned. Filtering those queries would recurse through the tenant
 * line handler.
 */
@Service
public class AccountStoreService implements AccountStore {
  private final SysUserMapper users;
  private final SysTenantMapper tenants;
  private final SysUserPermissionMapper permissions;

  public AccountStoreService(
      SysUserMapper users, SysTenantMapper tenants, SysUserPermissionMapper permissions) {
    this.users = users;
    this.tenants = tenants;
    this.permissions = permissions;
  }

  @Override
  public AccountDTO getByUsername(String username) {
    return TenantScope.callSystem(
        "load account by username",
        () ->
            snapshot(
                users.selectOne(
                    new LambdaQueryWrapper<SysUserDO>()
                        .apply("lower(username) = lower({0})", username))));
  }

  @Override
  public AccountDTO getById(Long id) {
    return TenantScope.callSystem("load account by id", () -> snapshot(users.selectById(id)));
  }

  @Override
  public void updateLastLoginTime(Long id, Instant time) {
    TenantScope.runSystem(
        "record last login time",
        () -> {
          SysUserDO user = new SysUserDO();
          user.setId(id);
          user.setLastLoginTime(time);
          users.updateById(user);
        });
  }

  @Override
  public void updatePassword(Long id, String hash) {
    TenantScope.runSystem(
        "update password and credential version",
        () ->
            users.update(
                null,
                new LambdaUpdateWrapper<SysUserDO>()
                    .eq(SysUserDO::getId, id)
                    .set(SysUserDO::getPasswordHash, hash)
                    .setSql("credential_version = credential_version + 1")
                    .set(SysUserDO::getUpdateBy, id)
                    .set(SysUserDO::getUpdateTime, Instant.now())));
  }

  private AccountDTO snapshot(SysUserDO user) {
    if (user == null) {
      return null;
    }
    Long tenantId = user.getTenantId();
    String tenantName = null;
    boolean tenantEnabled = true;
    if (tenantId != null) {
      SysTenantDO tenant = tenants.selectById(tenantId);
      tenantName = tenant == null ? null : tenant.getName();
      tenantEnabled = tenant != null && Integer.valueOf(1).equals(tenant.getStatus());
    }
    return new AccountDTO(
        user.getId(),
        user.getUsername(),
        user.getPasswordHash(),
        user.getNickname(),
        user.getEmail(),
        user.getRole(),
        user.getDataScope(),
        user.getStatus(),
        user.getLastLoginTime(),
        user.getCreateTime(),
        user.getCredentialVersion() == null ? 0 : user.getCredentialVersion(),
        tenantId,
        tenantName,
        tenantEnabled,
        permissionCodes(user.getId()),
        user.getPostTemplate());
  }

  /**
   * One query per account load. Runs in the caller's system scope, so it is not tenant-filtered.
   */
  private Set<String> permissionCodes(Long userId) {
    return permissions
        .selectList(
            new LambdaQueryWrapper<SysUserPermissionDO>()
                .select(SysUserPermissionDO::getPermissionCode)
                .eq(SysUserPermissionDO::getUserId, userId))
        .stream()
        .map(SysUserPermissionDO::getPermissionCode)
        .collect(Collectors.toSet());
  }
}
