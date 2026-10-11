package com.tcrm.admin.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.exception.InvalidContextException;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class LoginTenantResolverTest {
  private final AccountStore accounts = mock(AccountStore.class);
  private final CurrentUserContext current = new CurrentUserContext(accounts);
  private final LoginTenantResolver resolver = new LoginTenantResolver(current);

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void shouldTreatMissingContextAsLoggedOut() {
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::isLogin).thenThrow(new InvalidContextException("no context"));
      assertThat(resolver.currentLoginTenantId()).isNull();
      verifyNoInteractions(accounts);
    }
  }

  @Test
  void shouldReturnNull_whenNobodyIsLoggedIn() {
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::isLogin).thenReturn(false);
      assertThat(resolver.currentLoginTenantId()).isNull();
      verifyNoInteractions(accounts);
    }
  }

  @Test
  void shouldReturnTheLoggedInCompany_orNullForAPlatformAdministrator() {
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::isLogin).thenReturn(true);
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      when(accounts.getById(7L)).thenReturn(account(9L), account(null), null);
      assertThat(resolver.currentLoginTenantId()).isEqualTo(9L);
      assertThat(resolver.currentLoginTenantId()).isNull();
      assertThat(resolver.currentLoginTenantId()).isNull();
      verify(accounts, times(3)).getById(7L);
    }
  }

  @Test
  void shouldQueryTheAccountOnce_whenSeveralStatementsRunInOneRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::isLogin).thenReturn(true);
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      var session = mock(SaSession.class);
      when(session.get(CurrentUserContext.CREDENTIAL_VERSION)).thenReturn(0);
      stp.when(StpUtil::getTokenSession).thenReturn(session);
      when(accounts.getById(7L)).thenReturn(account(9L));
      assertThat(current.user().tenantId()).isEqualTo(9L);
      assertThat(resolver.currentLoginTenantId()).isEqualTo(9L);
      assertThat(resolver.currentLoginTenantId()).isEqualTo(9L);
      verify(accounts, times(1)).getById(7L);
    }
  }

  @Test
  void shouldPropagateAccountLookupFailures() {
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::isLogin).thenReturn(true);
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      when(accounts.getById(7L)).thenThrow(new IllegalStateException("db"));
      assertThatThrownBy(resolver::currentLoginTenantId)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("db");
    }
  }

  private static AccountDTO account(Long tenantId) {
    return new AccountDTO(
        7L,
        "operator",
        "test-hash",
        "Operator",
        null,
        Role.OPERATOR,
        DataScope.OWN,
        1,
        null,
        null,
        0,
        tenantId,
        tenantId == null ? null : "Acme",
        true);
  }
}
