package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** BD facade for other modules and the BD picker; exposes only ID, names and usability. */
@Service
public class BdUserService implements BdUserApi {
  private final SysUserMapper users;
  private final SysUserPermissionMapper permissions;

  public BdUserService(SysUserMapper users, SysUserPermissionMapper permissions) {
    this.users = users;
    this.permissions = permissions;
  }

  @Override
  public BdUserDTO getById(Long id) {
    return id == null ? null : toDto(users.selectOne(columns().eq(SysUserDO::getId, id)));
  }

  @Override
  public BdUserDTO lockById(Long id) {
    return users.lockById(id) == null ? null : getById(id);
  }

  @Override
  public BdUserDTO getByUsername(String username) {
    if (username == null || username.isBlank()) {
      return null;
    }
    return toDto(
        users.selectOne(columns().apply("lower(username) = lower({0})", username.strip())));
  }

  @Override
  public List<BdUserDTO> listByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    List<SysUserDO> rows = users.selectList(columns().in(SysUserDO::getId, ids));
    Set<Long> editors = editorsAmong(rows.stream().map(SysUserDO::getId).toList());
    return rows.stream().map(user -> toDto(user, editors.contains(user.getId()))).toList();
  }

  @Override
  public List<BdUserDTO> listUsable() {
    Set<Long> editors = editorsWith(Permission.KOL_EDIT.code());
    if (editors.isEmpty()) {
      return List.of();
    }
    return users
        .selectList(
            columns()
                .eq(SysUserDO::getRole, Role.STAFF)
                .eq(SysUserDO::getDataScope, DataScope.OWN)
                .eq(SysUserDO::getStatus, 1)
                .in(SysUserDO::getId, editors)
                .orderByAsc(SysUserDO::getUsername, SysUserDO::getId))
        .stream()
        .map(user -> toDto(user, true))
        .toList();
  }

  /** BD picker. The controller declares {@code kol:view}; the rows are the usable BDs. */
  public List<BdUserDTO> listSelectable() {
    return listUsable();
  }

  private static LambdaQueryWrapper<SysUserDO> columns() {
    return new LambdaQueryWrapper<SysUserDO>()
        .select(
            SysUserDO::getId,
            SysUserDO::getUsername,
            SysUserDO::getNickname,
            SysUserDO::getRole,
            SysUserDO::getDataScope,
            SysUserDO::getStatus);
  }

  private BdUserDTO toDto(SysUserDO user) {
    if (user == null) {
      return null;
    }
    return toDto(user, editorsAmong(List.of(user.getId())).contains(user.getId()));
  }

  private static BdUserDTO toDto(SysUserDO user, boolean kolEdit) {
    boolean usable =
        user.getRole() == Role.STAFF
            && user.getDataScope() == DataScope.OWN
            && Integer.valueOf(1).equals(user.getStatus())
            && kolEdit;
    return new BdUserDTO(user.getId(), user.getUsername(), user.getNickname(), usable);
  }

  /** Every user in this company who holds {@code code}. The tenant plugin limits the company. */
  private Set<Long> editorsWith(String code) {
    return permissions
        .selectList(
            new LambdaQueryWrapper<SysUserPermissionDO>()
                .select(SysUserPermissionDO::getUserId)
                .eq(SysUserPermissionDO::getPermissionCode, code))
        .stream()
        .map(SysUserPermissionDO::getUserId)
        .collect(Collectors.toSet());
  }

  private Set<Long> editorsAmong(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    return permissions
        .selectList(
            new LambdaQueryWrapper<SysUserPermissionDO>()
                .select(SysUserPermissionDO::getUserId)
                .in(SysUserPermissionDO::getUserId, ids)
                .eq(SysUserPermissionDO::getPermissionCode, Permission.KOL_EDIT.code()))
        .stream()
        .map(SysUserPermissionDO::getUserId)
        .collect(Collectors.toSet());
  }
}
