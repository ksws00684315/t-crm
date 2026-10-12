package com.tcrm.admin.security.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.StpKolUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityConfiguration implements WebMvcConfigurer {
  private final CurrentUserContext current;

  public SecurityConfiguration(CurrentUserContext current) {
    this.current = current;
  }

  @Bean
  @Primary
  public StpLogic adminStpLogic() {
    StpLogic logic = new StpLogic("admin");
    StpUtil.setStpLogic(logic);
    return logic;
  }

  @Bean
  public StpLogic kolStpLogic() {
    return StpKolUtil.STP;
  }

  @Bean
  public StpInterface roles() {
    return new StpInterface() {
      @Override
      public List<String> getPermissionList(Object id, String type) {
        return List.of();
      }

      @Override
      public List<String> getRoleList(Object id, String type) {
        return "admin".equals(type) ? current.roleNames(id) : List.of();
      }
    };
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // Company status is checked before @SaCheckRole. SaInterceptor runs annotations first, so a
    // disabled company whose role list is empty would otherwise be 403. This interceptor runs
    // first and answers 401, matching a disabled account.
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                StpUtil.checkLogin();
                current.user();
                return true;
              }
            })
        .addPathPatterns("/admin-api/**")
        .excludePathPatterns(
            "/admin-api/auth/login", "/admin-api/integration/weidian/oauth/callback");
    registry
        .addInterceptor(
            new SaInterceptor(
                handler -> {
                  StpUtil.checkLogin();
                  current.user();
                }))
        .addPathPatterns("/admin-api/**")
        .excludePathPatterns(
            "/admin-api/auth/login", "/admin-api/integration/weidian/oauth/callback");
  }
}
