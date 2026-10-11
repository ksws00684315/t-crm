package com.tcrm.admin.security.service;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final AccountStore users;
  private final BCryptPasswordEncoder passwords;
  private final LoginFailures failures;
  private final CurrentUserContext current;
  // Verified for unknown or disabled accounts so every attempt pays one BCrypt comparison.
  private final String timingHash;

  public AuthService(
      AccountStore users,
      BCryptPasswordEncoder passwords,
      LoginFailures failures,
      CurrentUserContext current) {
    this.users = users;
    this.passwords = passwords;
    this.failures = failures;
    this.current = current;
    this.timingHash = passwords.encode(UUID.randomUUID().toString());
  }

  public record LoginResult(String tokenName, String token, long expiresIn, AccountDTO user) {}

  public LoginResult login(String username, String password, String clientIp) {
    failures.check(username, clientIp);
    AccountDTO user = users.getByUsername(username);
    boolean passwordMatches =
        passwords.matches(password, user == null ? timingHash : user.passwordHash());
    if (user == null
        || !Integer.valueOf(1).equals(user.status())
        || !user.tenantEnabled()
        || !passwordMatches) {
      failures.fail(username, clientIp);
      throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
    failures.clear(username, clientIp);
    user = user.withLastLoginTime(Instant.now());
    users.updateLastLoginTime(user.id(), user.lastLoginTime());
    StpUtil.login(user.id());
    // Bind the version read before the password check: if a reset committed in between, this
    // token carries the old version and the first request with it is rejected.
    StpUtil.getTokenSession().set(CurrentUserContext.CREDENTIAL_VERSION, user.credentialVersion());
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
