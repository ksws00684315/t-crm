package com.tcrm.admin.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.security.api.Role;
import org.junit.jupiter.api.Test;

class SecurityConfigurationTest {
  private final AccountStore accounts = mock(AccountStore.class);
  private final CurrentUserContext current = new CurrentUserContext(accounts);
  private final cn.dev33.satoken.stp.StpInterface roles =
      new SecurityConfiguration(current, new CurrentKolContext(mock(KolAccountStore.class)))
          .roles();

  private static AccountDTO account(long id, Role role, int status) {
    return new AccountDTO(
        id,
        "user" + id,
        "test-hash",
        "User",
        null,
        role,
        DataScope.ALL,
        status,
        null,
        null,
        0,
        null,
        null,
        true);
  }

  @Test
  void shouldReturnRoleOfRequestedAccount_whenAdminTypeIsChecked() {
    when(accounts.getById(9L)).thenReturn(account(9L, Role.FINANCE, 1));
    assertThat(roles.getRoleList(9L, "admin")).containsExactly("FINANCE");
    assertThat(roles.getRoleList("9", "admin")).containsExactly("FINANCE");
    verify(accounts, org.mockito.Mockito.times(2)).getById(9L);
  }

  @Test
  void shouldReturnNoRoles_whenAccountIsMissingDisabledOrIdIsNotNumeric() {
    when(accounts.getById(8L)).thenReturn(null);
    when(accounts.getById(9L)).thenReturn(account(9L, Role.SUPER_ADMIN, 0));
    assertThat(roles.getRoleList(8L, "admin")).isEmpty();
    assertThat(roles.getRoleList(9L, "admin")).isEmpty();
    assertThat(roles.getRoleList("not-a-number", "admin")).isEmpty();
  }

  @Test
  void shouldReturnNoRolesAndSkipLookup_whenTypeIsNotAdmin() {
    assertThat(roles.getRoleList(9L, "kol")).isEmpty();
    assertThat(roles.getPermissionList(9L, "admin")).isEmpty();
    verifyNoInteractions(accounts);
  }
}
