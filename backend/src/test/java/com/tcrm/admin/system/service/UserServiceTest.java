package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UserServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDO.class);
  }

  private final SysUserMapper users = mock(SysUserMapper.class);
  private final BCryptPasswordEncoder passwords = mock(BCryptPasswordEncoder.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final UserPermissionService permissions = mock(UserPermissionService.class);
  private final UserService service = new UserService(users, passwords, current, permissions);

  private SysUserDO user() {
    var u = new SysUserDO();
    u.setId(7L);
    u.setUsername("operator");
    u.setNickname("Operator");
    u.setRole(Role.STAFF);
    u.setDataScope(DataScope.OWN);
    u.setStatus(1);
    return u;
  }

  @Test
  void shouldReportUsernameExists_whenInsertViolatesUniqueKey() {
    when(passwords.encode("test-input")).thenReturn("test-hash");
    when(users.insert(any(SysUserDO.class))).thenThrow(new DuplicateKeyException("unit-duplicate"));
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateUserCommand(
                        "operator",
                        "test-input",
                        "Operator",
                        null,
                        Role.STAFF,
                        PostTemplate.BD,
                        DataScope.OWN,
                        null)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.USER_USERNAME_EXISTS.message());
  }

  @Test
  void shouldHashPasswordAndReturnPublicFields_whenCreatingOperator() {
    when(current.user())
        .thenReturn(
            new AccountDTO(
                1L,
                "admin",
                null,
                "Admin",
                null,
                Role.SUPER_ADMIN,
                DataScope.ALL,
                1,
                null,
                null,
                0,
                3L,
                "Acme",
                true));
    when(passwords.encode("test-input")).thenReturn("test-hash");
    doAnswer(
            i -> {
              SysUserDO u = i.getArgument(0);
              u.setId(7L);
              return 1;
            })
        .when(users)
        .insert(any(SysUserDO.class));
    var result =
        service.create(
            new CreateUserCommand(
                "operator",
                "test-input",
                "Operator",
                null,
                Role.STAFF,
                PostTemplate.BD,
                null,
                null));
    assertThat(result.id()).isEqualTo(7L);
    assertThat(result.role()).isEqualTo(Role.STAFF);
    assertThat(result.dataScope()).isEqualTo(DataScope.OWN);
    assertThat(result.postTemplate()).isEqualTo(PostTemplate.BD);
    assertThat(result.permissions()).isEqualTo(PostTemplate.BD.permissionCodes());
    var saved = org.mockito.ArgumentCaptor.forClass(SysUserDO.class);
    verify(users).insert(saved.capture());
    assertThat(saved.getValue().getPasswordHash()).isEqualTo("test-hash");
    assertThat(saved.getValue().getPostTemplate()).isEqualTo(PostTemplate.BD);
    assertThat(saved.getValue().getDataScope()).isEqualTo(DataScope.OWN);
    assertThat(saved.getValue().getDeleted()).isFalse();
    verify(permissions).grant(7L, 3L, PostTemplate.BD.permissionCodes());
  }

  @Test
  void shouldRejectOwnScope_whenRoleIsNotStaff() {
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateUserCommand(
                        "test-user",
                        "test-input",
                        "Test",
                        null,
                        Role.SUPER_ADMIN,
                        PostTemplate.OPERATOR,
                        DataScope.OWN,
                        List.of("kol:view"))))
        .hasMessage(ErrorCode.USER_INVALID_DATA_SCOPE.message());
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateUserCommand(
                        "test-user",
                        "test-input",
                        "Test",
                        null,
                        Role.STAFF,
                        null,
                        DataScope.ALL,
                        null)))
        .hasMessage(ErrorCode.COMMON_VALIDATION_FAILED.message());
    verifyNoInteractions(users, passwords);
  }

  @Test
  void shouldPreventSelfRoleRemoval_whenEditingCurrentSuperAdmin() {
    when(users.selectById(7L)).thenReturn(user());
    when(current.id()).thenReturn(7L);
    assertThatThrownBy(
            () ->
                service.update(
                    7L,
                    new EditUserCommand(
                        "Operator", null, Role.STAFF, PostTemplate.BD, DataScope.OWN)))
        .hasMessage(ErrorCode.USER_CANNOT_REMOVE_SELF_ROLE.message());
    verify(users, never()).updateById(any(SysUserDO.class));
  }

  @Test
  void shouldUpdateProfile_whenScopeAndRoleAreAllowed() {
    when(users.selectById(7L)).thenReturn(user());
    when(current.id()).thenReturn(8L);
    service.update(
        7L,
        new EditUserCommand(
            "Finance", "test@example.invalid", Role.STAFF, PostTemplate.OPERATOR, DataScope.ALL));
    assertThat(updatedColumns())
        .contains(
            "nickname=",
            "email=",
            "role=",
            "data_scope=",
            "post_template=",
            "update_by=",
            "update_time=")
        .doesNotContain("status", "password_hash", "username");
    verify(users, never()).updateById(any(SysUserDO.class));
    verify(permissions, never()).grant(any(), any(), any());
    verify(permissions, never()).clear(any());
  }

  @Test
  void shouldKeepSelfSuperRole_whenProfileChanges() {
    when(users.selectById(7L)).thenReturn(user());
    when(current.id()).thenReturn(7L);
    service.update(
        7L, new EditUserCommand("Admin", null, Role.SUPER_ADMIN, PostTemplate.BD, DataScope.ALL));
    assertThat(updatedColumns()).contains("role=", "post_template=");
    verify(permissions).clear(7L);
  }

  @Test
  void shouldRejectSelfDisable_whenCurrentUserIsTarget() {
    when(current.id()).thenReturn(7L);
    assertThatThrownBy(() -> service.updateStatus(7L, 0))
        .hasMessage(ErrorCode.USER_CANNOT_DISABLE_SELF.message());
    verifyNoInteractions(users);
  }

  @Test
  void shouldKickAllTokens_whenDisablingAnotherUser() {
    when(current.id()).thenReturn(8L);
    when(users.selectById(7L)).thenReturn(user());
    try (var stp = mockStatic(StpUtil.class)) {
      service.updateStatus(7L, 0);
      assertThat(updatedColumns())
          .contains("status=")
          .doesNotContain("password_hash", "role", "nickname");
      stp.verify(() -> StpUtil.kickout(7L));
    }
  }

  @Test
  void shouldKeepSessions_whenEnablingUser() {
    when(current.id()).thenReturn(8L);
    when(users.selectById(7L)).thenReturn(user());
    try (var stp = mockStatic(StpUtil.class)) {
      service.updateStatus(7L, 1);
      stp.verifyNoInteractions();
    }
  }

  @Test
  void shouldInvalidateAllTokens_whenResettingPassword() {
    when(users.selectById(7L)).thenReturn(user());
    when(passwords.encode("new-test-input")).thenReturn("new-test-hash");
    try (var stp = mockStatic(StpUtil.class)) {
      service.updatePassword(7L, "new-test-input");
      assertThat(updatedColumns())
          .contains("password_hash=")
          .doesNotContain("status", "role", "nickname");
      stp.verify(() -> StpUtil.logout(7L));
    }
  }

  @Test
  void shouldReturnNotFound_whenTargetDoesNotExist() {
    assertThatThrownBy(() -> service.updatePassword(999L, "test-input"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void shouldKeepPagingMetadata_whenListingUsers() {
    Page<SysUserDO> page = new Page<>(2, 20, 21);
    page.setRecords(List.of(user()));
    when(users.selectPage(any(Page.class), any())).thenReturn(page);
    when(permissions.storedCodes(org.mockito.ArgumentMatchers.<java.util.Collection<Long>>any()))
        .thenReturn(Map.of(7L, List.of(Permission.KOL_VIEW.code())));
    var result = service.page(2, 20, "OP", Role.STAFF, 1);
    assertThat(result.total()).isEqualTo(21);
    assertThat(result.page()).isEqualTo(2);
    assertThat(result.size()).isEqualTo(20);
    assertThat(result.items()).extracting(UserDTO::username).containsExactly("operator");
    assertThat(result.items().get(0).permissions()).containsExactly(Permission.KOL_VIEW.code());
  }

  @Test
  void shouldListEveryUserInTheCompany_whenUsernameIsOmitted() {
    Page<SysUserDO> page = new Page<>(1, 20, 1);
    page.setRecords(List.of(user()));
    when(users.selectPage(any(Page.class), any())).thenReturn(page);
    when(permissions.storedCodes(org.mockito.ArgumentMatchers.<java.util.Collection<Long>>any()))
        .thenReturn(Map.of());
    var result = service.page(1, 20, null, null, null);
    assertThat(result.total()).isEqualTo(1);
    assertThat(result.items()).extracting(UserDTO::username).containsExactly("operator");
  }

  /** SET clause of the single column-whitelist update sent to the mapper. */
  @SuppressWarnings("unchecked")
  private String updatedColumns() {
    ArgumentCaptor<Wrapper<SysUserDO>> update = ArgumentCaptor.forClass(Wrapper.class);
    verify(users).update(isNull(), update.capture());
    return ((LambdaUpdateWrapper<SysUserDO>) update.getValue()).getSqlSet();
  }
}
