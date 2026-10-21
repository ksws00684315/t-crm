package com.tcrm.admin.security.config;

import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Requires a KOL token, re-checks the account and company, and opens the KOL's company scope for
 * the whole request (design §3.4): every statement of a /kol-api request is filtered to that
 * company and cannot reach another one's rows. The scope is closed in afterCompletion.
 */
class KolApiInterceptor implements HandlerInterceptor {
  static final String[] PATTERNS = {"/kol-api/**"};
  static final String[] PUBLIC_PATHS = {
    "/kol-api/auth/login",
    "/kol-api/auth/login/select",
    "/kol-api/auth/activate",
    "/kol-api/auth/forgot",
    "/kol-api/auth/reset",
    "/kol-api/public/site-config",
    "/kol-api/register",
    "/kol-api/register/**"
  };

  private static final String SCOPE_ATTRIBUTE = KolApiInterceptor.class.getName() + ".scope";
  private final CurrentKolContext current;

  KolApiInterceptor(CurrentKolContext current) {
    this.current = current;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    StpKolUtil.checkLogin();
    var kol = current.kol();
    request.setAttribute(SCOPE_ATTRIBUTE, TenantScope.openCompany(kol.tenantId()));
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    var scope = (TenantScope.Scope) request.getAttribute(SCOPE_ATTRIBUTE);
    if (scope != null) {
      scope.close();
    }
  }
}
