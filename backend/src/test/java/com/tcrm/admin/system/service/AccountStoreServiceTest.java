package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.security.api.CompanyDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.mapper.SysTenantMapper;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.AccountType;
import com.tcrm.admin.system.model.MembershipRow;
import com.tcrm.admin.system.model.SysTenantDO;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import com.tcrm.admin.system.model.SysUserTenantDO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountStoreServiceTest {
  private final SysUserMapper users = mock(SysUserMapper.class);
  private final SysUserTenantMapper members = mock(SysUserTenantMapper.class);
  private final SysTenantMapper tenants = mock(SysTenantMapper.class);
  private final SysUserPermissionMapper permissions = mock(SysUserPermissionMapper.class);
  private final AccountStoreService store =
      new AccountStoreService(users, members, tenants, permissions);

  @BeforeAll
  static void tableInfo() {
    var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, SysUserPermissionDO.class);
    TableInfoHelper.initTableInfo(assistant, SysUserDO.class);
    TableInfoHelper.initTableInfo(assistant, SysUserTenantDO.class);
  }

  private static SysUserDO member(long id, Long lastTenantId) {
    var u = new SysUserDO();
    u.setId(id);
    u.setUsername("operator");
    u.setPasswordHash("test-hash");
    u.setRole(AccountType.MEMBER);
    u.setStatus(1);
    u.setCredentialVersion(2);
    u.setLastTenantId(lastTenantId);
    return u;
  }

  private static SysUserTenantDO membership(Role role, DataScope scope, int status) {
    var m = new SysUserTenantDO();
    m.setRole(role);
    m.setDataScope(scope);
    m.setPostTemplate(role == Role.STAFF ? PostTemplate.BD : null);
    m.setStatus(status);
    return m;
  }

  private static SysTenantDO tenant(long id, int status) {
    var t = new SysTenantDO();
    t.setId(id);
    t.setName("Company " + id);
    t.setStatus(status);
    return t;
  }

  @Test
  void shouldReturnIdentityInLastCompany_whenLoadedByUsernameOrLegacyId() {
    var u = member(7L, 2L);
    when(users.selectOne(any())).thenReturn(u);
    when(users.selectById(7L)).thenReturn(u);
    when(members.selectOne(any())).thenReturn(membership(Role.STAFF, DataScope.OWN, 1));
    when(tenants.selectById(2L)).thenReturn(tenant(2L, 1));
    var loaded = store.getByUsername("OPERATOR");
    assertThat(loaded.username()).isEqualTo("operator");
    assertThat(loaded.role()).isEqualTo(Role.STAFF);
    assertThat(loaded.dataScope()).isEqualTo(DataScope.OWN);
    assertThat(loaded.postTemplate()).isEqualTo(PostTemplate.BD);
    assertThat(loaded.tenantId()).isEqualTo(2L);
    assertThat(loaded.tenantName()).isEqualTo("Company 2");
    assertThat(loaded.tenantEnabled()).isTrue();
    assertThat(loaded.credentialVersion()).isEqualTo(2);
    assertThat(store.getById(7L).passwordHash()).isEqualTo("test-hash");
    assertThat(store.getById(8L)).isNull();
    verify(permissions, times(2)).selectList(any());
  }

  @Test
  void shouldReadRoleAndCodesOfRequestedCompany_whenLoadedWithCompany() {
    when(users.selectById(7L)).thenReturn(member(7L, 2L));
    when(members.selectOne(any())).thenReturn(membership(Role.SUPER_ADMIN, DataScope.ALL, 1));
    when(tenants.selectById(3L)).thenReturn(tenant(3L, 1));
    var loaded = store.getById(7L, 3L);
    assertThat(loaded.tenantId()).isEqualTo(3L);
    assertThat(loaded.role()).isEqualTo(Role.SUPER_ADMIN);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<SysUserPermissionDO>> codes = ArgumentCaptor.forClass(Wrapper.class);
    verify(permissions).selectList(codes.capture());
    // Codes are read for this account in this company only (ADR-022).
    assertThat(((LambdaQueryWrapper<SysUserPermissionDO>) codes.getValue()).getSqlSegment())
        .contains("user_id", "tenant_id");
  }

  @Test
  void shouldMarkCompanyUnusable_whenMembershipOrTenantIsMissingOrStopped() {
    when(users.selectById(3L)).thenReturn(member(3L, 2L));
    when(tenants.selectById(2L)).thenReturn(tenant(2L, 0));
    when(tenants.selectById(4L)).thenReturn(tenant(4L, 1));
    when(members.selectOne(any()))
        .thenReturn(
            membership(Role.SUPER_ADMIN, DataScope.ALL, 1),
            membership(Role.STAFF, DataScope.OWN, 0),
            null);
    var stoppedCompany = store.getById(3L, 2L);
    assertThat(stoppedCompany.tenantEnabled()).isFalse();
    assertThat(stoppedCompany.tenantName()).isEqualTo("Company 2");
    assertThat(store.getById(3L, 4L).tenantEnabled()).isFalse();
    var noMembership = store.getById(3L, 4L);
    assertThat(noMembership.tenantEnabled()).isFalse();
    assertThat(noMembership.role()).isNull();
    assertThat(noMembership.permissions()).isEmpty();
    assertThat(store.getById(3L, 9L).tenantName()).isNull();
  }

  @Test
  void shouldHaveNoCompany_whenPlatformAdministrator() {
    var admin = member(1L, null);
    admin.setRole(AccountType.PLATFORM_ADMIN);
    when(users.selectById(1L)).thenReturn(admin);
    var loaded = store.getById(1L);
    assertThat(loaded.role()).isEqualTo(Role.PLATFORM_ADMIN);
    assertThat(loaded.tenantId()).isNull();
    assertThat(loaded.tenantEnabled()).isTrue();
    assertThat(loaded.effectivePermissions()).isEmpty();
    verify(members, never()).selectOne(any());
  }

  @Test
  void shouldListOnlyUsableCompaniesInNameOrder_whenAskedForCompanies() {
    when(members.listMemberships(7L))
        .thenReturn(
            List.of(
                row(1L, "A", 1, Role.SUPER_ADMIN, 1),
                row(2L, "B", 0, Role.STAFF, 1),
                row(3L, "C", 1, Role.STAFF, 0),
                row(4L, "D", 1, Role.STAFF, 1)));
    assertThat(store.companies(7L))
        .containsExactly(
            new CompanyDTO(1L, "A", Role.SUPER_ADMIN), new CompanyDTO(4L, "D", Role.STAFF));
  }

  private static MembershipRow row(
      Long tenantId, String name, int tenantStatus, Role role, int status) {
    var r = new MembershipRow();
    r.setTenantId(tenantId);
    r.setTenantName(name);
    r.setTenantStatus(tenantStatus);
    r.setRole(role);
    r.setStatus(status);
    return r;
  }

  @Test
  void shouldUpdateOnlyChangedAccountFields_whenRecordingLoginAndPassword() {
    var instant = Instant.parse("2026-10-04T04:00:00Z");
    store.recordLogin(7L, 2L, instant);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<SysUserDO>> account = ArgumentCaptor.forClass(Wrapper.class);
    verify(users).update(isNull(), account.capture());
    assertThat(((LambdaUpdateWrapper<SysUserDO>) account.getValue()).getSqlSet())
        .contains("last_login_time=", "last_tenant_id=")
        .doesNotContain("password_hash", "status");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<SysUserTenantDO>> membership = ArgumentCaptor.forClass(Wrapper.class);
    verify(members).update(isNull(), membership.capture());
    var touched = (LambdaUpdateWrapper<SysUserTenantDO>) membership.getValue();
    assertThat(touched.getSqlSet()).contains("last_login_time=").doesNotContain("status", "role");
    assertThat(touched.getSqlSegment()).contains("user_id", "tenant_id");

    store.updatePassword(7L, "new-test-hash");
    verify(users, times(2)).update(isNull(), account.capture());
    assertThat(((LambdaUpdateWrapper<SysUserDO>) account.getValue()).getSqlSet())
        .contains("password_hash=", "credential_version = credential_version + 1")
        .doesNotContain("status", "role");
  }

  @Test
  void shouldNotTouchMemberships_whenPlatformAdministratorLogsIn() {
    store.recordLogin(1L, null, Instant.parse("2026-10-04T04:00:00Z"));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<SysUserDO>> account = ArgumentCaptor.forClass(Wrapper.class);
    verify(users).update(isNull(), account.capture());
    assertThat(((LambdaUpdateWrapper<SysUserDO>) account.getValue()).getSqlSet())
        .contains("last_login_time=")
        .doesNotContain("last_tenant_id");
    verify(members, never()).update(any(), any());
  }

  @Test
  void shouldMoveLastCompany_whenSwitchIsRecorded() {
    store.recordSwitch(7L, 3L, Instant.parse("2026-10-04T04:00:00Z"));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<SysUserDO>> account = ArgumentCaptor.forClass(Wrapper.class);
    verify(users).update(isNull(), account.capture());
    assertThat(((LambdaUpdateWrapper<SysUserDO>) account.getValue()).getSqlSet())
        .contains("last_tenant_id=")
        .doesNotContain("last_login_time");
    verify(members).update(isNull(), any());
  }
}
