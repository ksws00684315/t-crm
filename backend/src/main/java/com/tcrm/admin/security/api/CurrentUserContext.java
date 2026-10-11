package com.tcrm.admin.security.api;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Resolves the current administrator from the login state and rejects disabled or deleted accounts.
 */
@Component
public class CurrentUserContext {
  /** Token-session attribute holding the credential version the token was issued for. */
  public static final String CREDENTIAL_VERSION = "credentialVersion";

  private static final String CACHE = CurrentUserContext.class.getName() + ".accounts";
  private static final String VERIFIED = CurrentUserContext.class.getName() + ".verified";
  private static final int SCOPE = RequestAttributes.SCOPE_REQUEST;
  private final AccountStore users;

  public CurrentUserContext(AccountStore users) {
    this.users = users;
  }

  /**
   * Returns the current enabled account; throws NotLoginException, AUTH_ACCOUNT_DISABLED, or
   * AUTH_UNAUTHORIZED when the token was issued for an older credential version (the password
   * changed since). Within one HTTP request the account is loaded and checked once.
   */
  public AccountDTO user() {
    Long id = StpUtil.getLoginIdAsLong();
    AccountDTO user = load(id);
    if (user == null || !Integer.valueOf(1).equals(user.status()) || !user.tenantEnabled()) {
      StpUtil.kickout(id);
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }
    requireCurrentCredentials(user);
    return user;
  }

  /**
   * Rejects a token bound to another credential version, or to none (tokens issued before the
   * version existed). This also covers a password change whose Redis logout failed and a login that
   * raced with a reset: such tokens carry the old version.
   */
  private void requireCurrentCredentials(AccountDTO user) {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null && Boolean.TRUE.equals(request.getAttribute(VERIFIED, SCOPE))) {
      return;
    }
    Object bound = StpUtil.getTokenSession().get(CREDENTIAL_VERSION);
    if (!(bound instanceof Number version) || version.intValue() != user.credentialVersion()) {
      StpUtil.logoutByTokenValue(StpUtil.getTokenValue());
      throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
    }
    if (request != null) {
      request.setAttribute(VERIFIED, Boolean.TRUE, SCOPE);
    }
  }

  /**
   * Returns the role names of the given account for Sa-Token's role checks; empty when the id is
   * not numeric or the account is missing or disabled. Unlike {@link #user()} it neither throws nor
   * ends sessions, and it answers for the requested id, not for the caller.
   */
  public List<String> roleNames(Object loginId) {
    Long id = toId(loginId);
    AccountDTO user = id == null ? null : load(id);
    if (user == null || !Integer.valueOf(1).equals(user.status()) || !user.tenantEnabled()) {
      return List.of();
    }
    return List.of(user.role().name());
  }

  private static Long toId(Object loginId) {
    try {
      return Long.valueOf(String.valueOf(loginId));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private AccountDTO load(Long id) {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request == null) {
      return users.getById(id);
    }
    Map<Long, AccountDTO> cache = (Map<Long, AccountDTO>) request.getAttribute(CACHE, SCOPE);
    if (cache == null) {
      cache = new HashMap<>();
      request.setAttribute(CACHE, cache, SCOPE);
    }
    AccountDTO user = cache.get(id);
    if (user == null) {
      user = users.getById(id);
      if (user != null) {
        cache.put(id, user);
      }
    }
    return user;
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
}
