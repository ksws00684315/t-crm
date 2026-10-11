package com.tcrm.admin.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.exception.InvalidContextException;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import org.junit.jupiter.api.Test;

class LoginTenantResolverTest {
  private final AccountStore accounts = mock(AccountStore.class);
  private final LoginTenantResolver resolver = new LoginTenantResolver(accounts);

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
