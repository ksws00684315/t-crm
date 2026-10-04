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
  void shouldAvoidDefaultAccount_whenCredentialsAreMissing() {
    when(users.selectCount(any())).thenReturn(0L);
    new BootstrapAdmin(users, passwords, "", "").run(null);
    new BootstrapAdmin(users, passwords, "test-admin", "").run(null);
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
