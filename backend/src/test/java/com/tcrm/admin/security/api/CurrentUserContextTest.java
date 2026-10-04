package com.tcrm.admin.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import org.junit.jupiter.api.Test;

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
      for (int i = 0; i < 2; i++)
        assertThatThrownBy(current::user)
            .isInstanceOf(BusinessException.class)
            .hasMessage(ErrorCode.AUTH_ACCOUNT_DISABLED.message());
      stp.verify(() -> StpUtil.kickout(7L), org.mockito.Mockito.times(2));
    }
  }

  @Test
  void shouldSeparateTokenNamespaces_whenCreatingKolLogic() {
    assertThat(StpKolUtil.STP.getLoginType()).isEqualTo("kol");
  }
}
