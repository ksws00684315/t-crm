package com.tcrm.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.common.*;
import com.tcrm.admin.security.*;
import com.tcrm.admin.system.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

class SecurityMvcTest {
  private MockMvc mvc;
  private UserService users;
  private CurrentUserContext current;
  private String token;

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
    SysUser actor = new SysUser();
    actor.id = 1L;
    actor.username = "operator";
    actor.status = 1;
    actor.role = Role.OPERATOR;
    when(current.user()).thenReturn(actor);
    when(current.role()).thenReturn(Role.OPERATOR);
    var security = new SecurityConfiguration(current);
    security.adminStpLogic();
    SaManager.setStpInterface(security.roles());
    token = StpUtil.createLoginSession(1L);
    var builder = JsonMapper.builder();
    new CommonConfiguration().jsonCustomizer().customize(builder);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new UserController(users),
                new AuthController(mock(AuthService.class), current),
                new ErrorController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build()))
            .addInterceptors(
                new SaInterceptor(
                    handler -> {
                      StpUtil.checkLogin();
                      current.user();
                    }))
            .build();
  }

  @Test
  void roleAnnotationRejectsOperatorAndAllowsSuperAdmin() throws Exception {
    mvc.perform(get("/admin-api/system/user").header("X-Admin-Token", token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
    verifyNoInteractions(users);
    when(current.role()).thenReturn(Role.SUPER_ADMIN);
    when(users.list(anyLong(), anyLong(), any(), any(), any()))
        .thenReturn(new PageResult<>(List.of(), 0));
    mvc.perform(get("/admin-api/system/user").header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rows").isArray());
  }

  @Test
  void missingTokenReturnsConsistentEnvelope() throws Exception {
    mvc.perform(get("/admin-api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.msg").value("Authentication required"));
  }

  @Test
  void malformedJsonAndUnknownExceptionHaveSafeResponses() throws Exception {
    when(current.role()).thenReturn(Role.SUPER_ADMIN);
    mvc.perform(
            post("/admin-api/system/user")
                .header("X-Admin-Token", token)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    mvc.perform(get("/test/error").header("X-Admin-Token", token))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.msg").value("Internal server error"));
  }

  @Test
  void unknownPathReturnsNotFoundInsteadOfServerError() throws Exception {
    mvc.perform(get("/actuator/env"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void longIdsDecimalAndTimeHaveSafeJsonRepresentation() throws Exception {
    mvc.perform(get("/test/json").header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("9007199254740993"))
        .andExpect(jsonPath("$.amount").value("12.34"))
        .andExpect(jsonPath("$.time").value("2026-10-04T00:00:00Z"));
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
