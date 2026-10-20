package com.tcrm.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.mapper.SysTenantMapper;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.AccountType;
import com.tcrm.admin.system.model.SysTenantDO;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserTenantDO;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-administrator operations. Company rows live in {@code sys_tenant}, which the tenant line
 * ignores. Reads and writes of accounts and memberships run in system scope and always name the
 * path company id; the caller's own company is null. Nothing here reads business data.
 */
@Service
public class PlatformService {
  private final SysTenantMapper tenants;
  private final SysUserMapper users;
  private final SysUserTenantMapper members;
  private final UserPermissionService permissions;
  private final PostTemplateService postTemplates;
  private final BCryptPasswordEncoder passwords;
  private final CurrentUserContext current;

  public PlatformService(
      SysTenantMapper tenants,
      SysUserMapper users,
      SysUserTenantMapper members,
      UserPermissionService permissions,
      PostTemplateService postTemplates,
      BCryptPasswordEncoder passwords,
      CurrentUserContext current) {
    this.tenants = tenants;
    this.users = users;
    this.members = members;
    this.permissions = permissions;
    this.postTemplates = postTemplates;
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
            members.listByRole(tenantId, Role.SUPER_ADMIN).stream()
                .map(TenantAdminDTO::from)
                .toList());
  }

  /** The account and its membership are written together or not at all. */
  @Transactional(rollbackFor = Exception.class)
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
          user.setRole(AccountType.MEMBER);
          user.setStatus(1);
          user.setLastTenantId(tenantId);
          user.setDeleted(false);
          try {
            users.insert(user);
          } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.USER_USERNAME_EXISTS);
          }
          insertMember(tenantId, user.getId(), MemberRole.resolve(Role.SUPER_ADMIN, null, null));
          return TenantAdminDTO.from(members.getMember(user.getId(), tenantId));
        });
  }

  /** Resets one super administrator of this company. Any other user id is not found. */
  public void resetAdminPassword(Long tenantId, Long userId, String password) {
    require(tenantId);
    TenantScope.runSystem(
        "reset company super administrator password",
        () -> {
          var member = members.getMember(userId, tenantId);
          if (member == null || member.getRole() != Role.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          writePassword(userId, password);
        });
  }

  /**
   * The company account with exactly this login name (case-insensitive) and all its memberships, or
   * null. No partial matching, so the platform cannot browse accounts. A platform administrator
   * account is answered as missing.
   */
  public PlatformUserDTO findUser(String username) {
    if (username == null || username.isBlank()) {
      return null;
    }
    return TenantScope.callSystem(
        "look up one account and its memberships by exact username",
        () -> {
          SysUserDO user =
              users.selectOne(
                  new LambdaQueryWrapper<SysUserDO>()
                      .apply("lower(username) = lower({0})", username.strip()));
          return user == null || user.getRole() != AccountType.MEMBER ? null : view(user);
        });
  }

  /**
   * Adds an existing company account to this company. A staff member starts from the template's
   * codes and data scope (ADR-018). 409 USER_ALREADY_MEMBER when a live membership exists; 404 for
   * an unknown company or account; 400 for a platform administrator account.
   */
  @Transactional(rollbackFor = Exception.class)
  public PlatformUserDTO addMember(
      Long tenantId, Long userId, Role role, PostTemplate template, DataScope scope) {
    require(tenantId);
    return TenantScope.callSystem(
        "add an existing account to a company",
        () -> {
          MemberRole resolved = MemberRole.resolve(role, template, scope);
          if (resolved.role() == Role.STAFF) {
            PostTemplateService.PostTemplateView defaults =
                postTemplates.getDefault(template, tenantId);
            resolved =
                new MemberRole(
                    Role.STAFF,
                    scope == null ? defaults.dataScope() : scope,
                    template,
                    defaults.permissions());
          }
          SysUserDO user = requireUser(userId);
          if (user.getRole() != AccountType.MEMBER) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
          }
          if (members.getMember(userId, tenantId) != null) {
            throw new BusinessException(ErrorCode.USER_ALREADY_MEMBER);
          }
          try {
            insertMember(tenantId, userId, resolved);
          } catch (DuplicateKeyException e) {
            // uk_sys_user_tenant_user_tenant: a concurrent request added the same membership.
            throw new BusinessException(ErrorCode.USER_ALREADY_MEMBER);
          }
          if (resolved.role() == Role.STAFF) {
            permissions.grant(userId, tenantId, resolved.codes());
          }
          return view(requireUser(userId));
        });
  }

  /** Changes the name and e-mail of a company account; a platform administrator is 404. */
  public void updateUser(Long userId, String nickname, String email) {
    TenantScope.runSystem(
        "edit account name and e-mail",
        () -> {
          requireMember(userId);
          users.update(
              null,
              new LambdaUpdateWrapper<SysUserDO>()
                  .eq(SysUserDO::getId, userId)
                  .set(SysUserDO::getNickname, nickname.strip())
                  .set(SysUserDO::getEmail, blankToNull(email))
                  .set(SysUserDO::getUpdateBy, current.id())
                  .set(SysUserDO::getUpdateTime, Instant.now()));
        });
  }

  /**
   * Resets the password of a company account; every token of the account becomes invalid. A
   * platform administrator is 404.
   */
  public void resetPassword(Long userId, String password) {
    TenantScope.runSystem(
        "reset account password",
        () -> {
          requireMember(userId);
          writePassword(userId, password);
        });
  }

  /**
   * Enables or disables the whole company account in every company. Disabling ends every token of
   * the account. A platform administrator cannot disable itself, and another platform administrator
   * is 404.
   */
  public void updateUserStatus(Long userId, int status) {
    if (status == 0 && userId.equals(current.id())) {
      throw new BusinessException(ErrorCode.USER_CANNOT_DISABLE_SELF);
    }
    TenantScope.runSystem(
        "change account status",
        () -> {
          requireMember(userId);
          users.update(
              null,
              new LambdaUpdateWrapper<SysUserDO>()
                  .eq(SysUserDO::getId, userId)
                  .set(SysUserDO::getStatus, status)
                  .set(SysUserDO::getUpdateBy, current.id())
                  .set(SysUserDO::getUpdateTime, Instant.now()));
        });
    if (status == 0) {
      StpUtil.logout(userId);
    }
  }

  private void insertMember(Long tenantId, Long userId, MemberRole resolved) {
    SysUserTenantDO member = new SysUserTenantDO();
    member.setTenantId(tenantId);
    member.setUserId(userId);
    member.setRole(resolved.role());
    member.setPostTemplate(resolved.postTemplate());
    member.setDataScope(resolved.dataScope());
    member.setStatus(1);
    member.setDeleted(false);
    members.insert(member);
  }

  private void writePassword(Long userId, String password) {
    users.update(
        null,
        new LambdaUpdateWrapper<SysUserDO>()
            .eq(SysUserDO::getId, userId)
            .set(SysUserDO::getPasswordHash, passwords.encode(password))
            .setSql("credential_version = credential_version + 1")
            .set(SysUserDO::getUpdateBy, current.id())
            .set(SysUserDO::getUpdateTime, Instant.now()));
    StpUtil.logout(userId);
  }

  private PlatformUserDTO view(SysUserDO user) {
    return new PlatformUserDTO(
        user.getId(),
        user.getUsername(),
        user.getNickname(),
        user.getEmail(),
        user.getStatus(),
        members.listMemberships(user.getId()).stream()
            .map(
                m ->
                    new PlatformUserDTO.Company(
                        m.getTenantId(), m.getTenantName(), m.getRole(), m.getStatus()))
            .toList());
  }

  private SysUserDO requireUser(Long userId) {
    SysUserDO user = users.selectById(userId);
    if (user == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return user;
  }

  /** A company account. Platform administrator accounts are not managed here: they are 404. */
  private SysUserDO requireMember(Long userId) {
    SysUserDO user = requireUser(userId);
    if (user.getRole() != AccountType.MEMBER) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return user;
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
