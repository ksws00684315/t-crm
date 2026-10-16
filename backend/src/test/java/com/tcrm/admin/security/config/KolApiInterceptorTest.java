package com.tcrm.admin.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The /kol-api interceptor must open the KOL's company scope for the handler and close it
 * afterwards (design §3.4): a company-scoped statement inside the request can only see that
 * company's rows.
 */
class KolApiInterceptorTest {
  private final KolAccountStore accounts = org.mockito.Mockito.mock(KolAccountStore.class);
  private final CurrentKolContext current = new CurrentKolContext(accounts);
  private final HandlerInterceptor interceptor = new KolApiInterceptor(current);

  @BeforeEach
  void setUp() {
    SaManager.setConfig(
        new SaTokenConfig()
            .setTokenName("X-Kol-Token")
            .setIsReadCookie(false)
            .setIsReadBody(false)
            .setIsPrint(false));
    SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static KolAccountDTO kol(long tenantId) {
    return new KolAccountDTO(
        7L,
        "KOL0007",
        "Alice",
        "kol@example.com",
        "US",
        "hash",
        1,
        1,
        0,
        Instant.EPOCH,
        tenantId,
        "Tenant",
        true);
  }

  private static KolAccountDTO disabledKol() {
    return new KolAccountDTO(
        7L,
        "KOL0007",
        "Alice",
        "kol@example.com",
        "US",
        "hash",
        0,
        1,
        0,
        Instant.EPOCH,
        1L,
        "Tenant",
        true);
  }

  private String loginAs(long tenantId) {
    KolAccountDTO kol = kol(tenantId);
    org.mockito.Mockito.when(accounts.getById(7L)).thenReturn(kol);
    String token = StpKolUtil.STP.createLoginSession(7L);
    StpKolUtil.STP
        .getTokenSessionByToken(token)
        .set(CurrentKolContext.CREDENTIAL_VERSION, kol.credentialVersion());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Kol-Token", token);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return token;
  }

  @Test
  void shouldOpenTheKolCompanyScope_duringTheRequest_andCloseItAfterwards() throws Exception {
    loginAs(42L);
    HttpServletRequest request = new MockHttpServletRequest();
    HttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
    AtomicReference<Long> seen = new AtomicReference<>();
    assertThat(interceptor.preHandle(request, response, Object.class)).isTrue();
    seen.set(TenantScope.companyId());
    assertThat(seen.get()).isEqualTo(42L);
    interceptor.afterCompletion(request, response, Object.class, null);
    assertThat(TenantScope.companyId()).isNull();
  }

  @Test
  void shouldCloseTheScope_evenWhenTheHandlerFails() throws Exception {
    loginAs(1L);
    HttpServletRequest request = new MockHttpServletRequest();
    HttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
    interceptor.preHandle(request, response, Object.class);
    assertThat(TenantScope.companyId()).isEqualTo(1L);
    interceptor.afterCompletion(request, response, Object.class, new IllegalStateException());
    assertThat(TenantScope.companyId()).isNull();
  }

  @Test
  void shouldRejectRequestsWithoutAKolToken() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    assertThatThrownBy(
            () ->
                interceptor.preHandle(
                    new MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(),
                    Object.class))
        .isInstanceOf(cn.dev33.satoken.exception.NotLoginException.class);
    assertThat(TenantScope.companyId()).isNull();
  }

  @Test
  void shouldNotOpenScope_whenAccountChecksFail() {
    loginAs(1L);
    org.mockito.Mockito.when(accounts.getById(7L)).thenReturn(disabledKol());
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    // A fresh request context without the token header: checkLogin fails before any scope opens.
    assertThatThrownBy(
            () ->
                interceptor.preHandle(
                    new MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(),
                    Object.class))
        .isInstanceOf(cn.dev33.satoken.exception.NotLoginException.class);
    assertThat(TenantScope.companyId()).isNull();
  }

  @Test
  void shouldAnswerUnauthorized_whenTokenBelongsToAnotherNamespace() {
    // Administrator sessions live under the admin namespace; the KOL interceptor must reject them.
    loginAs(1L);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Kol-Token", "an-administrator-token-value");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    assertThatThrownBy(
            () ->
                interceptor.preHandle(
                    request,
                    new org.springframework.mock.web.MockHttpServletResponse(),
                    Object.class))
        .isInstanceOf(cn.dev33.satoken.exception.NotLoginException.class);
    assertThat(TenantScope.companyId()).isNull();
  }

  @Test
  void shouldKeepTwoRequestsFromDifferentCompaniesSeparate() throws Exception {
    // Simulate two sequential requests from KOLs of different companies on the same thread.
    loginAs(1L);
    var first = new MockHttpServletRequest();
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    interceptor.preHandle(first, response, Object.class);
    interceptor.afterCompletion(first, response, Object.class, null);

    loginAs(2L);
    var second = new MockHttpServletRequest();
    interceptor.preHandle(second, response, Object.class);
    assertThat(TenantScope.companyId()).isEqualTo(2L);
    interceptor.afterCompletion(second, response, Object.class, null);
    assertThat(TenantScope.companyId()).isNull();
  }

  @Test
  void shouldAnswerDisabled_whenKolWasDisabledAfterLogin() throws Exception {
    String token = loginAs(1L);
    org.mockito.Mockito.when(accounts.getById(7L)).thenReturn(disabledKol());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Kol-Token", token);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    assertThatThrownBy(
            () ->
                interceptor.preHandle(
                    request,
                    new org.springframework.mock.web.MockHttpServletResponse(),
                    Object.class))
        .isInstanceOf(BusinessException.class)
        .hasMessage(com.tcrm.admin.shared.ErrorCode.AUTH_ACCOUNT_DISABLED.message());
    assertThat(TenantScope.companyId()).isNull();
  }
}
