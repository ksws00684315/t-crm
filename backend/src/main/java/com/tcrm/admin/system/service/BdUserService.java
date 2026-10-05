package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/** BD facade for other modules and the BD picker; exposes only ID, names and usability. */
@Service
public class BdUserService implements BdUserApi {
  private final SysUserMapper users;
  private final CurrentUserContext current;

  public BdUserService(SysUserMapper users, CurrentUserContext current) {
    this.users = users;
    this.current = current;
  }

  @Override
  public BdUserDTO getById(Long id) {
    return id == null ? null : toDto(users.selectOne(columns().eq(SysUserDO::getId, id)));
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
    return users.selectList(columns().in(SysUserDO::getId, ids)).stream()
        .map(BdUserService::toDto)
        .toList();
  }

  @Override
  public List<BdUserDTO> listUsable() {
    return users
        .selectList(
            columns()
                .eq(SysUserDO::getRole, Role.OPERATOR)
                .eq(SysUserDO::getDataScope, DataScope.OWN)
                .eq(SysUserDO::getStatus, 1)
                .orderByAsc(SysUserDO::getUsername, SysUserDO::getId))
        .stream()
        .map(BdUserService::toDto)
        .toList();
  }

  /** BD picker: super administrators and OPERATOR + ALL only; a BD (OWN) gets 403. */
  public List<BdUserDTO> listSelectable() {
    AccountDTO user = current.user();
    boolean allowed =
        user.role() == Role.SUPER_ADMIN
            || (user.role() == Role.OPERATOR && user.dataScope() == DataScope.ALL);
    if (!allowed) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
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

  private static BdUserDTO toDto(SysUserDO user) {
    if (user == null) {
      return null;
    }
    boolean usable =
        user.getRole() == Role.OPERATOR
            && user.getDataScope() == DataScope.OWN
            && Integer.valueOf(1).equals(user.getStatus());
    return new BdUserDTO(user.getId(), user.getUsername(), user.getNickname(), usable);
  }
}
