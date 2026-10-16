package com.tcrm.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
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
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.AccountType;
import com.tcrm.admin.system.model.CompanyCountRow;
import com.tcrm.admin.system.model.MemberRow;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserTenantDO;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Members of the current company, managed by its super administrators (ADR-022). Role, data scope,
 * template, status and codes belong to the membership in this company. Name, e-mail and password
 * belong to the person: they can be changed here only while the person belongs to this company
 * alone. Every update writes only the columns of that operation (never a whole row read earlier),
 * so concurrent edits cannot restore a stale status or password hash.
 */
@Service
public class UserService {
  private final SysUserMapper users;
  private final SysUserTenantMapper members;
  private final BCryptPasswordEncoder passwords;
  private final CurrentUserContext current;
  private final UserPermissionService permissions;
  private final ApplicationEventPublisher events;

  public UserService(
      SysUserMapper users,
      SysUserTenantMapper members,
      BCryptPasswordEncoder passwords,
      CurrentUserContext current,
      UserPermissionService permissions,
      ApplicationEventPublisher events) {
    this.users = users;
    this.members = members;
    this.passwords = passwords;
    this.current = current;
    this.permissions = permissions;
    this.events = events;
  }

  public PageResult<UserDTO> page(
      long page, long size, String username, Role role, Integer status) {
    long tenantId = tenantId();
    String keyword = username == null ? "" : username.strip();
    var result =
        members.pageMembers(
            new Page<>(page, size),
            tenantId,
            keyword.isEmpty() ? null : "%" + escapeLike(keyword) + "%",
            role,
            status);
    List<Long> ids = result.getRecords().stream().map(MemberRow::getUserId).toList();
    Map<Long, List<String>> stored = permissions.storedCodes(ids, tenantId);
    Map<Long, Integer> counts = companyCounts(ids);
    return new PageResult<>(
        result.getRecords().stream()
            .map(
                member ->
                    UserDTO.from(
                        member,
                        visible(member, stored),
                        counts.getOrDefault(member.getUserId(), 1)))
            .toList(),
        result.getTotal(),
        (int) page,
        (int) size);
  }

