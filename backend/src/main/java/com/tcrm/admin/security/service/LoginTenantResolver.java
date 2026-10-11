package com.tcrm.admin.security.service;

import cn.dev33.satoken.exception.InvalidContextException;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.shared.CurrentTenantResolver;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Company of the logged-in administrator. A missing Sa-Token context (startup, jobs, tests without
 * a request) means nobody is logged in. Database errors from the account lookup propagate.
 *
 * <p>The account store is resolved lazily. The tenant interceptor is built while MyBatis creates
 * the {@code SqlSessionFactory}, and looking up an account needs a mapper from that factory.
 */
@Component
public class LoginTenantResolver implements CurrentTenantResolver {
  private final AccountStore accounts;

  public LoginTenantResolver(@Lazy AccountStore accounts) {
    this.accounts = accounts;
  }

  @Override
  public Long currentLoginTenantId() {
    if (!loggedIn()) {
      return null;
    }
    AccountDTO user = accounts.getById(StpUtil.getLoginIdAsLong());
    return user == null ? null : user.tenantId();
  }

  private static boolean loggedIn() {
    try {
      return StpUtil.isLogin();
    } catch (InvalidContextException e) {
      return false;
    }
  }
}
