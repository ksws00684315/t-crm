package com.tcrm.admin.security.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.security.config.SecurityConfiguration;
import com.tcrm.admin.security.service.AuthService;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ClientIpResolver;
import com.tcrm.admin.shared.CommonConfiguration;
import com.tcrm.admin.shared.GlobalExceptionHandler;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.controller.UserController;
import com.tcrm.admin.system.service.UserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

class AuthControllerTest {
  private MockMvc mvc;
  private UserService users;
  private CurrentUserContext current;
  private String token;
  private AuthService auth;

  @BeforeEach
  void setup() {
    SaManager.setConfig(
        new SaTokenConfig()
            .setTokenName("X-Admin-Token")
            .setIsReadCookie(false)
            .setIsReadBody(false)
            .setIsPrint(false));
    SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
    current = mock(CurrentUserContext.class);
    users = mock(UserService.class);
    auth = mock(AuthService.class);
    var actor =
        new com.tcrm.admin.security.api.AccountDTO(
            1L,
            "operator",
            "test-hash",
            "Operator",
            null,
            Role.OPERATOR,
            DataScope.OWN,
            1,
            null,
            null);
    when(current.user()).thenReturn(actor);
    when(current.roleNames(any())).thenReturn(List.of("OPERATOR"));
    var security = new SecurityConfiguration(current);
    security.adminStpLogic();
    SaManager.setStpInterface(security.roles());
    token = StpUtil.createLoginSession(1L);
    var builder = JsonMapper.builder();
    new CommonConfiguration().jsonCustomizer().customize(builder);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new UserController(users),
                new AuthController(
                    auth, current, new ClientIpResolver("10.0.0.0/8", "X-Forwarded-For")),
                new ErrorController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build()))
            .addInterceptors(
                new SaInterceptor(
                    handler -> {
                      StpUtil.checkLogin();
                      current.user();
                    }))
            .addFilters(new com.tcrm.admin.shared.RequestTraceFilter())
            .build();
  }

  @Test
  void shouldRejectUserAdministration_whenRoleIsOperator() throws Exception {
    mvc.perform(get("/admin-api/system/users").header("X-Admin-Token", token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
    verifyNoInteractions(users);
  }

  @Test
  void shouldListUsers_whenRoleIsSuperAdministrator() throws Exception {
    when(current.roleNames(any())).thenReturn(List.of("SUPER_ADMIN"));
    when(users.page(anyLong(), anyLong(), any(), any(), any()))
        .thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    mvc.perform(get("/admin-api/system/users").header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isArray());
  }

  @Test
  void shouldMissingTokenReturnsConsistentEnvelope_whenRequested() throws Exception {
    mvc.perform(get("/admin-api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.msg").value("Authentication required"));
  }

  @Test
  void shouldHideInternalDetails_whenUnexpectedExceptionOccurs() throws Exception {
    mvc.perform(get("/test/error").header("X-Admin-Token", token))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.msg").value("Internal server error"));
  }

  @Test
  void shouldUnknownPathReturnsNotFoundInsteadOfServerError_whenRequested() throws Exception {
    mvc.perform(get("/actuator/env"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void shouldLongIdsDecimalAndTimeHaveSafeJsonRepresentation_whenRequested() throws Exception {
    mvc.perform(get("/test/json").header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("9007199254740993"))
        .andExpect(jsonPath("$.amount").value("12.34"))
        .andExpect(jsonPath("$.time").value("2026-10-04T00:00:00Z"));
  }

  @Test
  void shouldSerializeLoginUserWithoutCredentials_whenLoginSucceeds() throws Exception {
    var actor = current.user();
    when(auth.login("operator", "test-input", "198.51.100.8"))
        .thenReturn(new AuthService.LoginResult("X-Admin-Token", "unit-token", 28800, actor));
    mvc.perform(
            post("/admin-api/auth/login")
                .with(
                    request -> {
                      request.setRemoteAddr("10.1.2.3");
                      return request;
                    })
                .header("X-Forwarded-For", "192.0.2.77, 198.51.100.8")
                .header("X-Admin-Token", token)
                .contentType("application/json")
                .content("{\"username\":\"operator\",\"password\":\"test-input\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.user.id").value("1"))
        .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.data.token").value("unit-token"));
  }

  @Test
  void shouldReturnIdentity_whenOperatorIsAuthenticated() throws Exception {
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value("OPERATOR"));
  }

  @Test
  void shouldChangePassword_whenOperatorIsAuthenticated() throws Exception {
    mvc.perform(
            put("/admin-api/auth/password")
                .header("X-Admin-Token", token)
                .contentType("application/json")
                .content("{\"oldPassword\":\"test-input\",\"newPassword\":\"new-test-input\"}"))
        .andExpect(status().isOk());
    verify(auth).password("test-input", "new-test-input");
  }

  @Test
  void shouldLogout_whenOperatorIsAuthenticated() throws Exception {
    mvc.perform(post("/admin-api/auth/logout").header("X-Admin-Token", token))
        .andExpect(status().isOk());
    verify(auth).logout();
  }

  @Test
  void shouldRejectExpiredToken_whenTokenDeadlineHasPassed() throws Exception {
    ((SaTokenDaoDefaultImpl) SaManager.getSaTokenDao())
        .timedCache.expireMap.put(
            StpUtil.getStpLogic().splicingKeyTokenValue(token), System.currentTimeMillis() - 1);
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_UNAUTHORIZED"));
  }

  @Test
  void shouldRejectDisabledAccount_whenAnExistingTokenIsUsed() throws Exception {
    when(current.user())
        .thenThrow(new BusinessException(com.tcrm.admin.shared.ErrorCode.AUTH_ACCOUNT_DISABLED));
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_DISABLED"));
  }

  @Test
  void shouldReturnMethodNotAllowed_whenExistingPathUsesWrongMethod() throws Exception {
    mvc.perform(post("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.errorCode").value("COMMON_METHOD_NOT_ALLOWED"));
  }

  @Test
  void shouldReturnAllInvalidFieldsAndTraceHeader_whenCreateRequestIsInvalid() throws Exception {
    when(current.roleNames(any())).thenReturn(List.of("SUPER_ADMIN"));
    var result =
        mvc.perform(
                post("/admin-api/system/users")
                    .header("X-Admin-Token", token)
                    .contentType("application/json")
                    .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data.length()").value(5))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn();
    var node = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
    assertThat(node.get("traceId").asText())
        .isEqualTo(result.getResponse().getHeader("X-Trace-Id"));
    assertThat(org.slf4j.MDC.get("traceId")).isNull();
  }

  @Test
  void shouldRejectPageBeyondLimit_whenListingUsers() throws Exception {
    when(current.roleNames(any())).thenReturn(List.of("SUPER_ADMIN"));
    mvc.perform(get("/admin-api/system/users?page=10001").header("X-Admin-Token", token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(users);
  }

  @Test
  void shouldAcceptLastAllowedPage_whenListingUsers() throws Exception {
    when(current.roleNames(any())).thenReturn(List.of("SUPER_ADMIN"));
    when(users.page(anyLong(), anyLong(), any(), any(), any()))
        .thenReturn(new PageResult<>(List.of(), 0, 10000, 20));
    mvc.perform(get("/admin-api/system/users?page=10000").header("X-Admin-Token", token))
        .andExpect(status().isOk());
  }

  @RestController
  static class ErrorController {
    @GetMapping("/test/error")
    public R<Void> fail() {
      throw new IllegalStateException("private-internal-detail");
    }

    @GetMapping("/test/json")
    public Object json() {
      return new JsonValue(
          9007199254740993L, new BigDecimal("12.34"), Instant.parse("2026-10-04T00:00:00Z"));
    }
  }

  record JsonValue(Long id, BigDecimal amount, Instant time) {}
}
