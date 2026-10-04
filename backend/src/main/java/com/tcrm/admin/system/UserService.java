package com.tcrm.admin.system;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.common.*;
import com.tcrm.admin.security.CurrentUserContext;
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

  public PageResult<UserInfo> list(
      long page, long size, String username, Role role, Integer status) {
    var query =
        new QueryWrapper<SysUser>()
            .like(username != null && !username.isBlank(), "username", username)
            .eq(role != null, "role", role)
            .eq(status != null, "status", status)
            .orderByDesc("create_time", "id");
    var result = users.selectPage(new Page<>(page, size), query);
    return new PageResult<>(
        result.getRecords().stream().map(UserInfo::from).toList(), result.getTotal());
  }

  public UserInfo create(UserController.CreateRequest r) {
    validateScope(r.role(), r.dataScope());
    SysUser user = new SysUser();
    user.username = r.username();
    user.passwordHash = passwords.encode(r.password());
    user.nickname = r.nickname();
    user.email = r.email();
    user.role = r.role();
    user.dataScope = r.dataScope();
    user.status = 1;
    user.delFlag = 0;
    users.insert(user);
    return UserInfo.from(user);
  }

  public void edit(Long id, UserController.EditRequest r) {
    validateScope(r.role(), r.dataScope());
    SysUser user = require(id);
    if (id.equals(current.id()) && r.role() != Role.SUPER_ADMIN)
      throw new BusinessException(400, "Cannot remove your own super administrator role");
    user.nickname = r.nickname();
    user.email = r.email();
    user.role = r.role();
    user.dataScope = r.dataScope();
    users.updateById(user);
  }

  public void status(Long id, int status) {
    if (id.equals(current.id()) && status == 0)
      throw new BusinessException(400, "Cannot disable yourself");
    SysUser user = require(id);
    user.status = status;
    users.updateById(user);
    if (status == 0) StpUtil.kickout(id);
  }

  public void password(Long id, String password) {
    SysUser user = require(id);
    user.passwordHash = passwords.encode(password);
    users.updateById(user);
    StpUtil.logout(id);
  }

  private SysUser require(Long id) {
    SysUser user = users.selectById(id);
    if (user == null) throw new BusinessException(404, "User not found");
    return user;
  }

  private void validateScope(Role role, DataScope scope) {
    if (scope == DataScope.OWN && role != Role.OPERATOR)
      throw new BusinessException(400, "OWN data scope requires OPERATOR role");
  }
}
