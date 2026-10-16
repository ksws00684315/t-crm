package com.tcrm.admin.security.api;

import cn.dev33.satoken.exception.InvalidContextException;
import cn.dev33.satoken.exception.NotWebContextException;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Resolves the current administrator from the login state and rejects disabled or deleted accounts.
 * The current company lives in the token session ({@link #CURRENT_TENANT}, ADR-022), so browser
 * tabs sharing one token share one company; it is never taken from a request header or body.
 */
@Component
public class CurrentUserContext {
  /** Token-session attribute holding the credential version the token was issued for. */
  public static final String CREDENTIAL_VERSION = "credentialVersion";

  /** Token-session attribute holding the company the token currently works in. */
  public static final String CURRENT_TENANT = "currentTenantId";

  private static final String CACHE = CurrentUserContext.class.getName() + ".current";
  private static final String VERIFIED = CurrentUserContext.class.getName() + ".verified";
  private static final int SCOPE = RequestAttributes.SCOPE_REQUEST;
  private final AccountStore users;

  public CurrentUserContext(AccountStore users) {
    this.users = users;
  }

  /**
   * Returns the current enabled account in its current company; throws NotLoginException,
   * AUTH_ACCOUNT_DISABLED (account, membership or company disabled, which ends only this token), or
   * AUTH_UNAUTHORIZED when the token was issued for an older credential version (the password
   * changed since). Within one HTTP request the account is loaded and checked once.
   */
  public AccountDTO user() {
    Long id = StpUtil.getLoginIdAsLong();
    Loaded loaded = current(id);
    AccountDTO user = loaded == null ? null : loaded.account();
    if (!usable(user)) {
      StpUtil.logoutByTokenValue(StpUtil.getTokenValue());
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }
    requireCurrentCredentials(loaded);
    return user;
  }

  /**
   * Rejects a token bound to another credential version, or to none (tokens issued before the
   * version existed). This also covers a password change whose Redis logout failed and a login that
   * raced with a reset: such tokens carry the old version.
   */
  private void requireCurrentCredentials(Loaded loaded) {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null && Boolean.TRUE.equals(request.getAttribute(VERIFIED, SCOPE))) {
      return;
    }
    if (!(loaded.boundVersion() instanceof Number version)
        || version.intValue() != loaded.account().credentialVersion()) {
      StpUtil.logoutByTokenValue(StpUtil.getTokenValue());
      throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
    }
    if (request != null) {
      request.setAttribute(VERIFIED, Boolean.TRUE, SCOPE);
    }
  }

  /**
   * Returns the role names of the given account for Sa-Token's role checks; empty when the id is
   * not numeric or the account is missing, disabled or unusable in its company. For the logged-in
   * account the role is the one in the current company; for any other id, the one in its last
   * company. Unlike {@link #user()} it neither throws nor ends sessions.
   */
  public List<String> roleNames(Object loginId) {
    AccountDTO user = forLoginId(loginId);
    return usable(user) ? List.of(user.role().name()) : List.of();
  }

  /**
   * Permission codes of the given account for Sa-Token, resolved like {@link #roleNames(Object)}. A
   * super administrator receives every code, a staff account the codes granted in that company, and
   * anyone else none. Shares the per-request cache with {@link #user()}.
   */
  public List<String> permissionNames(Object loginId) {
    AccountDTO user = forLoginId(loginId);
    return usable(user) ? user.effectivePermissions() : List.of();
  }

  /**
   * Company of the logged-in token without status checks, for the tenant line; null when nobody is
   * logged in, for a platform administrator, or when the account is missing.
   */
  public Long currentTenantId() {
    Long id = loginId();
    if (id == null) {
      return null;
    }
    Loaded loaded = current(id);
    return loaded == null ? null : loaded.account().tenantId();
  }

  /**
   * Binds this token to {@code tenantId} and drops the request's cached account, so later lookups
   * in the same request already see the new company. Callers must have checked the membership.
   */
  public void switchTo(long tenantId) {
    StpUtil.getTokenSession().set(CURRENT_TENANT, tenantId);
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null) {
      request.removeAttribute(CACHE, SCOPE);
    }
  }

  /**
   * Ends the tokens of {@code userId} that currently work in {@code tenantId}; tokens of the same
   * person in other companies stay valid. Tokens without a bound company resolve to the account's
   * last company and are rejected on their next request if that membership is no longer usable.
   */
  public static void logoutCompany(long userId, long tenantId) {
    for (String token : StpUtil.getTokenValueListByLoginId(userId)) {
      SaSession session = StpUtil.getStpLogic().getTokenSessionByToken(token, false);
      Object bound = session == null ? null : session.get(CURRENT_TENANT);
      if (bound instanceof Number company && company.longValue() == tenantId) {
        StpUtil.logoutByTokenValue(token);
      }
    }
  }

  private static boolean usable(AccountDTO user) {
    return user != null
        && Integer.valueOf(1).equals(user.status())
        && user.tenantEnabled()
        && user.role() != null;
  }

  private AccountDTO forLoginId(Object loginId) {
    Long id = toId(loginId);
    if (id == null) {
      return null;
    }
    if (id.equals(loginId())) {
      Loaded loaded = current(id);
      return loaded == null ? null : loaded.account();
    }
    return users.getById(id);
  }

  private static Long toId(Object loginId) {
    try {
      return Long.valueOf(String.valueOf(loginId));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The logged-in id; null without a login or without a Sa-Token context (jobs, startup). */
  private static Long loginId() {
    try {
      return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
    } catch (InvalidContextException | NotWebContextException e) {
      return null;
    }
  }

  /**
   * The logged-in account in the token's current company, read with the token session once per
   * request. A token without a bound company (issued before ADR-022) falls back to the account's
   * last company, which is then bound to the token so it keeps working without a new login.
   */
  private Loaded current(Long id) {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null
        && request.getAttribute(CACHE, SCOPE) instanceof Loaded cached
        && cached.account().id().equals(id)) {
      return cached;
    }
    SaSession session = StpUtil.getTokenSession();
    Object bound = session.get(CURRENT_TENANT);
    AccountDTO user;
    if (bound instanceof Number tenant) {
      user = users.getById(id, tenant.longValue());
    } else {
      user = users.getById(id);
      if (user != null && user.tenantId() != null) {
        session.set(CURRENT_TENANT, user.tenantId());
      }
    }
    if (user == null) {
      return null;
    }
    Loaded loaded = new Loaded(user, session.get(CREDENTIAL_VERSION));
    if (request != null) {
      request.setAttribute(CACHE, loaded, SCOPE);
    }
    return loaded;
  }

  /** Returns the current administrator ID; authentication and availability checks are enforced. */
  public Long id() {
    return user().id();
  }

  /**
   * Returns the current administrator role; authentication and availability checks are enforced.
   */
  public Role role() {
    return user().role();
  }

  /** Returns the current data scope; authentication and availability checks are enforced. */
  public DataScope dataScope() {
    return user().dataScope();
  }

  /**
   * Account with this id without status or credential checks, for names in audit rows. The
   * logged-in account is the cached one in its current company; any other id is read from the store
   * in its last company. Missing accounts return null.
   */
  public AccountDTO account(Long id) {
    if (id != null && id.equals(loginId())) {
      Loaded loaded = current(id);
      return loaded == null ? null : loaded.account();
    }
    return users.getById(id);
  }

  private record Loaded(AccountDTO account, Object boundVersion) {}
}
