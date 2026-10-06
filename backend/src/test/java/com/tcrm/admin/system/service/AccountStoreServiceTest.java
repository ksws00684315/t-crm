package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.time.Instant;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDO.class);
    var users = mock(SysUserMapper.class);
    var store = new AccountStoreService(users);
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
