package com.tcrm.admin.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CurrentUserContextTest {
  @Test
  void shouldExposeAccountIdentity_whenAccountIsEnabled() {
    var accounts = mock(AccountStore.class);
    var user =
        new AccountDTO(
            7L,
            "operator",
            "test-hash",
            "Operator",
            null,
            Role.OPERATOR,
            DataScope.OWN,
            1,
            null,
            null);
    when(accounts.getById(7L)).thenReturn(user);
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      var current = new CurrentUserContext(accounts);
      assertThat(current.user()).isEqualTo(user);
      assertThat(current.id()).isEqualTo(7L);
      assertThat(current.role()).isEqualTo(Role.OPERATOR);
      assertThat(current.dataScope()).isEqualTo(DataScope.OWN);
    }
  }

  @Test
  void shouldInvalidateSession_whenAccountIsMissingOrDisabled() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L))
        .thenReturn(
            null,
            new AccountDTO(
                7L,
                "operator",
                "test-hash",
                "Operator",
                null,
                Role.OPERATOR,
                DataScope.OWN,
                0,
                null,
                null));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      var current = new CurrentUserContext(accounts);
      for (int i = 0; i < 2; i++) {
        assertThatThrownBy(current::user)
            .isInstanceOf(BusinessException.class)
            .hasMessage(ErrorCode.AUTH_ACCOUNT_DISABLED.message());
      }
      stp.verify(() -> StpUtil.kickout(7L), org.mockito.Mockito.times(2));
    }
  }

  private static AccountDTO account(long id, Role role, int status) {
    return new AccountDTO(
        id, "user" + id, "test-hash", "User", null, role, DataScope.ALL, status, null, null);
  }

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void shouldQueryStoreOnce_whenSeveralLookupsHappenInOneRequest() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(account(7L, Role.OPERATOR, 1));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      var current = new CurrentUserContext(accounts);
      current.user();
      current.id();
      current.role();
      current.dataScope();
      verify(accounts, times(1)).getById(7L);
    }
  }

  @Test
  void shouldQueryStoreAgain_whenANewRequestStarts() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(account(7L, Role.OPERATOR, 1));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      var current = new CurrentUserContext(accounts);
      for (int i = 0; i < 2; i++) {
        RequestContextHolder.setRequestAttributes(
            new ServletRequestAttributes(new MockHttpServletRequest()));
        current.user();
      }
      verify(accounts, times(2)).getById(7L);
    }
  }

  @Test
  void shouldQueryStoreEveryTime_whenNoRequestIsBound() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(account(7L, Role.OPERATOR, 1));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      var current = new CurrentUserContext(accounts);
      current.user();
      current.user();
      verify(accounts, times(2)).getById(7L);
    }
  }

  @Test
  void shouldKeepRejectingWithoutRequery_whenDisabledAccountIsRepeatedInOneRequest() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(account(7L, Role.OPERATOR, 0));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      var current = new CurrentUserContext(accounts);
      assertThatThrownBy(current::user).isInstanceOf(BusinessException.class);
      assertThatThrownBy(current::user).isInstanceOf(BusinessException.class);
      verify(accounts, times(1)).getById(7L);
      stp.verify(() -> StpUtil.kickout(7L), times(2));
    }
  }

  @Test
  void shouldSeparateTokenNamespaces_whenCreatingKolLogic() {
    assertThat(StpKolUtil.STP.getLoginType()).isEqualTo("kol");
  }
}
