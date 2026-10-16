package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserPermissionMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.MemberRow;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserPermissionDO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class BdUserServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDO.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserPermissionDO.class);
  }

  private final SysUserMapper users = mock(SysUserMapper.class);
  private final SysUserTenantMapper members = mock(SysUserTenantMapper.class);
  private final SysUserPermissionMapper permissions = mock(SysUserPermissionMapper.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final BdUserService service = new BdUserService(users, members, permissions, current);

  @BeforeEach
  void loggedInCompany() {
    when(current.user())
        .thenReturn(
            new AccountDTO(
                1L,
                "admin",
                "test-hash",
                "Admin",
                null,
                Role.SUPER_ADMIN,
                DataScope.ALL,
                1,
                null,
                null,
                0,
                3L,
                "Company 3",
                true));
  }

  private static MemberRow member(long id, Role role, DataScope scope, int status, int account) {
    var m = new MemberRow();
    m.setUserId(id);
    m.setTenantId(3L);
    m.setUsername("bd" + id);
    m.setNickname("BD " + id);
    m.setRole(role);
    m.setDataScope(scope);
    m.setStatus(status);
    m.setAccountStatus(account);
    return m;
  }

  private static SysUserPermissionDO grant(long userId) {
    var row = new SysUserPermissionDO();
    row.setUserId(userId);
    row.setPermissionCode("kol:edit");
    return row;
  }

  @ParameterizedTest
  @CsvSource({
    "STAFF, OWN, 1, 1, true",
    "STAFF, OWN, 0, 1, false",
    "STAFF, OWN, 1, 0, false",
    "STAFF, ALL, 1, 1, false",
    "SUPER_ADMIN, ALL, 1, 1, false"
  })
  void shouldMarkUsable_onlyForEnabledStaffWithOwnScopeAndKolEditInThisCompany(
      Role role, DataScope scope, int status, int accountStatus, boolean usable) {
    when(members.getMember(5L, 3L)).thenReturn(member(5L, role, scope, status, accountStatus));
    when(permissions.selectList(any())).thenReturn(List.of(grant(5L)));
    assertThat(service.getById(5L)).isEqualTo(new BdUserDTO(5L, "bd5", "BD 5", usable));
  }

  @Test
  void shouldMarkUnusable_whenStaffOwnLacksKolEditInThisCompany() {
    when(members.getMember(5L, 3L)).thenReturn(member(5L, Role.STAFF, DataScope.OWN, 1, 1));
    when(permissions.selectList(any())).thenReturn(List.of());
    assertThat(service.getById(5L).usable()).isFalse();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<SysUserPermissionDO>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(permissions).selectList(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("tenant_id", "permission_code");
  }

  @Test
  void shouldBeUnknown_whenAccountIsNotAMemberOfThisCompany() {
    assertThat(service.getById(5L)).isNull();
    verify(members).getMember(5L, 3L);
    verifyNoInteractions(permissions);
  }

  @Test
  void shouldUseExplicitCompanyScope_whenNobodyIsLoggedIn() {
    // Background jobs (scheduled transfers, imports, self-registration) open a company scope.
    when(members.getMember(5L, 8L)).thenReturn(member(5L, Role.STAFF, DataScope.OWN, 1, 1));
    when(permissions.selectList(any())).thenReturn(List.of(grant(5L)));
    assertThat(TenantScope.callCompany(8L, () -> service.getById(5L)).usable()).isTrue();
    verify(current, never()).user();
  }

  @Test
  void shouldNeverSelectCredentials_whenReadingMembers() {
    assertThat(SysUserTenantMapper.MEMBER_COLUMNS).doesNotContain("password");
  }

  @Test
  void shouldLockMembershipOfThisCompany_whenLockingABd() {
    when(members.lockMember(5L, 3L)).thenReturn(11L);
    when(members.getMember(5L, 3L)).thenReturn(member(5L, Role.STAFF, DataScope.OWN, 1, 1));
    assertThat(service.lockById(5L).id()).isEqualTo(5L);
    when(members.lockMember(6L, 3L)).thenReturn(null);
    assertThat(service.lockById(6L)).isNull();
  }

  @Test
  void shouldReturnNull_whenIdOrUsernameIsMissing() {
    assertThat(service.getById(null)).isNull();
    assertThat(service.getByUsername(null)).isNull();
    assertThat(service.getByUsername("  ")).isNull();
    assertThat(service.listByIds(List.of())).isEmpty();
    assertThat(service.listByIds(null)).isEmpty();
    verifyNoInteractions(users, members);
  }

  @Test
  void shouldMatchUsernameCaseInsensitively_whenImportLooksUpBd() {
    var account = new SysUserDO();
    account.setId(5L);
    when(users.selectOne(any())).thenReturn(account);
    when(members.getMember(5L, 3L)).thenReturn(member(5L, Role.STAFF, DataScope.OWN, 1, 1));
    assertThat(service.getByUsername(" BD5 ").id()).isEqualTo(5L);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<SysUserDO>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(users).selectOne(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("lower(username) = lower(");
    assertThat(query.getValue().getSqlSelect()).isEqualTo("id");
  }

  @Test
  void shouldReturnUsableAndUnusableUsers_whenListingByIds() {
    when(permissions.selectList(any())).thenReturn(List.of(grant(5L)));
    when(members.listMembers(eq(3L), any()))
        .thenReturn(
            List.of(
                member(5L, Role.STAFF, DataScope.OWN, 1, 1),
                member(6L, Role.STAFF, DataScope.OWN, 0, 1)));
    assertThat(service.listByIds(List.of(5L, 6L)))
        .extracting(BdUserDTO::usable)
        .containsExactly(true, false);
  }

  @Test
  void shouldListUsableBdsOfThisCompany_withoutCheckingTheCallersRole() {
    when(members.listUsableBds(3L, "kol:edit"))
        .thenReturn(List.of(member(5L, Role.STAFF, DataScope.OWN, 1, 1)));
    assertThat(service.listSelectable()).hasSize(1);
    assertThat(service.listSelectable()).extracting(BdUserDTO::usable).containsExactly(true);
    verify(members, never()).listUsableBds(anyLong(), eq("kol:view"));
  }
}
