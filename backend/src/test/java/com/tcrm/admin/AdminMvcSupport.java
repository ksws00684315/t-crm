package com.tcrm.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.security.config.SecurityConfiguration;
import com.tcrm.admin.shared.CommonConfiguration;
import com.tcrm.admin.shared.GlobalExceptionHandler;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Standalone MockMvc with the production Sa-Token interceptor, role lookup, exception handler and
 * JSON settings, so controller slice tests exercise real role annotations.
 */
public final class AdminMvcSupport {
  public static final String TOKEN_HEADER = "X-Admin-Token";

  private AdminMvcSupport() {}

  /** Standalone MockMvc without the login interceptor (for {@code @PublicEndpoint} controllers). */
  public static MockMvc buildPublic(Object... controllers) {
    var builder = JsonMapper.builder();
    new CommonConfiguration().jsonCustomizer().customize(builder);
    return MockMvcBuilders.standaloneSetup(controllers)
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(
            new ByteArrayHttpMessageConverter(),
            new JacksonJsonHttpMessageConverter(builder.build()))
        .build();
  }

  /** Builds MockMvc around the controllers; the caller's role comes from {@link #actAs}. */
  public static MockMvc build(CurrentUserContext current, Object... controllers) {
    SaManager.setConfig(
        new SaTokenConfig()
            .setTokenName(TOKEN_HEADER)
            .setIsReadCookie(false)
            .setIsReadBody(false)
            .setIsPrint(false));
    SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
    var security =
        new SecurityConfiguration(current, new CurrentKolContext(mock(KolAccountStore.class)));
    security.adminStpLogic();
    SaManager.setStpInterface(security.roles());
    var builder = JsonMapper.builder();
    new CommonConfiguration().jsonCustomizer().customize(builder);
    return MockMvcBuilders.standaloneSetup(controllers)
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(
            new ByteArrayHttpMessageConverter(),
            new JacksonJsonHttpMessageConverter(builder.build()))
        .addInterceptors(
            new SaInterceptor(
                handler -> {
                  StpUtil.checkLogin();
                  current.user();
                }))
        .build();
  }

  /**
   * Makes {@code current} answer as the given administrator and returns a valid token. A staff
   * account with ALL scope receives the operator template plus {@code kol:edit}. A staff account
   * with OWN scope receives the BD template. Other roles receive no stored codes; a super
   * administrator still resolves to every code.
   */
  public static String actAs(CurrentUserContext current, long id, Role role, DataScope scope) {
    return actAs(current, id, role, scope, defaultPermissions(role, scope));
  }

  /**
   * Same as {@link #actAs(CurrentUserContext, long, Role, DataScope)} with an explicit code set.
   */
  public static String actAs(
      CurrentUserContext current, long id, Role role, DataScope scope, Set<String> permissions) {
    AccountDTO user =
        new AccountDTO(
            id,
            "user" + id,
            "test-hash",
            "User " + id,
            null,
            role,
            scope,
            1,
            null,
            null,
            0,
            null,
            null,
            true,
            permissions,
            null);
    when(current.user()).thenReturn(user);
    when(current.id()).thenReturn(id);
    when(current.role()).thenReturn(role);
    when(current.dataScope()).thenReturn(scope);
    when(current.roleNames(any())).thenReturn(List.of(role.name()));
    when(current.permissionNames(any())).thenReturn(user.effectivePermissions());
    return StpUtil.createLoginSession(id);
  }

  private static Set<String> defaultPermissions(Role role, DataScope scope) {
    if (role != Role.STAFF) {
      return Set.of();
    }
    if (scope == DataScope.OWN) {
      return Set.copyOf(PostTemplate.BD.permissionCodes());
    }
    Set<String> codes = new HashSet<>(PostTemplate.OPERATOR.permissionCodes());
    codes.add(Permission.KOL_EDIT.code());
    return Set.copyOf(codes);
  }
}
