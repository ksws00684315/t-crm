package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class BootstrapAdminTest {
  private final SysUserMapper users = mock(SysUserMapper.class);
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

  @Test
  void shouldFailStartup_whenBothBootstrapCredentialsAreMissing() {
    when(users.selectCount(any())).thenReturn(0L);
    assertThatThrownBy(() -> new BootstrapAdmin(users, passwords, "", "").run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TCRM_BOOTSTRAP_ADMIN_USERNAME")
        .hasMessageContaining("TCRM_BOOTSTRAP_ADMIN_PASSWORD");
    verify(users, never()).insert(any(SysUserDO.class));
  }

  @Test
  void shouldFailStartup_whenBootstrapUsernameIsBlank() {
    when(users.selectCount(any())).thenReturn(0L);
    assertThatThrownBy(
            () -> new BootstrapAdmin(users, passwords, "  ", "unit-test-password").run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TCRM_BOOTSTRAP_ADMIN_USERNAME")
        .satisfies(error -> assertThat(error.getMessage()).doesNotContain("unit-test-password"));
    verify(users, never()).insert(any(SysUserDO.class));
  }

  @Test
  void shouldFailStartup_whenBootstrapPasswordIsBlank() {
    when(users.selectCount(any())).thenReturn(0L);
    assertThatThrownBy(() -> new BootstrapAdmin(users, passwords, "test-admin", "  ").run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TCRM_BOOTSTRAP_ADMIN_PASSWORD");
    verify(users, never()).insert(any(SysUserDO.class));
  }

  @Test
  void shouldSkipBootstrap_whenSuperAdministratorExists() {
    when(users.selectCount(any())).thenReturn(1L);
    new BootstrapAdmin(users, passwords, "", "").run(null);
    verify(users, never()).insert(any(SysUserDO.class));
  }

  @Test
  void shouldRejectInvalidCredentials_whenBootstrapIsNeeded() {
    when(users.selectCount(any())).thenReturn(0L);
    assertThatThrownBy(
            () -> new BootstrapAdmin(users, passwords, "bad name", "test-input-password").run(null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new BootstrapAdmin(users, passwords, "test-admin", "short").run(null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldCreateHashedSuperAdministrator_whenCredentialsAreValid() {
    when(users.selectCount(any())).thenReturn(0L);
    new BootstrapAdmin(users, passwords, "test-admin", "unit-test-password").run(null);
    var saved = org.mockito.ArgumentCaptor.forClass(SysUserDO.class);
    verify(users).insert(saved.capture());
    assertThat(saved.getValue().getRole()).isEqualTo(Role.SUPER_ADMIN);
    assertThat(passwords.matches("unit-test-password", saved.getValue().getPasswordHash()))
        .isTrue();
  }
}
