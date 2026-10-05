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
  private static final String CACHE = CurrentUserContext.class.getName() + ".accounts";
  private static final int SCOPE = RequestAttributes.SCOPE_REQUEST;
  private final AccountStore users;

  public CurrentUserContext(AccountStore users) {
    this.users = users;
  }

  /**
   * Returns the current enabled account; throws NotLoginException or AUTH_ACCOUNT_DISABLED
   * otherwise. Within one HTTP request the account is loaded once and reused.
   */
  public AccountDTO user() {
    Long id = StpUtil.getLoginIdAsLong();
    AccountDTO user = load(id);
    if (user == null || !Integer.valueOf(1).equals(user.status())) {
      StpUtil.kickout(id);
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }
    return user;
  }

  /**
   * Returns the role names of the given account for Sa-Token's role checks; empty when the id is
   * not numeric or the account is missing or disabled. Unlike {@link #user()} it neither throws nor
   * ends sessions, and it answers for the requested id, not for the caller.
   */
  public List<String> roleNames(Object loginId) {
    Long id = toId(loginId);
    AccountDTO user = id == null ? null : load(id);
    if (user == null || !Integer.valueOf(1).equals(user.status())) {
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
