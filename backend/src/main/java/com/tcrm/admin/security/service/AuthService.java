package com.tcrm.admin.security.service;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.security.api.CompanyDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final AccountStore users;
  private final BCryptPasswordEncoder passwords;
  private final LoginFailures failures;
  private final CurrentUserContext current;
  private final CaptchaService captchas;
  private final CompanySwitchAudit switchAudit;
  // Verified for unknown or disabled accounts so every attempt pays one BCrypt comparison.
  private final String timingHash;

  public AuthService(
      AccountStore users,
      BCryptPasswordEncoder passwords,
      LoginFailures failures,
      CurrentUserContext current,
      CaptchaService captchas,
      CompanySwitchAudit switchAudit) {
    this.users = users;
    this.passwords = passwords;
    this.failures = failures;
    this.current = current;
    this.captchas = captchas;
    this.switchAudit = switchAudit;
    this.timingHash = passwords.encode(UUID.randomUUID().toString());
  }

  public record LoginResult(
      String tokenName, String token, long expiresIn, AccountDTO user, List<CompanyDTO> companies) {
    public LoginResult {
      companies = List.copyOf(companies);
    }
  }

  /**
   * The captcha is checked and consumed first, before the lock check and the password: a wrong or
   * expired captcha answers AUTH_CAPTCHA_INVALID and does not count as a login failure (design
   * document §3.3, ADR-008 addendum), and the lock state cannot be probed without solving one.
   *
   * <p>A member enters its last company when that one is still usable, otherwise the first usable
   * company by name (ADR-022). Without any usable company the login answers AUTH_ACCOUNT_DISABLED,
   * counts as a failure, and no token is issued.
   */
  public LoginResult login(
      String username, String password, String captchaId, String captchaCode, String clientIp) {
    captchas.verify(captchaId, captchaCode);
    failures.check(username, clientIp);
    AccountDTO user = users.getByUsername(username);
    boolean passwordMatches =
        passwords.matches(password, user == null ? timingHash : user.passwordHash());
    if (user == null || !Integer.valueOf(1).equals(user.status()) || !passwordMatches) {
      failures.fail(username, clientIp);
      throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
    // Bind the version read before the password check: if a reset committed in between, this
    // token carries the old version and the first request with it is rejected.
    int credentialVersion = user.credentialVersion();
    List<CompanyDTO> companies = List.of();
    if (user.role() != Role.PLATFORM_ADMIN) {
      companies = users.companies(user.id());
      Long tenantId = chooseCompany(user.tenantId(), companies);
      if (tenantId == null) {
        // Still counted, so the distinct answer cannot be probed faster than a wrong password.
        failures.fail(username, clientIp);
        throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
      }
      if (!tenantId.equals(user.tenantId()) || !user.tenantEnabled()) {
        user = users.getById(user.id(), tenantId);
      }
    }
    failures.clear(username, clientIp);
    user = user.withLastLoginTime(Instant.now());
    users.recordLogin(user.id(), user.tenantId(), user.lastLoginTime());
    StpUtil.login(user.id());
    StpUtil.getTokenSession().set(CurrentUserContext.CREDENTIAL_VERSION, credentialVersion);
    if (user.tenantId() != null) {
      StpUtil.getTokenSession().set(CurrentUserContext.CURRENT_TENANT, user.tenantId());
    }
    return new LoginResult(
        StpUtil.getTokenName(),
        StpUtil.getTokenValue(),
        StpUtil.getTokenTimeout(),
        user,
        companies);
  }

  /** The last company when it is among the usable ones, else the first by name; null if none. */
  static Long chooseCompany(Long lastTenantId, List<CompanyDTO> companies) {
    if (companies.isEmpty()) {
      return null;
    }
    return companies.stream()
        .map(CompanyDTO::tenantId)
        .filter(id -> id.equals(lastTenantId))
        .findFirst()
        .orElse(companies.getFirst().tenantId());
  }

  /** Usable companies of the current account, by name; empty for a platform administrator. */
  public List<CompanyDTO> companies(AccountDTO user) {
    return user.role() == Role.PLATFORM_ADMIN ? List.of() : users.companies(user.id());
  }

  /**
   * Moves this token to another company of the same person. The company must be one of the
   * account's usable companies (403 AUTH_COMPANY_NOT_MEMBER); a platform administrator has none
   * (403 AUTH_FORBIDDEN). The switch is audited in the target company with the source company ID.
   * Other tokens of the same person keep their company.
   */
  public AccountDTO switchCompany(long tenantId) {
    AccountDTO user = current.user();
    if (user.role() == Role.PLATFORM_ADMIN) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    if (users.companies(user.id()).stream().noneMatch(c -> c.tenantId() == tenantId)) {
      throw new BusinessException(ErrorCode.AUTH_COMPANY_NOT_MEMBER);
    }
    current.switchTo(tenantId);
    users.recordSwitch(user.id(), tenantId, Instant.now());
    switchAudit.switched(new CompanySwitchAudit.Switched(user.tenantId()));
    return current.user();
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
