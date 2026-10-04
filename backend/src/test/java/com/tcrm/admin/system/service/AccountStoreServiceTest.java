package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountStoreServiceTest {
  @Test
  void shouldReturnAccountSnapshot_whenUserExists() {
    var users = mock(SysUserMapper.class);
    var u = new SysUserDO();
    u.setId(7L);
    u.setUsername("operator");
    u.setPasswordHash("test-hash");
    u.setRole(Role.OPERATOR);
    u.setDataScope(DataScope.OWN);
    u.setStatus(1);
    when(users.selectOne(any())).thenReturn(u);
    when(users.selectById(7L)).thenReturn(u);
    var store = new AccountStoreService(users);
    assertThat(store.getByUsername("OPERATOR").username()).isEqualTo("operator");
    assertThat(store.getById(7L).passwordHash()).isEqualTo("test-hash");
    assertThat(store.getById(8L)).isNull();
  }

  @Test
  void shouldUpdateOnlyChangedAccountFields_whenRecordingLoginAndPassword() {
    var users = mock(SysUserMapper.class);
    var store = new AccountStoreService(users);
    var instant = Instant.parse("2026-10-04T04:00:00Z");
    store.updateLastLoginTime(7L, instant);
    store.updatePassword(7L, "new-test-hash");
    var changes = org.mockito.ArgumentCaptor.forClass(SysUserDO.class);
    verify(users, times(2)).updateById(changes.capture());
    assertThat(changes.getAllValues().getFirst().getLastLoginTime()).isEqualTo(instant);
    assertThat(changes.getAllValues().getFirst().getPasswordHash()).isNull();
    assertThat(changes.getAllValues().get(1).getPasswordHash()).isEqualTo("new-test-hash");
  }
}
