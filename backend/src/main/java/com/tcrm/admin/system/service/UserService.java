package com.tcrm.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator accounts. Every update writes only the columns of that operation (never a whole row
 * read earlier), so concurrent edits cannot restore a stale status or password hash.
 */
@Service
public class UserService {
  private final SysUserMapper users;
  private final BCryptPasswordEncoder passwords;
  private final CurrentUserContext current;

  public UserService(
      SysUserMapper users, BCryptPasswordEncoder passwords, CurrentUserContext current) {
    this.users = users;
    this.passwords = passwords;
    this.current = current;
  }

  public PageResult<UserDTO> page(
      long page, long size, String username, Role role, Integer status) {
    var query =
        new LambdaQueryWrapper<SysUserDO>()
            .apply(
                username != null && !username.isBlank(),
                "username ILIKE {0}",
                "%" + escapeLike(username) + "%")
            .eq(role != null, SysUserDO::getRole, role)
            .eq(status != null, SysUserDO::getStatus, status)
            .orderByDesc(SysUserDO::getCreateTime, SysUserDO::getId);
    var result = users.selectPage(new Page<>(page, size), query);
    return new PageResult<>(
        result.getRecords().stream().map(UserDTO::from).toList(),
        result.getTotal(),
        (int) page,
        (int) size);
  }

  public UserDTO create(CreateUserCommand r) {
    validateScope(r.role(), r.dataScope());
    SysUserDO user = new SysUserDO();
    user.setUsername(r.username());
    user.setPasswordHash(passwords.encode(r.password()));
    user.setNickname(r.nickname());
    user.setEmail(r.email());
    user.setRole(r.role());
    user.setDataScope(r.dataScope());
    user.setStatus(1);
    user.setDeleted(false);
    try {
      users.insert(user);
    } catch (DuplicateKeyException e) {
      // Only username has a unique key on sys_user; see uk_sys_user_username.
      throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS);
    }
    return UserDTO.from(user);
  }

  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, EditUserCommand r) {
    validateScope(r.role(), r.dataScope());
    SysUserDO target = require(id);
    if (id.equals(current.id()) && r.role() != Role.SUPER_ADMIN) {
      throw new BusinessException(ErrorCode.USER_CANNOT_REMOVE_SELF_ROLE);
    }
    if (target.getRole() == Role.SUPER_ADMIN && r.role() != Role.SUPER_ADMIN) {
      requireAnotherSuperAdmin(id);
    }
    write(
        id,
        new LambdaUpdateWrapper<SysUserDO>()
            .set(SysUserDO::getNickname, r.nickname())
            .set(SysUserDO::getEmail, r.email())
            .set(SysUserDO::getRole, r.role())
            .set(SysUserDO::getDataScope, r.dataScope()));
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateStatus(Long id, int status) {
    if (id.equals(current.id()) && status == 0) {
      throw new BusinessException(ErrorCode.USER_CANNOT_DISABLE_SELF);
    }
    SysUserDO target = require(id);
    if (target.getRole() == Role.SUPER_ADMIN && status == 0) {
      requireAnotherSuperAdmin(id);
    }
    write(id, new LambdaUpdateWrapper<SysUserDO>().set(SysUserDO::getStatus, status));
    if (status == 0) {
      StpUtil.kickout(id);
    }
  }

  public void updatePassword(Long id, String password) {
    require(id);
    write(
        id,
        new LambdaUpdateWrapper<SysUserDO>()
            .set(SysUserDO::getPasswordHash, passwords.encode(password))
            .setSql("credential_version = credential_version + 1"));
    StpUtil.logout(id);
  }

  /**
   * Fails with USER_LAST_SUPER_ADMIN unless an enabled super administrator other than {@code id}
   * remains. The enabled super administrators stay locked until the transaction ends, so two
   * administrators demoting or disabling each other at the same time cannot both succeed.
   */
  private void requireAnotherSuperAdmin(Long id) {
    if (users.lockEnabledSuperAdmins().stream().noneMatch(other -> !other.equals(id))) {
      throw new BusinessException(ErrorCode.USER_LAST_SUPER_ADMIN);
    }
  }

  /** Applies the given column updates to one live user, with the audit columns. */
  private void write(Long id, LambdaUpdateWrapper<SysUserDO> columns) {
    users.update(
        null,
        columns
            .eq(SysUserDO::getId, id)
            .set(SysUserDO::getUpdateBy, current.id())
            .set(SysUserDO::getUpdateTime, Instant.now()));
  }

  private SysUserDO require(Long id) {
    SysUserDO user = users.selectById(id);
    if (user == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return user;
  }

  private void validateScope(Role role, DataScope scope) {
    if (scope == DataScope.OWN && role != Role.OPERATOR) {
      throw new BusinessException(ErrorCode.USER_INVALID_DATA_SCOPE);
    }
  }

  /** Escapes LIKE wildcards and the escape character itself (PostgreSQL default escape "\"). */
  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
