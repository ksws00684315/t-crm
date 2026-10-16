package com.tcrm.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.system.api.StaffDisabledEvent;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
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
  private final UserPermissionService permissions;
  private final ApplicationEventPublisher events;

  public UserService(
      SysUserMapper users,
      BCryptPasswordEncoder passwords,
      CurrentUserContext current,
      UserPermissionService permissions,
      ApplicationEventPublisher events) {
    this.users = users;
    this.passwords = passwords;
    this.current = current;
    this.permissions = permissions;
    this.events = events;
  }

  public PageResult<UserDTO> page(
      long page, long size, String username, Role role, Integer status) {
    String keyword = username == null ? "" : username.strip();
    var query =
        new LambdaQueryWrapper<SysUserDO>()
            .apply(!keyword.isEmpty(), "username ILIKE {0}", "%" + escapeLike(keyword) + "%")
            .eq(role != null, SysUserDO::getRole, role)
            .eq(status != null, SysUserDO::getStatus, status)
            .orderByDesc(SysUserDO::getCreateTime, SysUserDO::getId);
    var result = users.selectPage(new Page<>(page, size), query);
    Map<Long, List<String>> stored =
        permissions.storedCodes(result.getRecords().stream().map(SysUserDO::getId).toList());
    return new PageResult<>(
        result.getRecords().stream()
            .map(user -> UserDTO.from(user, visible(user, stored)))
            .toList(),
        result.getTotal(),
        (int) page,
        (int) size);
  }

  @Transactional(rollbackFor = Exception.class)
  public UserDTO create(CreateUserCommand r) {
    Resolved resolved = resolve(r.role(), r.postTemplate(), r.dataScope(), r.permissions(), true);
    if (TenantScope.callSystem(
        "username uniqueness", () -> users.countByUsername(r.username()) > 0)) {
      throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS);
    }
    SysUserDO user = new SysUserDO();
    user.setUsername(r.username());
    user.setPasswordHash(passwords.encode(r.password()));
    user.setNickname(r.nickname());
    user.setEmail(r.email());
    user.setRole(resolved.role());
    user.setDataScope(resolved.dataScope());
    user.setPostTemplate(resolved.postTemplate());
    user.setStatus(1);
    user.setDeleted(false);
    try {
      users.insert(user);
    } catch (DuplicateKeyException e) {
      // Only username has a unique key on sys_user; see uk_sys_user_username.
      throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS);
    }
    if (resolved.role() == Role.STAFF) {
      permissions.grant(user.getId(), current.user().tenantId(), resolved.codes());
    }
    return UserDTO.from(user, visible(user, Map.of(user.getId(), resolved.codes())));
  }

  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, EditUserCommand r) {
    Resolved resolved = resolve(r.role(), r.postTemplate(), r.dataScope(), null, false);
    SysUserDO target = require(id);
    if (id.equals(current.id()) && resolved.role() != Role.SUPER_ADMIN) {
      throw new BusinessException(ErrorCode.USER_CANNOT_REMOVE_SELF_ROLE);
    }
    if (target.getRole() == Role.SUPER_ADMIN && resolved.role() != Role.SUPER_ADMIN) {
      requireAnotherSuperAdmin(id);
    }
    write(
        id,
        new LambdaUpdateWrapper<SysUserDO>()
            .set(SysUserDO::getNickname, r.nickname())
            .set(SysUserDO::getEmail, r.email())
            .set(SysUserDO::getRole, resolved.role())
            .set(SysUserDO::getDataScope, resolved.dataScope())
            .set(SysUserDO::getPostTemplate, resolved.postTemplate()));
    if (target.getRole() != Role.STAFF && resolved.role() == Role.STAFF) {
      permissions.grant(id, target.getTenantId(), resolved.codes());
    } else if (resolved.role() != Role.STAFF) {
      permissions.clear(id);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateStatus(Long id, int status) {
    if (id.equals(current.id()) && status == 0) {
      throw new BusinessException(ErrorCode.USER_CANNOT_DISABLE_SELF);
    }
    SysUserDO target = require(id);
    if (target.getRole() == Role.STAFF) {
      if (users.lockById(id) == null) {
        throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
      }
      target = require(id);
    }
    if (target.getRole() == Role.SUPER_ADMIN && status == 0) {
      requireAnotherSuperAdmin(id);
    }
    write(id, new LambdaUpdateWrapper<SysUserDO>().set(SysUserDO::getStatus, status));
    if (status == 0) {
      if (target.getRole() == Role.STAFF) {
        TransactionalEvents.publish(events, new StaffDisabledEvent(target.getTenantId(), id));
      }
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

  /**
   * Super administrators ignore a supplied template and permission list. A staff account requires a
   * template. An omitted data scope uses the template default, or ALL for a super administrator.
   * OWN is rejected for anyone who is not staff.
   */
  private Resolved resolve(
      Role role, PostTemplate template, DataScope scope, List<String> requested, boolean creating) {
    if (role == null || role == Role.PLATFORM_ADMIN) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (role == Role.SUPER_ADMIN) {
      if (scope == DataScope.OWN) {
        throw new BusinessException(ErrorCode.USER_INVALID_DATA_SCOPE);
      }
      return new Resolved(Role.SUPER_ADMIN, scope == null ? DataScope.ALL : scope, null, List.of());
    }
    if (template == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    DataScope dataScope = scope == null ? template.dataScope() : scope;
    List<String> codes =
        creating && requested != null
            ? permissions.canonical(requested)
            : template.permissionCodes();
    return new Resolved(Role.STAFF, dataScope, template, codes);
  }

  private static List<String> visible(SysUserDO user, Map<Long, List<String>> stored) {
    return switch (user.getRole()) {
      case SUPER_ADMIN -> Permission.codes();
      case STAFF -> stored.getOrDefault(user.getId(), List.of());
      default -> List.of();
    };
  }

  /** Escapes LIKE wildcards and the escape character itself (PostgreSQL default escape "\"). */
  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private record Resolved(
      Role role, DataScope dataScope, PostTemplate postTemplate, List<String> codes) {}
}
