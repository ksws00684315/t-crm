package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stored staff permissions. Unknown codes are rejected before any row is written. */
@Service
public class UserPermissionService {
  private final SysUserMapper users;
  private final SysUserPermissionMapper permissions;
  private final PermissionChangeApplier applier;

  public UserPermissionService(
      SysUserMapper users, SysUserPermissionMapper permissions, PermissionChangeApplier applier) {
    this.users = users;
    this.permissions = permissions;
    this.applier = applier;
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

  public List<String> storedCodes(Long userId) {
    return Permission.codes().stream().filter(storedSet(userId)::contains).toList();
  }

  public Map<Long, List<String>> storedCodes(Collection<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, EnumSet<Permission>> grouped = new HashMap<>();
    for (SysUserPermissionDO row :
        permissions.selectList(
            new LambdaQueryWrapper<SysUserPermissionDO>()
                .select(SysUserPermissionDO::getUserId, SysUserPermissionDO::getPermissionCode)
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

  /** Replaces every stored code. Caller supplies the company; the tenant plugin also checks it. */
  public void grant(Long userId, Long tenantId, List<String> codes) {
    permissions.delete(
        new LambdaQueryWrapper<SysUserPermissionDO>().eq(SysUserPermissionDO::getUserId, userId));
    for (String code : codes) {
      SysUserPermissionDO row = new SysUserPermissionDO();
      row.setUserId(userId);
      row.setTenantId(tenantId);
      row.setPermissionCode(code);
      permissions.insert(row);
    }
  }

  public void clear(Long userId) {
    permissions.delete(
        new LambdaQueryWrapper<SysUserPermissionDO>().eq(SysUserPermissionDO::getUserId, userId));
  }

  /**
   * Staff permissions and data scope. Missing and cross-company ids are 404; other roles are 400.
   */
  public UserPermissionView get(Long id) {
    SysUserDO user = requireStaff(id);
    return new UserPermissionView(storedCodes(id), user.getDataScope());
  }

  /**
   * Replaces a staff account's permissions and data scope in one transaction. The audited call
   * receives both the previous and the next code sets.
   */
  @Transactional(rollbackFor = Exception.class)
  public void replace(Long id, List<String> codes, DataScope dataScope) {
    List<String> next = canonical(codes);
    SysUserDO user = requireStaff(id);
    applier.apply(
        new PermissionChange(
            id, user.getTenantId(), storedCodes(id), next, user.getDataScope(), dataScope));
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

  private SysUserDO requireStaff(Long id) {
    SysUserDO user = users.selectById(id);
    if (user == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (user.getRole() != Role.STAFF) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return user;
  }

  private java.util.Set<String> storedSet(Long userId) {
    return permissions
        .selectList(
            new LambdaQueryWrapper<SysUserPermissionDO>()
                .select(SysUserPermissionDO::getPermissionCode)
                .eq(SysUserPermissionDO::getUserId, userId))
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
