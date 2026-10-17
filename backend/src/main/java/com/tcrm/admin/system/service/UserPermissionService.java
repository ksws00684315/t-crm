package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
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

  /** Known codes in {@link Permission} order. Duplicates collapse. Null or unknown codes fail. */
  public List<String> canonical(List<String> codes) {
    if (codes == null) {
      throw new BusinessException(ErrorCode.USER_PERMISSION_INVALID);
    }
    EnumSet<Permission> selected = EnumSet.noneOf(Permission.class);
    for (String code : codes) {
      Permission permission = Permission.fromCode(code);
      if (permission == null) {
        throw new BusinessException(ErrorCode.USER_PERMISSION_INVALID);
      }
      selected.add(permission);
    }
    return Permission.ordered(selected);
  }

  public List<String> storedCodes(Long userId, long tenantId) {
    return Permission.codes().stream().filter(storedSet(userId, tenantId)::contains).toList();
  }

  public Map<Long, List<String>> storedCodes(Collection<Long> userIds, long tenantId) {
    if (userIds == null || userIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, EnumSet<Permission>> grouped = new HashMap<>();
    for (SysUserPermissionDO row :
        permissions.selectList(
            new LambdaQueryWrapper<SysUserPermissionDO>()
                .select(SysUserPermissionDO::getUserId, SysUserPermissionDO::getPermissionCode)
                .eq(SysUserPermissionDO::getTenantId, tenantId)
                .in(SysUserPermissionDO::getUserId, userIds))) {
      Permission permission = Permission.fromCode(row.getPermissionCode());
      if (permission == null) {
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

  public PermissionCatalog catalog() {
    List<PermissionCatalog.Item> items =
        java.util.Arrays.stream(Permission.values())
            .map(permission -> new PermissionCatalog.Item(permission.code(), permission.group()))
            .toList();
    List<PermissionCatalog.Template> templates =
        java.util.Arrays.stream(PostTemplate.values())
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
