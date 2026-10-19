package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.StaffDisabledEvent;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.AccountType;
import com.tcrm.admin.system.model.CompanyCountRow;
import com.tcrm.admin.system.model.MemberRow;
import com.tcrm.admin.system.model.SysUserDO;
import com.tcrm.admin.system.model.SysUserTenantDO;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UserServiceTest {
  private static final long TENANT = 3L;

  @BeforeAll
  static void tableInfo() {
    var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, SysUserDO.class);
    TableInfoHelper.initTableInfo(assistant, SysUserTenantDO.class);
  }

  private final SysUserMapper users = mock(SysUserMapper.class);
  private final SysUserTenantMapper members = mock(SysUserTenantMapper.class);
  private final BCryptPasswordEncoder passwords = mock(BCryptPasswordEncoder.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final UserPermissionService permissions = mock(UserPermissionService.class);
  private final PostTemplateService postTemplates = mock(PostTemplateService.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final UserService service =
      new UserService(users, members, passwords, current, permissions, postTemplates, events);

  @BeforeEach
  void loggedInSuperAdministrator() {
    org.mockito.Mockito.when(postTemplates.getDefault(any(), anyLong()))
        .thenAnswer(
            invocation -> {
              PostTemplate template = invocation.getArgument(0);
              return new PostTemplateService.PostTemplateView(
                  template, template.dataScope(), template.permissionCodes(), true);
            });
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
                TENANT,
                "Acme",
                true));
    when(members.lockMember(7L, TENANT)).thenReturn(70L);
  }

  private static MemberRow member() {
    var m = new MemberRow();
    m.setUserId(7L);
    m.setTenantId(TENANT);
    m.setUsername("operator");
    m.setNickname("Operator");
    m.setRole(Role.STAFF);
    m.setDataScope(DataScope.OWN);
    m.setPostTemplate(PostTemplate.BD);
    m.setStatus(1);
    m.setAccountStatus(1);
    return m;
  }

  private void companies(long userId, int count) {
    var row = new CompanyCountRow();
    row.setUserId(userId);
    row.setCompanyCount(count);
    when(members.countCompanies(any())).thenReturn(List.of(row));
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
    verify(members, never()).insert(any(SysUserTenantDO.class));
  }

  @Test
  void shouldRejectExistingUsername_evenWhenTheAccountBelongsToAnotherCompany() {
    // Only a platform administrator adds an existing account to a company (ADR-022).
    when(users.countByUsername("operator")).thenReturn(1L);
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
                        null,
                        null)))
        .hasMessage(ErrorCode.USER_USERNAME_EXISTS.message());
    verify(users, never()).insert(any(SysUserDO.class));
    verify(members, never()).insert(any(SysUserTenantDO.class));
  }

  @Test
  void shouldHashPasswordAndCreateMembership_whenCreatingOperator() {
    when(passwords.encode("test-input")).thenReturn("test-hash");
    doAnswer(
            i -> {
              SysUserDO u = i.getArgument(0);
              u.setId(7L);
              return 1;
            })
        .when(users)
        .insert(any(SysUserDO.class));
    when(members.getMember(7L, TENANT)).thenReturn(member());
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
    assertThat(result.companyCount()).isEqualTo(1);
    var saved = ArgumentCaptor.forClass(SysUserDO.class);
    verify(users).insert(saved.capture());
    assertThat(saved.getValue().getPasswordHash()).isEqualTo("test-hash");
    assertThat(saved.getValue().getRole()).isEqualTo(AccountType.MEMBER);
    assertThat(saved.getValue().getLastTenantId()).isEqualTo(TENANT);
    assertThat(saved.getValue().getDeleted()).isFalse();
    var membership = ArgumentCaptor.forClass(SysUserTenantDO.class);
    verify(members).insert(membership.capture());
    assertThat(membership.getValue().getTenantId()).isEqualTo(TENANT);
    assertThat(membership.getValue().getUserId()).isEqualTo(7L);
    assertThat(membership.getValue().getRole()).isEqualTo(Role.STAFF);
    assertThat(membership.getValue().getPostTemplate()).isEqualTo(PostTemplate.BD);
    assertThat(membership.getValue().getDataScope()).isEqualTo(DataScope.OWN);
    assertThat(membership.getValue().getStatus()).isEqualTo(1);
    assertThat(membership.getValue().getDeleted()).isFalse();
    verify(permissions).grant(7L, TENANT, PostTemplate.BD.permissionCodes());
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
    verifyNoInteractions(users, members, passwords);
  }

  @Test
  void shouldPreventSelfRoleRemoval_whenEditingCurrentSuperAdmin() {
    when(members.getMember(7L, TENANT)).thenReturn(member());
    when(current.id()).thenReturn(7L);
    assertThatThrownBy(
            () ->
                service.update(
                    7L,
                    new EditUserCommand(
                        "Operator", null, Role.STAFF, PostTemplate.BD, DataScope.OWN)))
        .hasMessage(ErrorCode.USER_CANNOT_REMOVE_SELF_ROLE.message());
    verify(users, never()).update(any(), any());
    verify(members, never()).update(any(), any());
  }

  @Test
  void shouldUpdateProfileAndMembership_whenPersonBelongsToThisCompanyOnly() {
    when(members.getMember(7L, TENANT)).thenReturn(member());
    when(current.id()).thenReturn(8L);
    companies(7L, 1);
    service.update(
        7L,
        new EditUserCommand(
            "Finance", "test@example.invalid", Role.STAFF, PostTemplate.OPERATOR, DataScope.ALL));
    assertThat(accountColumns())
        .contains("nickname=", "email=", "update_by=", "update_time=")
        .doesNotContain("status", "password_hash", "username", "role");
    String membership = memberColumns();
    assertThat(membership)
        .contains("role=", "data_scope=", "post_template=", "update_by=", "update_time=")
        .doesNotContain("status", "nickname");
    assertThat(memberWhere()).contains("user_id", "tenant_id");
    verify(permissions, never()).grant(any(), anyLong(), any());
    verify(permissions, never()).clear(any(), anyLong());
  }

  @Test
  void shouldRejectNameChange_whenPersonBelongsToSeveralCompanies() {
    when(members.getMember(7L, TENANT)).thenReturn(member());
    when(current.id()).thenReturn(8L);
    companies(7L, 2);
    assertThatThrownBy(
            () ->
                service.update(
                    7L,
                    new EditUserCommand(
                        "Renamed", null, Role.STAFF, PostTemplate.BD, DataScope.OWN)))
        .hasMessage(ErrorCode.USER_MULTI_COMPANY_LOCKED.message());
    verify(users, never()).update(any(), any());
    verify(members, never()).update(any(), any());
  }

  @Test
  void shouldChangeRoleOnly_whenMultiCompanyPersonKeepsNameAndEmail() {
    when(members.getMember(7L, TENANT)).thenReturn(member());
    when(current.id()).thenReturn(8L);
    companies(7L, 2);
    service.update(
        7L, new EditUserCommand("Operator", null, Role.STAFF, PostTemplate.BD, DataScope.ALL));
    assertThat(memberColumns()).contains("data_scope=");
    verify(users, never()).update(any(), any());
    verify(members, never()).countCompanies(any());
  }

  @Test
  void shouldKeepSelfSuperRole_whenProfileChanges() {
    when(members.getMember(7L, TENANT)).thenReturn(member());
    when(current.id()).thenReturn(7L);
    companies(7L, 1);
    when(members.lockEnabledSuperAdmins(TENANT)).thenReturn(List.of(1L, 7L));
    service.update(
        7L, new EditUserCommand("Admin", null, Role.SUPER_ADMIN, PostTemplate.BD, DataScope.ALL));
    assertThat(memberColumns()).contains("role=", "post_template=");
    verify(permissions).clear(7L, TENANT);
  }

  @Test
  void shouldCountSuperAdminsOfThisCompanyOnly_whenDemotingOrDisabling() {
    var admin = member();
    admin.setRole(Role.SUPER_ADMIN);
    admin.setDataScope(DataScope.ALL);
    when(members.getMember(7L, TENANT)).thenReturn(admin);
    when(current.id()).thenReturn(8L);
    when(members.lockEnabledSuperAdmins(TENANT)).thenReturn(List.of(7L));
    assertThatThrownBy(() -> service.updateStatus(7L, 0))
        .hasMessage(ErrorCode.USER_LAST_SUPER_ADMIN.message());
    verify(members).lockEnabledSuperAdmins(TENANT);
    verify(members, never()).update(any(), any());
  }

  @Test
  void shouldRejectSelfDisable_whenCurrentUserIsTarget() {
    when(current.id()).thenReturn(7L);
    assertThatThrownBy(() -> service.updateStatus(7L, 0))
        .hasMessage(ErrorCode.USER_CANNOT_DISABLE_SELF.message());
    verifyNoInteractions(users, members);
  }

  @Test
  void shouldEndOnlyThisCompanysTokens_whenDisablingAnotherUser() {
    when(current.id()).thenReturn(8L);
    when(members.getMember(7L, TENANT)).thenReturn(member());
    try (var stp = mockStatic(StpUtil.class)) {
      TestTransactions.template().executeWithoutResult(tx -> service.updateStatus(7L, 0));
      assertThat(memberColumns())
          .contains("status=")
          .doesNotContain("password_hash", "role", "nickname");
      assertThat(memberWhere()).contains("user_id", "tenant_id");
      verify(users, never()).update(any(), any());
      stp.verify(() -> StpUtil.getTokenValueListByLoginId(7L));
      stp.verify(() -> StpUtil.kickout(any()), never());
      stp.verify(() -> StpUtil.logout(any()), never());
      verify(events).publishEvent(new StaffDisabledEvent(TENANT, 7L));
    }
  }

  @Test
  void shouldKeepSessions_whenEnablingUser() {
    when(current.id()).thenReturn(8L);
    when(members.getMember(7L, TENANT)).thenReturn(member());
    try (var stp = mockStatic(StpUtil.class)) {
      TestTransactions.template().executeWithoutResult(tx -> service.updateStatus(7L, 1));
      stp.verifyNoInteractions();
      verifyNoInteractions(events);
    }
  }

  @Test
  void shouldInvalidateAllTokens_whenResettingPassword() {
    when(members.getMember(7L, TENANT)).thenReturn(member());
    companies(7L, 1);
    when(passwords.encode("new-test-input")).thenReturn("new-test-hash");
    try (var stp = mockStatic(StpUtil.class)) {
      service.updatePassword(7L, "new-test-input");
      assertThat(accountColumns())
          .contains("password_hash=")
          .doesNotContain("status", "role", "nickname");
      stp.verify(() -> StpUtil.logout(7L));
    }
  }

  @Test
  void shouldRejectPasswordReset_whenPersonBelongsToSeveralCompanies() {
    when(members.getMember(7L, TENANT)).thenReturn(member());
    companies(7L, 2);
    try (var stp = mockStatic(StpUtil.class)) {
      assertThatThrownBy(() -> service.updatePassword(7L, "new-test-input"))
          .hasMessage(ErrorCode.USER_MULTI_COMPANY_LOCKED.message());
      verify(users, never()).update(any(), any());
      stp.verifyNoInteractions();
    }
  }

  @Test
  void shouldReturnNotFound_whenTargetIsNotAMemberOfThisCompany() {
    assertThatThrownBy(() -> service.updatePassword(999L, "test-input"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
    verify(members).getMember(999L, TENANT);
  }

  @Test
  void shouldKeepPagingMetadataAndCountCompanies_whenListingUsers() {
    Page<MemberRow> page = new Page<>(2, 20, 21);
    page.setRecords(List.of(member()));
    when(members.pageMembers(any(), eq(TENANT), eq("%OP%"), eq(Role.STAFF), eq(1)))
        .thenReturn(page);
    when(permissions.storedCodes(List.of(7L), TENANT))
        .thenReturn(Map.of(7L, List.of(Permission.KOL_VIEW.code())));
    companies(7L, 2);
    var result = service.page(2, 20, "OP", Role.STAFF, 1);
    assertThat(result.total()).isEqualTo(21);
    assertThat(result.page()).isEqualTo(2);
    assertThat(result.size()).isEqualTo(20);
    assertThat(result.items()).extracting(UserDTO::username).containsExactly("operator");
    assertThat(result.items().get(0).permissions()).containsExactly(Permission.KOL_VIEW.code());
    assertThat(result.items().get(0).companyCount()).isEqualTo(2);
  }

  @Test
  void shouldListEveryMemberOfTheCompany_whenUsernameIsOmitted() {
    Page<MemberRow> page = new Page<>(1, 20, 1);
    page.setRecords(List.of(member()));
    when(members.pageMembers(any(), eq(TENANT), isNull(), isNull(), isNull())).thenReturn(page);
    var result = service.page(1, 20, null, null, null);
    assertThat(result.total()).isEqualTo(1);
    assertThat(result.items()).extracting(UserDTO::username).containsExactly("operator");
    assertThat(result.items().get(0).companyCount()).isEqualTo(1);
  }

  @Test
  void shouldEscapeWildcards_whenFilteringByUsername() {
    Page<MemberRow> page = new Page<>(1, 20, 0);
    when(members.pageMembers(any(), anyLong(), any(), any(), any())).thenReturn(page);
    service.page(1, 20, " a_b% ", null, null);
    verify(members).pageMembers(any(), eq(TENANT), eq("%a\\_b\\%%"), isNull(), isNull());
    verify(members, never()).countCompanies(any());
  }

  /** SET clause of the single account update sent to the mapper. */
  @SuppressWarnings("unchecked")
  private String accountColumns() {
    ArgumentCaptor<Wrapper<SysUserDO>> update = ArgumentCaptor.forClass(Wrapper.class);
    verify(users).update(isNull(), update.capture());
    return ((LambdaUpdateWrapper<SysUserDO>) update.getValue()).getSqlSet();
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<SysUserTenantDO> memberUpdate() {
    ArgumentCaptor<Wrapper<SysUserTenantDO>> update = ArgumentCaptor.forClass(Wrapper.class);
    verify(members).update(isNull(), update.capture());
    return (LambdaUpdateWrapper<SysUserTenantDO>) update.getValue();
  }

  private String memberColumns() {
    return memberUpdate().getSqlSet();
  }

  private String memberWhere() {
    return memberUpdate().getSqlSegment();
  }
}
