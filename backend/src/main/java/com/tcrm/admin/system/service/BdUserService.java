package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.MemberRow;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * BD facade for other modules and the BD picker; exposes only ID, names and usability. A BD is
 * judged by its membership of the current company (ADR-022): enabled account and membership, STAFF
 * with OWN scope, and {@code kol:edit} granted in this company. Accounts that are not members of
 * the current company are unknown here.
 */
@Service
public class BdUserService implements BdUserApi {
  private final SysUserMapper users;
  private final SysUserTenantMapper members;
  private final SysUserPermissionMapper permissions;
  private final CurrentUserContext current;

  public BdUserService(
      SysUserMapper users,
      SysUserTenantMapper members,
      SysUserPermissionMapper permissions,
      CurrentUserContext current) {
    this.users = users;
    this.members = members;
    this.permissions = permissions;
    this.current = current;
  }

  @Override
  public BdUserDTO getById(Long id) {
    if (id == null) {
      return null;
    }
    long tenantId = tenantId();
    MemberRow member = members.getMember(id, tenantId);
    if (member == null) {
      return null;
    }
    return toDto(member, editorsAmong(List.of(id), tenantId).contains(id));
  }

  @Override
  public BdUserDTO lockById(Long id) {
    return id == null || members.lockMember(id, tenantId()) == null ? null : getById(id);
  }

  @Override
  public BdUserDTO getByUsername(String username) {
    if (username == null || username.isBlank()) {
      return null;
    }
    SysUserDO user =
        users.selectOne(
            new LambdaQueryWrapper<SysUserDO>()
                .select(SysUserDO::getId)
                .apply("lower(username) = lower({0})", username.strip()));
    return user == null ? null : getById(user.getId());
  }

  @Override
  public List<BdUserDTO> listByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    long tenantId = tenantId();
    List<MemberRow> rows = members.listMembers(tenantId, ids);
    Set<Long> editors = editorsAmong(rows.stream().map(MemberRow::getUserId).toList(), tenantId);
    return rows.stream().map(m -> toDto(m, editors.contains(m.getUserId()))).toList();
  }

  @Override
  public List<BdUserDTO> listUsable() {
    return members.listUsableBds(tenantId(), Permission.KOL_EDIT.code()).stream()
        .map(m -> toDto(m, true))
        .toList();
  }

  /** BD picker. The controller declares {@code kol:view}; the rows are the usable BDs. */
  public List<BdUserDTO> listSelectable() {
    return listUsable();
  }

  /**
   * The company in force: an explicit company scope (background jobs such as scheduled transfers)
   * or else the logged-in token's company.
   */
  private long tenantId() {
    Long scoped = TenantScope.companyId();
    return scoped != null ? scoped : current.user().tenantId();
  }

  private static BdUserDTO toDto(MemberRow member, boolean kolEdit) {
    boolean usable =
        member.getRole() == Role.STAFF
            && member.getDataScope() == DataScope.OWN
            && Integer.valueOf(1).equals(member.getStatus())
            && Integer.valueOf(1).equals(member.getAccountStatus())
            && kolEdit;
    return new BdUserDTO(
        member.getUserId(),
        member.getUsername(),
        member.getNickname(),
        usable,
        member.getRole(),
        member.getDataScope(),
        Integer.valueOf(1).equals(member.getStatus())
            && Integer.valueOf(1).equals(member.getAccountStatus()));
  }

  private Set<Long> editorsAmong(Collection<Long> ids, long tenantId) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    return permissions
        .selectList(
            new LambdaQueryWrapper<SysUserPermissionDO>()
                .select(SysUserPermissionDO::getUserId)
                .eq(SysUserPermissionDO::getTenantId, tenantId)
                .in(SysUserPermissionDO::getUserId, ids)
                .eq(SysUserPermissionDO::getPermissionCode, Permission.KOL_EDIT.code()))
        .stream()
        .map(SysUserPermissionDO::getUserId)
        .collect(Collectors.toSet());
  }
}
