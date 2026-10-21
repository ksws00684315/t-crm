package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.KolAuthTokenDO;
import com.tcrm.admin.kol.service.KolMailTemplates.ResetLink;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.security.api.LoginFailureCounter;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * KOL authentication (task 12a, design §3.4): email + password login across companies, one-time
 * company selection, activation by invitation token, password reset, and password change. Login
 * failure throttling follows ADR-008 with the same thresholds as the administrator side, on
 * separate {@code kol:auth:fail:*} keys; the KOL portal deliberately has no image captcha (owner
 * decision 2026-10-16, design §3.4).
 */
@Service
public class KolAuthService {
  static final Duration SELECTION_TTL = Duration.ofMinutes(5);
  static final Duration RESET_TTL = Duration.ofMinutes(30);
  private static final String SELECTION_PREFIX = "kol:auth:selection:";
  private static final String FORGOT_EMAIL_PREFIX = "kol:auth:forgot:email:";
  private static final String FORGOT_IP_PREFIX = "kol:auth:forgot:ip:";

  private final KolAccountStore accounts;
  private final KolAuthTokens tokens;
  private final KolMails mails;
  private final BCryptPasswordEncoder passwords;
  private final LoginFailureCounter failures;
  private final CurrentKolContext current;
  private final ExternalAuditApi audit;
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final SystemConfigApi configs;
  // Verified for unknown or unactivated accounts so every attempt pays one BCrypt comparison.
  private final String timingHash;

