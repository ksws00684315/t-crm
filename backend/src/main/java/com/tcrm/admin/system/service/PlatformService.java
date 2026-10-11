package com.tcrm.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.mapper.SysTenantMapper;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysTenantDO;
import com.tcrm.admin.system.model.SysUserDO;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Platform-administrator operations. Company rows live in {@code sys_tenant}, which the tenant line
 * ignores. Reads and writes of {@code sys_user} run in system scope and always name the path
 * company id; the caller's own company is null.
 */
@Service
public class PlatformService {
  private final SysTenantMapper tenants;
  private final SysUserMapper users;
  private final BCryptPasswordEncoder passwords;
  private final CurrentUserContext current;

  public PlatformService(
      SysTenantMapper tenants,
      SysUserMapper users,
      BCryptPasswordEncoder passwords,
      CurrentUserContext current) {
    this.tenants = tenants;
    this.users = users;
    this.passwords = passwords;
    this.current = current;
  }

  public List<TenantDTO> list() {
    return tenants
        .selectList(
            new LambdaQueryWrapper<SysTenantDO>()
                .orderByAsc(SysTenantDO::getCreateTime, SysTenantDO::getId))
        .stream()
        .map(TenantDTO::from)
        .toList();
  }

  public TenantDTO create(String tenantCode, String name, String remark) {
    SysTenantDO row = new SysTenantDO();
    row.setTenantCode(tenantCode);
    row.setName(name.strip());
    row.setRemark(blankToNull(remark));
    row.setStatus(1);
    row.setDeleted(false);
    try {
      tenants.insert(row);
    } catch (DuplicateKeyException e) {
      // The only business unique key on sys_tenant is uk_sys_tenant_tenant_code.
      throw new BusinessException(ErrorCode.TENANT_CODE_EXISTS);
    }
    return TenantDTO.from(row);
  }

  public void update(Long id, String name, String remark) {
    require(id);
    tenants.update(
        null,
        new LambdaUpdateWrapper<SysTenantDO>()
            .eq(SysTenantDO::getId, id)
            .set(SysTenantDO::getName, name.strip())
            .set(SysTenantDO::getRemark, blankToNull(remark))
            .set(SysTenantDO::getUpdateBy, current.id())
            .set(SysTenantDO::getUpdateTime, Instant.now()));
  }

  public void updateStatus(Long id, int status) {
    require(id);
    tenants.update(
        null,
        new LambdaUpdateWrapper<SysTenantDO>()
            .eq(SysTenantDO::getId, id)
            .set(SysTenantDO::getStatus, status)
            .set(SysTenantDO::getUpdateBy, current.id())
            .set(SysTenantDO::getUpdateTime, Instant.now()));
  }

  public List<TenantAdminDTO> listAdmins(Long tenantId) {
    require(tenantId);
    return TenantScope.callSystem(
        "list company super administrators",
        () ->
            users
                .selectList(
                    new LambdaQueryWrapper<SysUserDO>()
                        .eq(SysUserDO::getTenantId, tenantId)
                        .eq(SysUserDO::getRole, Role.SUPER_ADMIN)
                        .orderByAsc(SysUserDO::getCreateTime, SysUserDO::getId))
                .stream()
                .map(TenantAdminDTO::from)
                .toList());
  }

  public TenantAdminDTO createAdmin(
      Long tenantId, String username, String password, String nickname, String email) {
    require(tenantId);
    return TenantScope.callSystem(
        "create company super administrator",
        () -> {
          if (users.countByUsername(username) > 0) {
            throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS);
          }
          SysUserDO user = new SysUserDO();
          user.setUsername(username);
          user.setPasswordHash(passwords.encode(password));
          user.setNickname(nickname.strip());
          user.setEmail(blankToNull(email));
          user.setRole(Role.SUPER_ADMIN);
          user.setDataScope(DataScope.ALL);
          user.setStatus(1);
          user.setTenantId(tenantId);
          user.setDeleted(false);
          try {
            users.insert(user);
          } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS);
          }
          return TenantAdminDTO.from(user);
        });
  }

  /** Resets one super administrator of this company. Any other user id is not found. */
  public void resetAdminPassword(Long tenantId, Long userId, String password) {
    require(tenantId);
    TenantScope.runSystem(
        "reset company super administrator password",
        () -> {
          int updated =
              users.update(
                  null,
                  new LambdaUpdateWrapper<SysUserDO>()
                      .eq(SysUserDO::getId, userId)
                      .eq(SysUserDO::getTenantId, tenantId)
                      .eq(SysUserDO::getRole, Role.SUPER_ADMIN)
                      .set(SysUserDO::getPasswordHash, passwords.encode(password))
                      .setSql("credential_version = credential_version + 1")
                      .set(SysUserDO::getUpdateBy, current.id())
                      .set(SysUserDO::getUpdateTime, Instant.now()));
          if (updated == 0) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          StpUtil.logout(userId);
        });
  }

  private SysTenantDO require(Long id) {
    SysTenantDO row = tenants.selectById(id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return row;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
  }
}
