package com.tcrm.admin.security.api;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Resolves the current administrator from the login state and rejects disabled or deleted accounts.
 */
@Component
public class CurrentUserContext {
  private final AccountStore users;

  public CurrentUserContext(AccountStore users) {
    this.users = users;
  }

  /**
   * Returns the current enabled account; throws NotLoginException or AUTH_ACCOUNT_DISABLED
   * otherwise.
   */
  public AccountDTO user() {
    Long id = StpUtil.getLoginIdAsLong();
    AccountDTO user = users.getById(id);
    if (user == null || !Integer.valueOf(1).equals(user.status())) {
      StpUtil.kickout(id);
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
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
