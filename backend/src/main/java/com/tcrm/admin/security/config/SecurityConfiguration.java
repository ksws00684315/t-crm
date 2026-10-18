package com.tcrm.admin.security.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.security.service.LoginFailures;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityConfiguration implements WebMvcConfigurer {
  private final CurrentUserContext current;
  private final CurrentKolContext currentKol;

  public SecurityConfiguration(CurrentUserContext current, CurrentKolContext currentKol) {
    this.current = current;
    this.currentKol = currentKol;
  }

  @Bean
  @Primary
  public StpLogic adminStpLogic() {
    StpLogic logic = new StpLogic("admin");
    StpUtil.setStpLogic(logic);
    return logic;
  }

  /**
   * KOL namespace with its own Sa-Token config (task 12a §4.2): token read only from the {@code
   * X-Kol-Token} header, 7-day absolute lifetime, 3-day idle timeout, at most 10 tokens per KOL.
   * Registration in {@link SaManager} also routes {@code @SaCheckLogin(type = "kol")} here, so the
   * annotation sees the same settings as the interceptor.
   */
  @Bean
  public StpLogic kolStpLogic(
      @Value("${crm.kol-auth.token-name:X-Kol-Token}") String tokenName,
      @Value("${crm.kol-auth.timeout:604800}") long timeout,
      @Value("${crm.kol-auth.active-timeout:259200}") long activeTimeout,
      @Value("${crm.kol-auth.max-login-count:10}") int maxLoginCount) {
    SaTokenConfig config =
        new SaTokenConfig()
            .setTokenName(tokenName)
            .setTimeout(timeout)
            .setActiveTimeout(activeTimeout)
            .setMaxLoginCount(maxLoginCount)
            .setIsConcurrent(true)
            .setIsShare(false)
            .setIsReadCookie(false)
            .setIsReadBody(false)
            .setIsReadHeader(true)
            .setIsLog(false);
    StpKolUtil.STP.setConfig(config);
    SaManager.putStpLogic(StpKolUtil.STP);
    return StpKolUtil.STP;
  }

  /**
   * KOL login failures count on their own keys ({@code kol:auth:fail:*}); thresholds as ADR-008.
   */
  @Bean(name = "kolLoginFailures")
  public LoginFailures kolLoginFailures(StringRedisTemplate redis) {
    return new LoginFailures(redis, "kol:auth");
  }

  @Bean
  public StpInterface roles() {
    return new StpInterface() {
      @Override
      public List<String> getPermissionList(Object id, String type) {
        return "admin".equals(type) ? current.permissionNames(id) : List.of();
      }

      @Override
      public List<String> getRoleList(Object id, String type) {
        return "admin".equals(type) ? current.roleNames(id) : List.of();
      }
    };
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // Company status is checked before @SaCheckRole / @SaCheckPermission. SaInterceptor runs
    // annotations first, so a disabled company whose role list is empty would otherwise be 403.
    // This interceptor runs first and answers 401, matching a disabled account. A CORS preflight
    // (task 26 review: the promo-sheet upload sub-domain is a different origin from the console)
    // carries no token and is answered by Spring's PreFlightHandler — the interceptors pass it
    // through instead of rejecting it with 401 before any CORS header exists.
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (CorsUtils.isPreFlightRequest(request)) {
                  return true;
                }
                StpUtil.checkLogin();
                current.user();
                return true;
              }
            })
        .addPathPatterns("/admin-api/**")
        .excludePathPatterns(
            "/admin-api/auth/login",
            "/admin-api/auth/captchas",
            "/admin-api/integration/weidian/oauth/callback");
    // The displayed-company check runs after the status check (so it sees a valid login) and
    // before the role annotations, so a tab still showing the previous company gets 409 rather
    // than a 403 computed for the new company. A preflight sends no company header and passes
    // the null check below on its own.
    registry
        .addInterceptor(new CompanyHeaderInterceptor(current))
        .addPathPatterns(CompanyHeaderInterceptor.PATTERNS)
        .excludePathPatterns(CompanyHeaderInterceptor.EXCLUDED);
    registry
        .addInterceptor(
            new SaInterceptor(
                handler -> {
                  StpUtil.checkLogin();
                  current.user();
                }) {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler)
                  throws Exception {
                if (CorsUtils.isPreFlightRequest(request)) {
                  return true;
                }
                return super.preHandle(request, response, handler);
              }
            })
        .addPathPatterns("/admin-api/**")
        .excludePathPatterns(
            "/admin-api/auth/login",
            "/admin-api/auth/captchas",
            "/admin-api/integration/weidian/oauth/callback");
    // KOL mirror of the two interceptors above: status checks first, annotations second. The
    // company scope opened by KolApiInterceptor covers every statement of the request (design
    // §3.4) and is closed in afterCompletion; public endpoints are excluded and run in system
    // scope themselves.
    registry
        .addInterceptor(new KolApiInterceptor(currentKol))
        .addPathPatterns(KolApiInterceptor.PATTERNS)
        .excludePathPatterns(KolApiInterceptor.PUBLIC_PATHS);
    registry
        .addInterceptor(
            new SaInterceptor(
                handler -> {
                  StpKolUtil.checkLogin();
                  currentKol.kol();
                }))
        .addPathPatterns("/kol-api/**")
        .excludePathPatterns(KolApiInterceptor.PUBLIC_PATHS);
  }
}
