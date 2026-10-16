package com.tcrm.admin.security.config;

import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards against "the page shows company A, the request lands in company B" (ADR-022). The admin UI
 * sends the company it displays in {@link #HEADER}; when that differs from the company bound to the
 * token session the request is rejected with 409 AUTH_COMPANY_SWITCHED before any handler runs. The
 * header only confirms: it never selects the company, and a request without it is not checked
 * (scripts, older pages). Auth and platform endpoints are excluded at registration.
 */
class CompanyHeaderInterceptor implements HandlerInterceptor {
  static final String HEADER = "X-Company-Id";
  static final String[] PATTERNS = {"/admin-api/**"};
  static final String[] EXCLUDED = {
    "/admin-api/auth/**", "/admin-api/platform/**", "/admin-api/integration/weidian/oauth/callback"
  };

  private final CurrentUserContext current;

  CompanyHeaderInterceptor(CurrentUserContext current) {
    this.current = current;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String shown = request.getHeader(HEADER);
    if (shown == null || shown.isBlank()) {
      return true;
    }
    Long tenantId = current.user().tenantId();
    if (tenantId == null || !String.valueOf(tenantId).equals(shown.strip())) {
      throw new BusinessException(ErrorCode.AUTH_COMPANY_SWITCHED);
    }
    return true;
  }
}
