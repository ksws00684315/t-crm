package com.tcrm.admin.security.config;

import com.tcrm.admin.security.api.CompanyStore;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.EditionScope;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

/**
 * Refuses a request whose controller the current company's edition does not include (ADR-038 §2,
 * task 143 §2.3): 403 {@code AUTH_EDITION_FORBIDDEN}. Registered after the company header check and
 * before {@code SaInterceptor}, so the answer does not depend on the account's role or permission
 * codes, and a wrong edition reports 403 rather than a redirect-like 401.
 *
 * <p>A controller without {@link EditionScope} belongs to {@link Edition#DISTRIBUTION} only, so
 * nothing new is reachable from {@link Edition#KOL_LIBRARY} unless someone declares it there.
 * Platform, external and public endpoints never reach this interceptor ({@link #PATTERNS} and
 * {@link #EXCLUDED}); a CORS preflight is passed through like the interceptors around it.
 */
public class EditionInterceptor implements HandlerInterceptor {
  /** Company endpoints only: platform management is not a company session and is not gated. */
  public static final String[] PATTERNS = {"/admin-api/**"};

  /** Paths registered before this interceptor that carry no company session or no login. */
  public static final String[] EXCLUDED = {
    "/admin-api/platform/**",
    "/admin-api/external/**",
    "/admin-api/auth/login",
    "/admin-api/auth/captchas",
    "/admin-api/public/site-config",
    "/admin-api/integration/weidian/oauth/callback"
  };

  private final CurrentUserContext current;
  private final CompanyStore companies;
  private final UrlPathHelper paths = new UrlPathHelper();

  public EditionInterceptor(CurrentUserContext current, CompanyStore companies) {
    this.current = current;
    this.companies = companies;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!requiresCompanyEdition(paths.getPathWithinApplication(request))) {
      return true;
    }
    // No handler means no endpoint of this application (static resource, unknown path): the
    // endpoint's own 404 answers instead.
    List<Edition> allowed =
        handler instanceof HandlerMethod method
            ? allowedEditions(method.getBeanType())
            : List.of(Edition.DISTRIBUTION);
    Long tenantId = current.currentTenantId();
    if (tenantId == null) {
      // Only a platform administrator has no company; its own endpoints are excluded above.
      return true;
    }
    if (!allowed.contains(companies.editionOf(tenantId))) {
      throw new BusinessException(ErrorCode.AUTH_EDITION_FORBIDDEN);
    }
    return true;
  }

  /** Editions a controller is open to; an undeclared controller is distribution only. */
  public static List<Edition> allowedEditions(Class<?> controller) {
    EditionScope scope = AnnotatedElementUtils.findMergedAnnotation(controller, EditionScope.class);
    return scope == null ? List.of(Edition.DISTRIBUTION) : List.of(scope.value());
  }

  /** True unless the path is one of {@link #EXCLUDED}; the edition is not checked for them. */
  public static boolean requiresCompanyEdition(String path) {
    for (String excluded : EXCLUDED) {
      if (excluded.endsWith("/**")) {
        if (path.startsWith(excluded.substring(0, excluded.length() - 3))) {
          return false;
        }
      } else if (path.equals(excluded)) {
        return false;
      }
    }
    return path.startsWith("/admin-api/");
  }
}
