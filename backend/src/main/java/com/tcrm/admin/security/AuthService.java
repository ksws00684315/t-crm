package com.tcrm.admin.security;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcrm.admin.common.BusinessException;
import com.tcrm.admin.system.*;
import java.time.Instant;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final SysUserMapper users;
  private final BCryptPasswordEncoder passwords;
  private final LoginFailures failures;
  private final CurrentUserContext current;

  public AuthService(
      SysUserMapper users,
      BCryptPasswordEncoder passwords,
      LoginFailures failures,
      CurrentUserContext current) {
    this.users = users;
    this.passwords = passwords;
    this.failures = failures;
    this.current = current;
  }

  public record LoginResult(String tokenName, String token, long expiresIn, UserInfo user) {}

  public LoginResult login(String username, String password) {
    failures.check(username);
    SysUser user = users.selectOne(new QueryWrapper<SysUser>().apply("lower(username) = lower({0})", username));
    if (user == null
        || !Integer.valueOf(1).equals(user.status)
        || !passwords.matches(password, user.passwordHash)) {
      failures.fail(username);
      throw new BusinessException(401, "Invalid username or password");
    }
    failures.clear(username);
    user.lastLoginTime = Instant.now();
    users.updateById(user);
    StpUtil.login(user.id);
    return new LoginResult(
        StpUtil.getTokenName(),
        StpUtil.getTokenValue(),
        StpUtil.getTokenTimeout(),
        UserInfo.from(user));
  }

  public void password(String oldPassword, String newPassword) {
    SysUser user = current.user();
    if (!passwords.matches(oldPassword, user.passwordHash))
      throw new BusinessException(400, "Old password is incorrect");
    user.passwordHash = passwords.encode(newPassword);
    users.updateById(user);
    StpUtil.logout(user.id);
  }
}
