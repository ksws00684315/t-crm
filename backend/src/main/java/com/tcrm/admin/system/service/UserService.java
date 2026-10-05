package com.tcrm.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

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
                username != null && !username.isBlank(), "username ILIKE {0}", "%" + username + "%")
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

  public void update(Long id, EditUserCommand r) {
    validateScope(r.role(), r.dataScope());
    SysUserDO user = require(id);
    if (id.equals(current.id()) && r.role() != Role.SUPER_ADMIN) {
      throw new BusinessException(ErrorCode.USER_CANNOT_REMOVE_SELF_ROLE);
    }
    user.setNickname(r.nickname());
    user.setEmail(r.email());
    user.setRole(r.role());
    user.setDataScope(r.dataScope());
    users.updateById(user);
  }

  public void updateStatus(Long id, int status) {
    if (id.equals(current.id()) && status == 0) {
      throw new BusinessException(ErrorCode.USER_CANNOT_DISABLE_SELF);
    }
    SysUserDO user = require(id);
    user.setStatus(status);
    users.updateById(user);
    if (status == 0) {
      StpUtil.kickout(id);
    }
  }

  public void updatePassword(Long id, String password) {
    SysUserDO user = require(id);
    user.setPasswordHash(passwords.encode(password));
    users.updateById(user);
    StpUtil.logout(id);
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
}
