package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.mapper.SysTenantMapper;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.model.SysTenantDO;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import java.time.Instant;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AccountStoreServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserPermissionDO.class);
  }

  @Test
  void shouldReturnAccountSnapshot_whenUserExists() {
    var users = mock(SysUserMapper.class);
    var u = new SysUserDO();
    u.setId(7L);
    u.setUsername("operator");
    u.setPasswordHash("test-hash");
    u.setRole(Role.STAFF);
    u.setDataScope(DataScope.OWN);
    u.setStatus(1);
    when(users.selectOne(any())).thenReturn(u);
    when(users.selectById(7L)).thenReturn(u);
    var permissions = mock(SysUserPermissionMapper.class);
    var store = new AccountStoreService(users, mock(SysTenantMapper.class), permissions);
    var loaded = store.getByUsername("OPERATOR");
    assertThat(loaded.username()).isEqualTo("operator");
    assertThat(loaded.tenantId()).isNull();
    assertThat(loaded.tenantName()).isNull();
    assertThat(loaded.tenantEnabled()).isTrue();
    assertThat(store.getById(7L).passwordHash()).isEqualTo("test-hash");
    assertThat(store.getById(8L)).isNull();
    verify(permissions, times(2)).selectList(any());
  }

  @Test
  void shouldMarkCompanyDisabled_whenTenantIsMissingOrStopped() {
    var users = mock(SysUserMapper.class);
    var tenants = mock(SysTenantMapper.class);
    var stopped = new SysTenantDO();
    stopped.setId(2L);
    stopped.setName("Stopped");
    stopped.setStatus(0);
    when(tenants.selectById(2L)).thenReturn(stopped);
    var member = new SysUserDO();
    member.setId(3L);
    member.setUsername("member");
    member.setRole(Role.SUPER_ADMIN);
    member.setDataScope(DataScope.ALL);
    member.setStatus(1);
    member.setTenantId(2L);
    member.setCredentialVersion(0);
    var missing = new SysUserDO();
    missing.setId(4L);
    missing.setUsername("gone");
    missing.setRole(Role.STAFF);
    missing.setDataScope(DataScope.OWN);
    missing.setStatus(1);
    missing.setTenantId(9L);
    missing.setCredentialVersion(0);
    when(users.selectById(3L)).thenReturn(member);
    when(users.selectById(4L)).thenReturn(missing);
    var store = new AccountStoreService(users, tenants, mock(SysUserPermissionMapper.class));
    assertThat(store.getById(3L).tenantEnabled()).isFalse();
    assertThat(store.getById(3L).tenantName()).isEqualTo("Stopped");
    assertThat(store.getById(4L).tenantEnabled()).isFalse();
    assertThat(store.getById(4L).tenantName()).isNull();
  }

  @Test
  void shouldUpdateOnlyChangedAccountFields_whenRecordingLoginAndPassword() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDO.class);
    var users = mock(SysUserMapper.class);
    var store =
        new AccountStoreService(
            users, mock(SysTenantMapper.class), mock(SysUserPermissionMapper.class));
    var instant = Instant.parse("2026-10-04T04:00:00Z");
    store.updateLastLoginTime(7L, instant);
    var login = org.mockito.ArgumentCaptor.forClass(SysUserDO.class);
    verify(users).updateById(login.capture());
    assertThat(login.getValue().getLastLoginTime()).isEqualTo(instant);
    assertThat(login.getValue().getPasswordHash()).isNull();

    store.updatePassword(7L, "new-test-hash");
    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<Wrapper<SysUserDO>> password =
        org.mockito.ArgumentCaptor.forClass(Wrapper.class);
    verify(users).update(isNull(), password.capture());
    assertThat(((LambdaUpdateWrapper<SysUserDO>) password.getValue()).getSqlSet())
        .contains("password_hash=", "credential_version = credential_version + 1")
        .doesNotContain("status", "role");
  }
}
