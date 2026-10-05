package com.tcrm.admin.security.service;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final AccountStore users;
  private final BCryptPasswordEncoder passwords;
  private final LoginFailures failures;
  private final CurrentUserContext current;

  public AuthService(
      AccountStore users,
      BCryptPasswordEncoder passwords,
      LoginFailures failures,
      CurrentUserContext current) {
    this.users = users;
    this.passwords = passwords;
    this.failures = failures;
    this.current = current;
  }

  public record LoginResult(String tokenName, String token, long expiresIn, AccountDTO user) {}

  public LoginResult login(String username, String password) {
    failures.check(username);
    AccountDTO user = users.getByUsername(username);
    if (user == null
        || !Integer.valueOf(1).equals(user.status())
        || !passwords.matches(password, user.passwordHash())) {
      failures.fail(username);
      throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
    failures.clear(username);
    user = user.withLastLoginTime(Instant.now());
    users.updateLastLoginTime(user.id(), user.lastLoginTime());
    StpUtil.login(user.id());
    return new LoginResult(
        StpUtil.getTokenName(), StpUtil.getTokenValue(), StpUtil.getTokenTimeout(), user);
  }

  public void logout() {
    StpUtil.logout();
  }

  public void password(String oldPassword, String newPassword) {
    AccountDTO user = current.user();
    if (!passwords.matches(oldPassword, user.passwordHash())) {
      throw new BusinessException(ErrorCode.AUTH_OLD_PASSWORD_INCORRECT);
    }
    users.updatePassword(user.id(), passwords.encode(newPassword));
    StpUtil.logout(user.id());
  }
}
