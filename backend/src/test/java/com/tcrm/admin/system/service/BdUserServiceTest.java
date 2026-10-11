package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class BdUserServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDO.class);
  }

  private final SysUserMapper users = mock(SysUserMapper.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final BdUserService service = new BdUserService(users, current);

  private static SysUserDO user(long id, Role role, DataScope scope, int status) {
    var u = new SysUserDO();
    u.setId(id);
    u.setUsername("bd" + id);
    u.setNickname("BD " + id);
    u.setRole(role);
    u.setDataScope(scope);
    u.setStatus(status);
    u.setPasswordHash("test-hash");
    return u;
  }

  private void actAs(Role role, DataScope scope) {
    when(current.user())
        .thenReturn(
            new AccountDTO(
                1L, "me", null, "Me", null, role, scope, 1, null, null, 0, null, null, true));
  }

  @ParameterizedTest
  @CsvSource({
    "OPERATOR, OWN, 1, true",
    "OPERATOR, OWN, 0, false",
    "OPERATOR, ALL, 1, false",
    "SUPER_ADMIN, ALL, 1, false",
    "FINANCE, ALL, 1, false"
  })
  void shouldMarkUsable_onlyForEnabledOperatorWithOwnScope(
      Role role, DataScope scope, int status, boolean usable) {
    when(users.selectOne(any())).thenReturn(user(5L, role, scope, status));
    assertThat(service.getById(5L)).isEqualTo(new BdUserDTO(5L, "bd5", "BD 5", usable));
  }

  @Test
  void shouldSelectOnlyPublicColumns_whenLookingUpById() {
    when(users.selectOne(any())).thenReturn(null);
    assertThat(service.getById(5L)).isNull();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<SysUserDO>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(users).selectOne(query.capture());
    assertThat(query.getValue().getSqlSelect())
        .isEqualTo("id,username,nickname,role,data_scope,status")
        .doesNotContain("password");
  }

  @Test
  void shouldReturnNull_whenIdOrUsernameIsMissing() {
    assertThat(service.getById(null)).isNull();
    assertThat(service.getByUsername(null)).isNull();
    assertThat(service.getByUsername("  ")).isNull();
    assertThat(service.listByIds(List.of())).isEmpty();
    assertThat(service.listByIds(null)).isEmpty();
    verifyNoInteractions(users);
  }

  @Test
  void shouldMatchUsernameCaseInsensitively_whenImportLooksUpBd() {
    when(users.selectOne(any())).thenReturn(user(5L, Role.OPERATOR, DataScope.OWN, 1));
    assertThat(service.getByUsername(" BD5 ").id()).isEqualTo(5L);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<SysUserDO>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(users).selectOne(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("lower(username) = lower(");
  }

  @Test
  void shouldReturnUsableAndUnusableUsers_whenListingByIds() {
    when(users.selectList(any()))
        .thenReturn(
            List.of(
                user(5L, Role.OPERATOR, DataScope.OWN, 1),
                user(6L, Role.OPERATOR, DataScope.OWN, 0)));
    assertThat(service.listByIds(List.of(5L, 6L)))
        .extracting(BdUserDTO::usable)
        .containsExactly(true, false);
  }

  @Test
  void shouldListUsableBds_whenSuperAdminOrOperatorWithAllScopeAsks() {
    when(users.selectList(any())).thenReturn(List.of(user(5L, Role.OPERATOR, DataScope.OWN, 1)));
    actAs(Role.SUPER_ADMIN, DataScope.ALL);
    assertThat(service.listSelectable()).hasSize(1);
    actAs(Role.OPERATOR, DataScope.ALL);
    assertThat(service.listSelectable()).hasSize(1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<SysUserDO>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(users, org.mockito.Mockito.times(2)).selectList(query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains("role =", "data_scope =", "status =", "ORDER BY username ASC,id ASC");
  }

  @ParameterizedTest
  @CsvSource({"OPERATOR, OWN", "FINANCE, ALL"})
  void shouldRejectPicker_whenCallerIsBdOrFinance(Role role, DataScope scope) {
    actAs(role, scope);
    assertThatThrownBy(service::listSelectable)
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
    verifyNoInteractions(users);
  }
}
