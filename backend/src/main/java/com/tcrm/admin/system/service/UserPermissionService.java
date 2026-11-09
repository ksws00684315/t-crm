package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.MemberRow;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stored staff permissions, per company (ADR-022): codes held in one company do not affect another.
 * Unknown codes are rejected before any row is written.
 *
 * <p>Codes are limited to the company's edition (ADR-038 §2.4): assignment, the catalog and the
 * post templates only offer the codes of the current edition, and a code of another edition is
 * rejected with {@link ErrorCode#USER_PERMISSION_EDITION_MISMATCH} rather than stored. Stored rows
 * of another edition never take effect either, see {@code CurrentUserContext}.
 */
@Service
public class UserPermissionService {
  private final SysUserTenantMapper members;
  private final SysUserPermissionMapper permissions;
  private final PermissionChangeApplier applier;
  private final CurrentUserContext current;

  public UserPermissionService(
      SysUserTenantMapper members,
      SysUserPermissionMapper permissions,
      PermissionChangeApplier applier,
      CurrentUserContext current) {
    this.members = members;
    this.permissions = permissions;
    this.applier = applier;
    this.current = current;
  }

  /**
   * Codes of {@code edition}, in {@link Permission} order. Duplicates collapse. Null or unknown
   * codes fail with {@code USER_PERMISSION_INVALID}; a known code of another edition fails with
   * {@code USER_PERMISSION_EDITION_MISMATCH}.
   */
  public List<String> canonical(List<String> codes, Edition edition) {
    if (codes == null) {
      throw new BusinessException(ErrorCode.USER_PERMISSION_INVALID);
    }
    EnumSet<Permission> selected = EnumSet.noneOf(Permission.class);
    for (String code : codes) {
      Permission permission = Permission.fromCode(code);
      if (permission == null) {
        throw new BusinessException(ErrorCode.USER_PERMISSION_INVALID);
      }
      if (permission.edition() != edition) {
        throw new BusinessException(ErrorCode.USER_PERMISSION_EDITION_MISMATCH);
      }
      selected.add(permission);
    }
    return Permission.ordered(selected);
  }

  /** Codes of the current company's edition. */
  public List<String> canonical(List<String> codes) {
    return canonical(codes, current.edition());
  }

  /** Stored codes that belong to the current edition, in {@link Permission} order. */
  public List<String> storedCodes(Long userId, long tenantId) {
    List<String> codes = current.editionCodes(tenantId);
    return codes.stream().filter(storedSet(userId, tenantId)::contains).toList();
  }

  /**
   * Custody of the same codes for a page of accounts. Limited to the company's edition like the
   * single-account lookup: a code of the other edition can only come from old data, and the list
   * must not show codes the account cannot use.
   */
  public Map<Long, List<String>> storedCodes(Collection<Long> userIds, long tenantId) {
    if (userIds == null || userIds.isEmpty()) {
      return Map.of();
    }
    EnumSet<Permission> usable = EnumSet.noneOf(Permission.class);
    for (String code : current.editionCodes(tenantId)) {
      Permission permission = Permission.fromCode(code);
      if (permission != null) {
        usable.add(permission);
      }
    }
    Map<Long, EnumSet<Permission>> grouped = new HashMap<>();
    for (SysUserPermissionDO row :
        permissions.selectList(
            new LambdaQueryWrapper<SysUserPermissionDO>()
                .select(SysUserPermissionDO::getUserId, SysUserPermissionDO::getPermissionCode)
                .eq(SysUserPermissionDO::getTenantId, tenantId)
                .in(SysUserPermissionDO::getUserId, userIds))) {
      Permission permission = Permission.fromCode(row.getPermissionCode());
      if (permission == null || !usable.contains(permission)) {
        continue;
      }
      grouped
          .computeIfAbsent(row.getUserId(), id -> EnumSet.noneOf(Permission.class))
          .add(permission);
    }
    Map<Long, List<String>> result = new HashMap<>();
    grouped.forEach((id, selected) -> result.put(id, Permission.ordered(selected)));
    return result;
  }

  /**
   * Replaces every code stored for this account in this company. Caller supplies the company; in a
   * company scope the tenant plugin also checks it.
   */
  public void grant(Long userId, long tenantId, List<String> codes) {
    clear(userId, tenantId);
    for (String code : codes) {
      SysUserPermissionDO row = new SysUserPermissionDO();
      row.setUserId(userId);
      row.setTenantId(tenantId);
      row.setPermissionCode(code);
      permissions.insert(row);
    }
  }

  public void clear(Long userId, long tenantId) {
    permissions.delete(
        new LambdaQueryWrapper<SysUserPermissionDO>()
            .eq(SysUserPermissionDO::getUserId, userId)
            .eq(SysUserPermissionDO::getTenantId, tenantId));
  }

  /**
   * Staff permissions and data scope. Missing and cross-company ids are 404; other roles are 400.
   */
  public UserPermissionView get(Long id) {
    long tenantId = current.user().tenantId();
    MemberRow member = requireStaff(id, tenantId);
    return new UserPermissionView(storedCodes(id, tenantId), member.getDataScope());
  }

  /**
   * Replaces a staff account's permissions and data scope in one transaction. The audited call
   * receives both the previous and the next code sets.
   */
  @Transactional(rollbackFor = Exception.class)
  public void replace(Long id, List<String> codes, DataScope dataScope) {
    List<String> next = canonical(codes);
    long tenantId = current.user().tenantId();
    MemberRow member = requireStaff(id, tenantId);
    applier.apply(
        new PermissionChange(
            id, tenantId, storedCodes(id, tenantId), next, member.getDataScope(), dataScope));
  }

  /**
   * Codes and post templates assignable in the current company's edition (ADR-038 §2.4). The
   * creator-library edition has no post templates: they are a distribution concept too.
   */
  public PermissionCatalog catalog() {
    Edition edition = current.edition();
    List<PermissionCatalog.Item> items =
        java.util.Arrays.stream(Permission.values())
            .filter(permission -> permission.edition() == edition)
            .map(permission -> new PermissionCatalog.Item(permission.code(), permission.group()))
            .toList();
    List<PermissionCatalog.Template> templates =
        edition != Edition.DISTRIBUTION
            ? List.of()
            : java.util.Arrays.stream(PostTemplate.values())
                .map(
                    template ->
                        new PermissionCatalog.Template(
                            template.name(), template.permissionCodes(), template.dataScope()))
                .toList();
    return new PermissionCatalog(items, templates);
  }

  private MemberRow requireStaff(Long id, long tenantId) {
    MemberRow member = members.getMember(id, tenantId);
    if (member == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (member.getRole() != Role.STAFF) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return member;
  }

  private java.util.Set<String> storedSet(Long userId, long tenantId) {
    return permissions
        .selectList(
            new LambdaQueryWrapper<SysUserPermissionDO>()
                .select(SysUserPermissionDO::getPermissionCode)
                .eq(SysUserPermissionDO::getUserId, userId)
                .eq(SysUserPermissionDO::getTenantId, tenantId))
        .stream()
        .map(SysUserPermissionDO::getPermissionCode)
        .collect(java.util.stream.Collectors.toSet());
  }

  public record UserPermissionView(List<String> permissions, DataScope dataScope) {}

  public record PermissionCatalog(List<Item> permissions, List<Template> templates) {
    public record Item(String code, Permission.Group group) {}

    public record Template(String code, List<String> permissions, DataScope dataScope) {}
  }
}