  @Autowired
  public KolAuthService(
      KolAccountStore accounts,
      KolAuthTokens tokens,
      KolMails mails,
      BCryptPasswordEncoder passwords,
      @Qualifier("kolLoginFailures") LoginFailureCounter failures,
      CurrentKolContext current,
      ExternalAuditApi audit,
      StringRedisTemplate redis,
      ObjectMapper json,
      TransactionTemplate tx,
      SystemConfigApi configs) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mails = mails;
    this.passwords = passwords;
    this.failures = failures;
    this.current = current;
    this.audit = audit;
    this.redis = redis;
    this.json = json;
    this.tx = tx;
    this.configs = configs;
    this.timingHash = passwords.encode(UUID.randomUUID().toString());
  }

  KolAuthService(
      KolAccountStore accounts,
      KolAuthTokens tokens,
      KolMails mails,
      BCryptPasswordEncoder passwords,
      LoginFailureCounter failures,
      CurrentKolContext current,
      ExternalAuditApi audit,
      StringRedisTemplate redis,
      ObjectMapper json,
      TransactionTemplate tx) {
    this(accounts, tokens, mails, passwords, failures, current, audit, redis, json, tx, null);
  }

  /** Successful login answer for the KOL portal; {@code kol} mirrors {@code GET /kol-api/me}. */
  public record LoginResult(String tokenName, String token, long expiresIn, KolAccountDTO kol) {}

  /** Returned when the password matches accounts in several enabled companies (design §3.4.2). */
  public record CompanyChoice(Long tenantId, String tenantName) {}

  public record SelectionResult(String selectionToken, List<CompanyChoice> companies) {}

  /**
   * Login by email + password. Accounts of every company sharing the email are compared; a correct
   * password on a disabled KOL or disabled company answers AUTH_ACCOUNT_DISABLED, everything else
   * AUTH_INVALID_CREDENTIALS (task 12a §4.4).
   */
  public Object login(String email, String password, String clientIp) {
    String address = normalize(email);
    failures.check(address, clientIp);
    List<KolAccountDTO> matching = passwordMatches(address, password);
    if (matching.isEmpty()) {
      failures.fail(address, clientIp);
      throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
    List<KolAccountDTO> usable =
        matching.stream().filter(a -> a.enabled() && a.tenantEnabled()).toList();
    if (usable.isEmpty()) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }
    failures.clear(address, clientIp);
    if (usable.size() == 1) {
      return logIn(usable.getFirst());
    }
    return selection(usable);
  }

  /** Exchanges the one-time selection credential for the chosen company's login (design §3.4.2). */
  public LoginResult select(String selectionToken, long tenantId) {
    String raw = redis.opsForValue().getAndDelete(SELECTION_PREFIX + selectionToken);
    if (raw == null) {
      throw new BusinessException(ErrorCode.KOL_LOGIN_SELECTION_INVALID);
    }
    List<String> verified;
    try {
      verified =
          json.readValue(
              raw, json.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.KOL_LOGIN_SELECTION_INVALID);
    }
    KolAccountDTO chosen = null;
    for (String value : verified) {
      long id;
      try {
        id = Long.parseLong(value);
      } catch (NumberFormatException e) {
        throw new BusinessException(ErrorCode.KOL_LOGIN_SELECTION_INVALID);
      }
      KolAccountDTO account = accounts.getById(id);
      if (account != null && account.tenantId() == tenantId) {
        chosen = account;
        break;
      }
    }
    if (chosen == null) {
      // The credential never covered this company, or the account vanished meanwhile.
      throw new BusinessException(ErrorCode.KOL_LOGIN_SELECTION_INVALID);
    }
    return logIn(requireUsable(chosen));
  }

  public void logout() {
    StpKolUtil.logout();
  }

  public KolAccountDTO me() {
    return current.kol();
  }

  /**
   * Changes the password and returns a fresh login so the current session stays signed in: the
   * credential version bump invalidates every older token first (task 12a §4.3).
   */
  public LoginResult changePassword(String oldPassword, String newPassword, String clientIp) {
    KolAccountDTO kol = current.kol();
    if (!passwords.matches(oldPassword, kol.passwordHash())) {
      throw new BusinessException(ErrorCode.AUTH_OLD_PASSWORD_INCORRECT);
    }
    accounts.updatePassword(kol.id(), passwords.encode(newPassword));
    StpKolUtil.logout(kol.id());
    audit.recordKolOperation(
        kol.tenantId(),
        kol.email(),
        "KolAuth",
        "Change password",
        "/kol-api/me/password",
        clientIp,
        "{\"kolId\":\"" + kol.id() + "\"}");
    return logIn(accounts.getById(kol.id()));
  }

  /**
   * Activates a back-office-created KOL with an invitation token and signs them in. Used, expired
   * and unknown tokens, and invitations of already-activated or disabled accounts, answer
   * KOL_AUTH_TOKEN_INVALID.
   */
  public LoginResult activate(String token, String password, String clientIp) {
    KolAuthTokenDO row = tokens.findLive(token);
    KolAccountDTO kol = row == null ? null : accounts.getById(row.getKolId());
    if (kol == null || kol.activated() || !kol.enabled() || !kol.tenantEnabled()) {
      throw new BusinessException(ErrorCode.KOL_AUTH_TOKEN_INVALID);
    }
    TenantScope.runSystem(
        "consume invitation token and activate the KOL account",
        () ->
            tx.executeWithoutResult(
                status -> {
                  if (tokens.markUsed(row.getId()) == 0) {
                    throw new BusinessException(ErrorCode.KOL_AUTH_TOKEN_INVALID);
                  }
                  accounts.activate(kol.id(), passwords.encode(password));
                }));
    audit.recordKolOperation(
        kol.tenantId(),
        kol.email(),
        "KolAuth",
        "Activate",
        "/kol-api/auth/activate",
        clientIp,
        "{\"kolId\":\"" + kol.id() + "\"}");
    return logIn(requireUsable(accounts.getById(kol.id())));
  }

  /**
   * Password-reset request. Always reports success; a mail is sent only when the email has
   * activated accounts in enabled companies and both rate limits hold (3 mails per email per 10
   * minutes, 20 requests per IP per hour — task 12a §4.4). Each company gets its own link.
   */
  public void forgot(String email, String clientIp) {
    String address = normalize(email);
    if (!withinIpBudget(clientIp)) {
      return;
    }
    List<KolAccountDTO> eligible =
        accounts.listByEmail(address).stream()
            .filter(a -> a.activated() && a.enabled() && a.tenantEnabled())
            .toList();
    if (eligible.isEmpty() || !tryConsumeEmailBudget(address)) {
      return;
    }
    List<ResetLink> links =
        eligible.stream()
            .map(
                account ->
                    new ResetLink(
                        account.tenantName(),
                        tokens.issue(
                            account.tenantId(),
                            account.id(),
                            KolAuthTokenDO.PURPOSE_RESET,
                            Instant.now().plus(RESET_TTL))))
            .toList();
    mails.sendReset(address, links);
  }

  /**
   * Resets the password with a reset token: the credential version bump invalidates every issued
   * token, the remaining unused reset links of this KOL are invalidated too.
   */
  public void reset(String token, String password, String clientIp) {
    KolAuthTokenDO row = tokens.findLive(token);
    KolAccountDTO kol = row == null ? null : accounts.getById(row.getKolId());
    if (kol == null || !kol.activated() || !kol.enabled() || !kol.tenantEnabled()) {
      throw new BusinessException(ErrorCode.KOL_AUTH_TOKEN_INVALID);
    }
    TenantScope.runSystem(
        "consume reset token and update the KOL password",
        () ->
            tx.executeWithoutResult(
                status -> {
                  if (tokens.markUsed(row.getId()) == 0) {
                    throw new BusinessException(ErrorCode.KOL_AUTH_TOKEN_INVALID);
                  }
                  accounts.updatePassword(kol.id(), passwords.encode(password));
                  tokens.invalidateUnused(kol.id(), KolAuthTokenDO.PURPOSE_RESET);
                }));
    StpKolUtil.logout(kol.id());
    audit.recordKolOperation(
        kol.tenantId(),
        kol.email(),
        "KolAuth",
        "Reset password",
        "/kol-api/auth/reset",
        clientIp,
        "{\"kolId\":\"" + kol.id() + "\"}");
  }

  /**
   * Compares the password against every account of this email (one BCrypt each, accepted residual
   * cost in design §3.4.6). An unknown email or an unactivated account (which has no password)
   * still pays exactly one BCrypt comparison against a startup-random hash.
   */
  private List<KolAccountDTO> passwordMatches(String address, String password) {
    List<KolAccountDTO> all = accounts.listByEmail(address);
    if (all.isEmpty()) {
      passwords.matches(password, timingHash);
      return List.of();
    }
    List<KolAccountDTO> matching = new ArrayList<>();
    for (KolAccountDTO account : all) {
      String hash = account.passwordHash() == null ? timingHash : account.passwordHash();
      if (passwords.matches(password, hash) && account.passwordHash() != null) {
        matching.add(account);
      }
    }
    return matching;
  }

  private SelectionResult selection(List<KolAccountDTO> usable) {
    String selectionToken = SecureTokens.generate();
    // Stored as strings: the app-wide ObjectMapper writes every Long as a string anyway.
    List<String> ids = usable.stream().map(a -> String.valueOf(a.id())).toList();
    redis
        .opsForValue()
        .set(SELECTION_PREFIX + selectionToken, json.writeValueAsString(ids), SELECTION_TTL);
    return new SelectionResult(
        selectionToken,
        usable.stream().map(a -> new CompanyChoice(a.tenantId(), a.tenantName())).toList());
  }

  /**
   * Signs in a KOL this request just created and activated. The payload matches a password login.
   * Callers must already have checked that the account exists and is usable.
   */
  public LoginResult loginEstablished(long kolId) {
    KolAccountDTO kol = accounts.getById(kolId);
    if (kol == null) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }
    return logIn(requireUsable(kol));
  }

  /** Signs the account in and binds the credential version read before this call. */
  private LoginResult logIn(KolAccountDTO kol) {
    accounts.updateLastLoginTime(kol.id(), Instant.now());
    StpKolUtil.login(kol.id());
    // Bind the version read before this login: if a reset committed in between, this token carries
    // the old version and the first request with it is rejected.
    StpKolUtil.getTokenSession().set(CurrentKolContext.CREDENTIAL_VERSION, kol.credentialVersion());
    return new LoginResult(
        StpKolUtil.getTokenName(), StpKolUtil.getTokenValue(), StpKolUtil.getTokenTimeout(), kol);
  }

  private static KolAccountDTO requireUsable(KolAccountDTO kol) {
    if (!kol.enabled() || !kol.activated() || !kol.tenantEnabled()) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }
    return kol;
  }

  private boolean withinIpBudget(String clientIp) {
    Duration window = Duration.ofMinutes(configInt("kol.forgot.ip-window-minutes", 60));
    return increment(FORGOT_IP_PREFIX + SecureTokens.hash(clientIp), window)
        <= configInt("kol.forgot.ip-limit", 20);
  }

  private boolean tryConsumeEmailBudget(String email) {
    Duration window = Duration.ofMinutes(configInt("kol.forgot.email-window-minutes", 10));
    return increment(FORGOT_EMAIL_PREFIX + SecureTokens.hash(email), window)
        <= configInt("kol.forgot.email-limit", 3);
  }

  private int configInt(String key, int fallback) {
    return configs == null ? fallback : configs.getInt(key);
  }

  private long increment(String key, Duration window) {
    Long value = redis.opsForValue().increment(key);
    if (value == null) {
      return Long.MAX_VALUE;
    }
    Long ttl = redis.getExpire(key);
    if (ttl == null || ttl < 0) {
      redis.expire(key, window);
    }
    return value;
  }

  private static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