  /**
   * Creates a new account with a membership of this company. An existing username is 409 even when
   * the account belongs to another company: only a platform administrator adds existing accounts.
   */
  @Transactional(rollbackFor = Exception.class)
  public UserDTO create(CreateUserCommand r) {
    MemberRole resolved = resolve(r.role(), r.postTemplate(), r.dataScope(), r.permissions(), true);
    long tenantId = tenantId();
    if (users.countByUsername(r.username()) > 0) {
      throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS);
    }
    SysUserDO user = new SysUserDO();
    user.setUsername(r.username());
    user.setPasswordHash(passwords.encode(r.password()));
    user.setNickname(r.nickname());
    user.setEmail(r.email());
    user.setRole(AccountType.MEMBER);
    user.setStatus(1);
    user.setLastTenantId(tenantId);
    user.setDeleted(false);
    try {
      users.insert(user);
    } catch (DuplicateKeyException e) {
      // Only username has a unique key on sys_user; see uk_sys_user_username.
      throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS);
    }
    SysUserTenantDO member = new SysUserTenantDO();
    member.setTenantId(tenantId);
    member.setUserId(user.getId());
    member.setRole(resolved.role());
    member.setPostTemplate(resolved.postTemplate());
    member.setDataScope(resolved.dataScope());
    member.setStatus(1);
    member.setDeleted(false);
    members.insert(member);
    if (resolved.role() == Role.STAFF) {
      permissions.grant(user.getId(), tenantId, resolved.codes());
    }
    MemberRow created = members.getMember(user.getId(), tenantId);
    return UserDTO.from(created, visible(created, Map.of(user.getId(), resolved.codes())), 1);
  }

  /**
   * Role, template and data scope change the membership in this company. Changing the name or
   * e-mail of a person who belongs to several companies is 422 USER_MULTI_COMPANY_LOCKED; sending
   * the stored values unchanged is allowed.
   */
  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, EditUserCommand r) {
    MemberRole resolved = resolve(r.role(), r.postTemplate(), r.dataScope(), null, false);
    long tenantId = tenantId();
    MemberRow target = require(id, tenantId);
    if (id.equals(current.id()) && resolved.role() != Role.SUPER_ADMIN) {
      throw new BusinessException(ErrorCode.USER_CANNOT_REMOVE_SELF_ROLE);
    }
    boolean personal =
        !Objects.equals(target.getNickname(), r.nickname())
            || !Objects.equals(target.getEmail(), r.email());
    if (personal) {
      requireSingleCompany(id);
    }
    if (target.getRole() == Role.SUPER_ADMIN && resolved.role() != Role.SUPER_ADMIN) {
      requireAnotherSuperAdmin(id, tenantId);
    }
    if (personal) {
      writeAccount(
          id,
          new LambdaUpdateWrapper<SysUserDO>()
              .set(SysUserDO::getNickname, r.nickname())
              .set(SysUserDO::getEmail, r.email()));
    }
    writeMember(
        id,
        tenantId,
        new LambdaUpdateWrapper<SysUserTenantDO>()
            .set(SysUserTenantDO::getRole, resolved.role())
            .set(SysUserTenantDO::getDataScope, resolved.dataScope())
            .set(SysUserTenantDO::getPostTemplate, resolved.postTemplate()));
    if (target.getRole() != Role.STAFF && resolved.role() == Role.STAFF) {
      permissions.grant(id, tenantId, resolved.codes());
    } else if (resolved.role() != Role.STAFF) {
      permissions.clear(id, tenantId);
    }
  }

  /**
   * Enables or disables the membership in this company only. Disabling a staff member publishes
   * {@link StaffDisabledEvent} for this company and ends only the tokens working in this company.
   */
  @Transactional(rollbackFor = Exception.class)
  public void updateStatus(Long id, int status) {
    if (id.equals(current.id()) && status == 0) {
      throw new BusinessException(ErrorCode.USER_CANNOT_DISABLE_SELF);
    }
    long tenantId = tenantId();
    MemberRow target = require(id, tenantId);
    if (target.getRole() == Role.STAFF) {
      if (members.lockMember(id, tenantId) == null) {
        throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
      }
      target = require(id, tenantId);
    }
    if (target.getRole() == Role.SUPER_ADMIN && status == 0) {
      requireAnotherSuperAdmin(id, tenantId);
    }
    writeMember(
        id,
        tenantId,
        new LambdaUpdateWrapper<SysUserTenantDO>().set(SysUserTenantDO::getStatus, status));
    if (status == 0) {
      if (target.getRole() == Role.STAFF) {
        TransactionalEvents.publish(events, new StaffDisabledEvent(tenantId, id));
      }
      CurrentUserContext.logoutCompany(id, tenantId);
    }
  }

  /** Resets a member's password; 422 USER_MULTI_COMPANY_LOCKED for a multi-company person. */
  public void updatePassword(Long id, String password) {
    require(id, tenantId());
    requireSingleCompany(id);
    writeAccount(
        id,
        new LambdaUpdateWrapper<SysUserDO>()
            .set(SysUserDO::getPasswordHash, passwords.encode(password))
            .setSql("credential_version = credential_version + 1"));
    StpUtil.logout(id);
  }

  /**
   * Fails with USER_LAST_SUPER_ADMIN unless an enabled super administrator of this company other
   * than {@code id} remains. The company's enabled super administrators stay locked until the
   * transaction ends, so two administrators demoting or disabling each other at the same time
   * cannot both succeed. Other companies are not counted.
   */
  private void requireAnotherSuperAdmin(Long id, long tenantId) {
    if (members.lockEnabledSuperAdmins(tenantId).stream().noneMatch(other -> !other.equals(id))) {
      throw new BusinessException(ErrorCode.USER_LAST_SUPER_ADMIN);
    }
  }

  private void requireSingleCompany(Long id) {
    if (companyCounts(List.of(id)).getOrDefault(id, 1) > 1) {
      throw new BusinessException(ErrorCode.USER_MULTI_COMPANY_LOCKED);
    }
  }

  /**
   * Number of companies each person belongs to. Crosses companies on purpose and returns counts
   * only, no other company's data.
   */
  private Map<Long, Integer> companyCounts(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return TenantScope.callSystem(
        "count every company of listed members (counts only)",
        () ->
            members.countCompanies(ids).stream()
                .collect(
                    Collectors.toMap(
                        CompanyCountRow::getUserId, CompanyCountRow::getCompanyCount)));
  }

  /** Applies the given column updates to one live account, with the audit columns. */
  private void writeAccount(Long id, LambdaUpdateWrapper<SysUserDO> columns) {
    users.update(
        null,
        columns
            .eq(SysUserDO::getId, id)
            .set(SysUserDO::getUpdateBy, current.id())
            .set(SysUserDO::getUpdateTime, Instant.now()));
  }

  /** Applies the given column updates to one live membership of this company. */
  private void writeMember(Long id, long tenantId, LambdaUpdateWrapper<SysUserTenantDO> columns) {
    members.update(
        null,
        columns
            .eq(SysUserTenantDO::getUserId, id)
            .eq(SysUserTenantDO::getTenantId, tenantId)
            .set(SysUserTenantDO::getUpdateBy, current.id())
            .set(SysUserTenantDO::getUpdateTime, Instant.now()));
  }

  private MemberRow require(Long id, long tenantId) {
    MemberRow member = members.getMember(id, tenantId);
    if (member == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return member;
  }

  private long tenantId() {
    return current.user().tenantId();
  }

  /** {@link MemberRole#resolve}; a new staff member may start from an explicit code list. */
  private MemberRole resolve(
      Role role, PostTemplate template, DataScope scope, List<String> requested, boolean creating) {
    MemberRole resolved = MemberRole.resolve(role, template, scope);
    if (resolved.role() == Role.STAFF && creating && requested != null) {
      return resolved.withCodes(permissions.canonical(requested));
    }
    return resolved;
  }

  private static List<String> visible(MemberRow member, Map<Long, List<String>> stored) {
    return switch (member.getRole()) {
      case SUPER_ADMIN -> Permission.codes();
      case STAFF -> stored.getOrDefault(member.getUserId(), List.of());
      default -> List.of();
    };
  }

  /** Escapes LIKE wildcards and the escape character itself (PostgreSQL default escape "\"). */
  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
