package com.tcrm.admin;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.CommonConfiguration;
import com.tcrm.admin.shared.GlobalExceptionHandler;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Standalone MockMvc for KOL-portal controllers, mirroring {@link AdminMvcSupport}: the KOL login
 * interceptor of the production configuration plus the shared exception handler and JSON settings.
 */
public final class KolMvcSupport {
  public static final String TOKEN_HEADER = "X-Kol-Token";

  private KolMvcSupport() {}

  /** Builds MockMvc around the controllers; the caller's KOL comes from {@link #actAs}. */
  public static MockMvc build(CurrentKolContext current, Object... controllers) {
    SaManager.setConfig(
        new SaTokenConfig()
            .setTokenName(TOKEN_HEADER)
            .setIsReadCookie(false)
            .setIsReadBody(false)
            .setIsPrint(false));
    SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
    var builder = JsonMapper.builder();
    new CommonConfiguration().jsonCustomizer().customize(builder);
    return MockMvcBuilders.standaloneSetup(controllers)
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build()))
        .addInterceptors(
            new org.springframework.web.servlet.handler.MappedInterceptor(
                new String[] {"/kol-api/**"},
                new String[] {
                  "/kol-api/auth/login",
                  "/kol-api/auth/login/select",
                  "/kol-api/auth/activate",
                  "/kol-api/auth/forgot",
                  "/kol-api/auth/reset",
                  "/kol-api/register",
                  "/kol-api/register/**"
                },
                new SaInterceptor(
                    handler -> {
                      StpKolUtil.checkLogin();
                      current.kol();
                    })))
        .build();
  }

  /**
   * Makes {@code current} answer as the given KOL and returns a valid token, bound to the account's
   * credential version the way a real login does.
   */
  public static String actAs(CurrentKolContext current, KolAccountDTO kol) {
    org.mockito.Mockito.when(current.kol()).thenReturn(kol);
    String token = StpKolUtil.STP.createLoginSession(kol.id());
    StpKolUtil.STP
        .getTokenSessionByToken(token)
        .set(CurrentKolContext.CREDENTIAL_VERSION, kol.credentialVersion());
    return token;
  }
}
