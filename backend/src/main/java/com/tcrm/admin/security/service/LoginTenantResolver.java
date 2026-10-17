package com.tcrm.admin.security.service;

import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.CurrentTenantResolver;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Current company of the logged-in administrator: the company bound to the token session (ADR-022),
 * never a request header or body. A missing Sa-Token context (startup, jobs, tests without a
 * request) means nobody is logged in. Database errors from the account lookup propagate.
 *
 * <p>The user context is resolved lazily. The tenant interceptor is built while MyBatis creates the
 * {@code SqlSessionFactory}, and loading an account needs a mapper from that factory. Within one
 * HTTP request the account comes from {@link CurrentUserContext}'s cache, so each statement does
 * not load it again.
 */
@Component
public class LoginTenantResolver implements CurrentTenantResolver {
  private final CurrentUserContext current;

  public LoginTenantResolver(@Lazy CurrentUserContext current) {
    this.current = current;
  }

  @Override
  public Long currentLoginTenantId() {
    return current.currentTenantId();
  }
}
