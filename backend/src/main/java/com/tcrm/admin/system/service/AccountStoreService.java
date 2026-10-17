package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.security.api.CompanyDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.mapper.SysTenantMapper;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.AccountType;
import com.tcrm.admin.system.model.SysTenantDO;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import com.tcrm.admin.system.model.SysUserTenantDO;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Account lookups run in system scope: login does not know the company yet, the company a token
 * works in is chosen from the account's own memberships, and filtering those queries by the current
 * company would recurse through the tenant line handler. Every lookup names the company explicitly.
 */
@Service
public class AccountStoreService implements AccountStore {
  private final SysUserMapper users;
  private final SysUserTenantMapper members;
  private final SysTenantMapper tenants;
  private final SysUserPermissionMapper permissions;

  public AccountStoreService(
      SysUserMapper users,
      SysUserTenantMapper members,
      SysTenantMapper tenants,
      SysUserPermissionMapper permissions) {
    this.users = users;
    this.members = members;
    this.tenants = tenants;
    this.permissions = permissions;
  }

  @Override
  public AccountDTO getByUsername(String username) {
    return TenantScope.callSystem(
        "load account by username",
        () -> {
          SysUserDO user =
              users.selectOne(
                  new LambdaQueryWrapper<SysUserDO>()
                      .apply("lower(username) = lower({0})", username));
          return user == null ? null : snapshot(user, user.getLastTenantId());
        });
  }

  @Override
  public AccountDTO getById(Long id) {
    return TenantScope.callSystem(
        "load account by id in its last company",
        () -> {
          SysUserDO user = users.selectById(id);
          return user == null ? null : snapshot(user, user.getLastTenantId());
        });
  }

  @Override
  public AccountDTO getById(Long id, long tenantId) {
    return TenantScope.callSystem(
        "load account by id in the token's company",
        () -> {
          SysUserDO user = users.selectById(id);
          return user == null ? null : snapshot(user, tenantId);
        });
  }

  @Override
  public List<CompanyDTO> companies(Long id) {
    return TenantScope.callSystem(
        "list the account's own companies",
        () ->
            members.listMemberships(id).stream()
                .filter(
                    m ->
                        Integer.valueOf(1).equals(m.getStatus())
                            && Integer.valueOf(1).equals(m.getTenantStatus()))
                .map(m -> new CompanyDTO(m.getTenantId(), m.getTenantName(), m.getRole()))
                .toList());
  }

  @Override
  public void recordLogin(Long id, Long tenantId, Instant time) {
    TenantScope.runSystem(
        "record last login time and company",
        () -> {
          var account = new LambdaUpdateWrapper<SysUserDO>().eq(SysUserDO::getId, id);
          account.set(SysUserDO::getLastLoginTime, time);
          if (tenantId != null) {
            account.set(SysUserDO::getLastTenantId, tenantId);
            touchMembership(id, tenantId, time);
          }
          users.update(null, account);
        });
  }

  @Override
  public void recordSwitch(Long id, long tenantId, Instant time) {
    TenantScope.runSystem(
        "record company switch",
        () -> {
          users.update(
              null,
              new LambdaUpdateWrapper<SysUserDO>()
                  .eq(SysUserDO::getId, id)
                  .set(SysUserDO::getLastTenantId, tenantId));
          touchMembership(id, tenantId, time);
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

  private void touchMembership(Long id, long tenantId, Instant time) {
    members.update(
        null,
        new LambdaUpdateWrapper<SysUserTenantDO>()
            .eq(SysUserTenantDO::getUserId, id)
            .eq(SysUserTenantDO::getTenantId, tenantId)
            .set(SysUserTenantDO::getLastLoginTime, time));
  }

  /**
   * The account as a member of {@code tenantId}. A platform administrator has no company. A member
   * without a live membership there gets a null role, no codes, and an unusable company.
   */
  private AccountDTO snapshot(SysUserDO user, Long tenantId) {
    int version = user.getCredentialVersion() == null ? 0 : user.getCredentialVersion();
    if (user.getRole() == AccountType.PLATFORM_ADMIN) {
      return new AccountDTO(
          user.getId(),
          user.getUsername(),
          user.getPasswordHash(),
          user.getNickname(),
          user.getEmail(),
          Role.PLATFORM_ADMIN,
          DataScope.ALL,
          user.getStatus(),
          user.getLastLoginTime(),
          user.getCreateTime(),
          version,
          null,
          null,
          true);
    }
    SysUserTenantDO member = tenantId == null ? null : membership(user.getId(), tenantId);
    SysTenantDO tenant = tenantId == null ? null : tenants.selectById(tenantId);
    boolean usable =
        member != null
            && Integer.valueOf(1).equals(member.getStatus())
            && tenant != null
            && Integer.valueOf(1).equals(tenant.getStatus());
    return new AccountDTO(
        user.getId(),
        user.getUsername(),
        user.getPasswordHash(),
        user.getNickname(),
        user.getEmail(),
        member == null ? null : member.getRole(),
        member == null ? null : member.getDataScope(),
        user.getStatus(),
        user.getLastLoginTime(),
        user.getCreateTime(),
        version,
        tenantId,
        tenant == null ? null : tenant.getName(),
        usable,
        member == null ? Set.of() : permissionCodes(user.getId(), tenantId),
        member == null ? null : member.getPostTemplate());
  }

  private SysUserTenantDO membership(Long userId, long tenantId) {
    return members.selectOne(
        new LambdaQueryWrapper<SysUserTenantDO>()
            .eq(SysUserTenantDO::getUserId, userId)
            .eq(SysUserTenantDO::getTenantId, tenantId));
  }

  /** Codes granted in this company only. Runs in the caller's system scope. */
  private Set<String> permissionCodes(Long userId, long tenantId) {
    return permissions
        .selectList(
            new LambdaQueryWrapper<SysUserPermissionDO>()
                .select(SysUserPermissionDO::getPermissionCode)
                .eq(SysUserPermissionDO::getUserId, userId)
                .eq(SysUserPermissionDO::getTenantId, tenantId))
        .stream()
        .map(SysUserPermissionDO::getPermissionCode)
        .collect(Collectors.toSet());
  }
}
