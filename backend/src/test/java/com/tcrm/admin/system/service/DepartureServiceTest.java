package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.api.DepartureHandoverEvent;
import com.tcrm.admin.system.api.DeparturePlanExecutedEvent;
import com.tcrm.admin.system.api.DeparturePlanSubmittedEvent;
import com.tcrm.admin.system.api.ExternalAuditApi;
import com.tcrm.admin.system.mapper.SysUserDeparturePlanMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.DepartureInitiator;
import com.tcrm.admin.system.model.DeparturePlanStatus;
import com.tcrm.admin.system.model.DeparturePlanType;
import com.tcrm.admin.system.model.DepartureResult;
import com.tcrm.admin.system.model.MemberRow;
import com.tcrm.admin.system.model.SysUserDeparturePlanDO;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** Task 41: the rules of a departure plan and the single execution path. */
class DepartureServiceTest {
  private static final long TENANT = 3L;
  private static final long ACTOR = 1L;
  private static final long BD = 7L;
  private static final long HEIR = 8L;
  private static final long PLAN_ID = 11L;
  private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");

  @BeforeAll
  static void tableInfo() {
    var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, SysUserDeparturePlanDO.class);
  }

  private final SysUserDeparturePlanMapper plans = mock(SysUserDeparturePlanMapper.class);
  private final SysUserTenantMapper members = mock(SysUserTenantMapper.class);
  private final BdUserService bdUsers = mock(BdUserService.class);
  private final UserService users = mock(UserService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final AuditSanitizer sanitizer = mock(AuditSanitizer.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final DepartureService service =
      new DepartureService(
          plans,
          members,
          bdUsers,
          users,
          current,
          audit,
          sanitizer,
          events,
          TestTransactions.template(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void signedInSuperAdministrator() {
    when(current.user()).thenReturn(account(ACTOR, Role.SUPER_ADMIN, DataScope.ALL));
  }

  @Test
  void shouldRecordOwnResignationAndAnnounceIt_whenBdSubmitsPlan() {
    when(current.user()).thenReturn(account(BD, Role.STAFF, DataScope.OWN));
    Instant effective = NOW.plusSeconds(3600);

    DepartureService.PlanDTO plan = service.submitOwn(effective, "  family  ");

    SysUserDeparturePlanDO row = inserted();
    assertThat(row.getTenantId()).isEqualTo(TENANT);
    assertThat(row.getUserId()).isEqualTo(BD);
    assertThat(row.getPlanType()).isEqualTo(DeparturePlanType.RESIGN);
    assertThat(row.getEffectiveAt()).isEqualTo(effective);
    assertThat(row.getHeirUserId()).isNull();
    assertThat(row.getInitiator()).isEqualTo(DepartureInitiator.SELF);
    assertThat(row.getReason()).isEqualTo("family");
    assertThat(row.getStatus()).isEqualTo(DeparturePlanStatus.SCHEDULED);
    assertThat(row.getCreateBy()).isEqualTo(BD);
    assertThat(plan.status()).isEqualTo(DeparturePlanStatus.SCHEDULED);
    assertThat(published()).singleElement().isInstanceOf(DeparturePlanSubmittedEvent.class);
  }

  @Test
  void shouldRejectNonBdAccounts_whenReadingOrSubmittingOwnPlan() {
    when(current.user()).thenReturn(account(BD, Role.STAFF, DataScope.ALL));

    error(() -> service.ownPlan(), ErrorCode.DEPARTURE_NOT_BD_ACCOUNT);
    error(() -> service.submitOwn(NOW.plusSeconds(60), null), ErrorCode.DEPARTURE_NOT_BD_ACCOUNT);
    verifyNoInteractions(plans);
  }

  @Test
  void shouldRejectDisabledBd_whenSubmittingOwnPlan() {
    AccountDTO disabled = account(BD, Role.STAFF, DataScope.OWN);
    when(current.user())
        .thenReturn(
            new AccountDTO(
                disabled.id(),
                disabled.username(),
                null,
                disabled.nickname(),
                null,
                Role.STAFF,
                DataScope.OWN,
                0,
                null,
                null,
                0,
                TENANT,
                "Acme",
                true));

    error(() -> service.submitOwn(NOW.plusSeconds(60), null), ErrorCode.AUTH_ACCOUNT_DISABLED);
  }

  @Test
  void shouldRejectPastOrMissingTime_whenSubmittingOwnPlan() {
    when(current.user()).thenReturn(account(BD, Role.STAFF, DataScope.OWN));

    error(() -> service.submitOwn(NOW.minusSeconds(60), null), ErrorCode.DEPARTURE_INVALID_TIME);
    error(() -> service.submitOwn(null, null), ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @Test
  void shouldRejectSecondPlan_whenOneIsAlreadyScheduled() {
    when(current.user()).thenReturn(account(BD, Role.STAFF, DataScope.OWN));
    when(plans.selectOne(any())).thenReturn(plan(BD, null, NOW.plusSeconds(600)));

    error(() -> service.submitOwn(NOW.plusSeconds(3600), null), ErrorCode.DEPARTURE_PLAN_EXISTS);
    verify(plans, never()).insert(any(SysUserDeparturePlanDO.class));
  }

  @Test
  void shouldRejectOwnWithdrawalOfAdministratorPlan_whenCancellingOwnPlan() {
    when(current.user()).thenReturn(account(BD, Role.STAFF, DataScope.OWN));
    SysUserDeparturePlanDO scheduled = plan(BD, null, NOW.plusSeconds(600));
    scheduled.setInitiator(DepartureInitiator.ADMIN);
    when(plans.selectOne(any())).thenReturn(scheduled);

    error(service::cancelOwn, ErrorCode.DEPARTURE_SELF_SUBMITTED_ONLY);
  }

  @Test
  void shouldMarkPlanCancelled_whenBdWithdrawsOwnPlan() {
    when(current.user()).thenReturn(account(BD, Role.STAFF, DataScope.OWN));
    SysUserDeparturePlanDO own = plan(BD, null, NOW.plusSeconds(600));
    own.setInitiator(DepartureInitiator.SELF);
    when(plans.selectOne(any())).thenReturn(own);
    when(plans.update(any(), any())).thenReturn(1);

    service.cancelOwn();

    verify(plans).update(any(), any());
  }

  @Test
  void shouldRejectSelfTarget_whenAdministratorSchedules() {
    error(
        () -> service.create(ACTOR, DeparturePlanType.DISABLE, null, null, null),
        ErrorCode.USER_CANNOT_DISABLE_SELF);
  }

  @Test
  void shouldRejectTargetsThatAreNotBdAccounts_whenAdministratorSchedules() {
    MemberRow allScope = member(BD);
    allScope.setDataScope(DataScope.ALL);
    when(members.getMember(BD, TENANT)).thenReturn(allScope);

    error(
        () -> service.create(BD, DeparturePlanType.DISABLE, null, null, null),
        ErrorCode.DEPARTURE_NOT_BD_ACCOUNT);
  }

  @Test
  void shouldRejectHeirsThatAreUnusableOrBusy_whenAdministratorSchedules() {
    when(members.getMember(BD, TENANT)).thenReturn(member(BD));
    when(bdUsers.getById(HEIR)).thenReturn(new BdUserDTO(HEIR, "heir", "Heir", false));
    error(
        () -> service.create(BD, DeparturePlanType.RESIGN, NOW.plusSeconds(600), HEIR, null),
        ErrorCode.DEPARTURE_INVALID_HEIR);

    when(bdUsers.getById(HEIR)).thenReturn(new BdUserDTO(HEIR, "heir", "Heir", true));
    when(plans.selectOne(any())).thenReturn(plan(HEIR, null, NOW.plusSeconds(600)));
    error(
        () -> service.create(BD, DeparturePlanType.RESIGN, NOW.plusSeconds(600), HEIR, null),
        ErrorCode.DEPARTURE_INVALID_HEIR);

    error(
        () -> service.create(BD, DeparturePlanType.RESIGN, NOW.plusSeconds(600), BD, null),
        ErrorCode.DEPARTURE_INVALID_HEIR);
  }

  @Test
  void shouldRunImmediately_whenAdministratorUpdatesToAPastTime() {
    when(members.getMember(BD, TENANT)).thenReturn(member(BD));
    when(bdUsers.getById(HEIR)).thenReturn(new BdUserDTO(HEIR, "heir", "Heir", true));
    SysUserDeparturePlanDO pending = plan(BD, null, NOW.plusSeconds(3600));
    // 1) validateHeir.scheduledPlan(heir)  2) update.lockScheduled
    // 3) execute.lockScheduledById  4) execute.heirProblem.scheduledPlan(heir)
    when(plans.selectOne(any())).thenReturn(null, pending, pending, null);
    when(plans.update(any(), any())).thenReturn(1);

    DepartureService.PlanDTO result = service.update(BD, NOW.minusSeconds(1), HEIR, "run now");

    assertThat(result.status()).isEqualTo(DeparturePlanStatus.DONE);
    assertThat(result.result()).isEqualTo(DepartureResult.TRANSFERRED);
    verify(users).disableMembership(TENANT, BD, ACTOR);
    assertThat(published()).hasAtLeastOneElementOfType(DepartureHandoverEvent.class);
  }

  @Test
  void shouldHandOverThenDisable_whenDuePlanHasHeir() {
    SysUserDeparturePlanDO due = plan(BD, HEIR, NOW.minusSeconds(60));
    // First read is the locked plan; the heir check then finds no plan of its own.
    when(plans.selectOne(any())).thenReturn(due).thenReturn(null);
    when(members.getMember(BD, TENANT)).thenReturn(member(BD));
    when(bdUsers.getById(HEIR)).thenReturn(new BdUserDTO(HEIR, "heir", "Heir", true));

    DepartureService.PlanDTO executed = service.execute(PLAN_ID);

    assertThat(executed.status()).isEqualTo(DeparturePlanStatus.DONE);
    assertThat(executed.result()).isEqualTo(DepartureResult.TRANSFERRED);
    assertThat(executed.executedAt()).isEqualTo(NOW);
    verify(users).disableMembership(TENANT, BD, ACTOR);
    assertThat(published())
        .hasAtLeastOneElementOfType(DepartureHandoverEvent.class)
        .hasAtLeastOneElementOfType(DeparturePlanExecutedEvent.class);
    verify(audit)
        .recordTenantWriteOperation(
            TENANT,
            ACTOR,
            "User",
            "Execute departure plan",
            "SYSTEM",
            "/admin-api/system/users",
            null);
  }

  @Test
  void shouldLeaveCreatorsUnassigned_whenDuePlanHasNoHeir() {
    when(plans.selectOne(any())).thenReturn(plan(BD, null, NOW.minusSeconds(60)));
    when(members.getMember(BD, TENANT)).thenReturn(member(BD));

    DepartureService.PlanDTO executed = service.execute(PLAN_ID);

    assertThat(executed.result()).isEqualTo(DepartureResult.UNASSIGNED);
    verify(users).disableMembership(TENANT, BD, ACTOR);
    assertThat(published()).noneMatch(event -> event instanceof DepartureHandoverEvent);
  }

  @Test
  void shouldDisableWithoutHandover_whenNamedHeirBecameUnusable() {
    when(plans.selectOne(any())).thenReturn(plan(BD, HEIR, NOW.minusSeconds(60)));
    when(members.getMember(BD, TENANT)).thenReturn(member(BD));
    when(bdUsers.getById(HEIR)).thenReturn(new BdUserDTO(HEIR, "heir", "Heir", false));

    DepartureService.PlanDTO executed = service.execute(PLAN_ID);

    assertThat(executed.result()).isEqualTo(DepartureResult.HEIR_INVALID);
    assertThat(executed.resultMessage()).contains("不可用");
    verify(users).disableMembership(TENANT, BD, ACTOR);
    assertThat(published()).noneMatch(event -> event instanceof DepartureHandoverEvent);
  }

  @Test
  void shouldVoidThePlan_whenTheAccountIsNoLongerABd() {
    SysUserDeparturePlanDO due = plan(BD, HEIR, NOW.minusSeconds(60));
    when(plans.selectOne(any())).thenReturn(due);
    MemberRow promoted = member(BD);
    promoted.setRole(Role.SUPER_ADMIN);
    when(members.getMember(BD, TENANT)).thenReturn(promoted);

    DepartureService.PlanDTO executed = service.execute(PLAN_ID);

    assertThat(executed.status()).isEqualTo(DeparturePlanStatus.CANCELLED);
    assertThat(executed.resultMessage()).contains("已不是商务");
    verifyNoInteractions(users);
    verifyNoInteractions(events);
  }

  @Test
  void shouldDoNothing_whenPlanIsNoLongerScheduled() {
    when(plans.selectOne(any())).thenReturn(null);

    assertThat(service.execute(PLAN_ID)).isNull();
    verifyNoInteractions(users);
  }

  @Test
  void shouldSkipTheDepartingBdAndBusyBds_whenListingHeirs() {
    when(bdUsers.listUsable())
        .thenReturn(
            List.of(
                new BdUserDTO(BD, "bd", "Bea", true),
                new BdUserDTO(HEIR, "heir", "Hana", true),
                new BdUserDTO(9L, "other", "Otto", true)));
    when(plans.selectList(any())).thenReturn(List.of(plan(9L, null, NOW.plusSeconds(600))));

    List<DepartureService.HeirDTO> heirs = service.heirs(BD);

    assertThat(heirs).extracting(DepartureService.HeirDTO::id).containsExactly(HEIR);
    assertThat(heirs.getFirst().nickname()).isEqualTo("Hana");
  }

  @Test
  void shouldUseTheNewestPlanOfEachAccount_whenListingPlans() {
    SysUserDeparturePlanDO newest = plan(BD, null, NOW.plusSeconds(60));
    newest.setStatus(DeparturePlanStatus.DONE);
    SysUserDeparturePlanDO older = plan(BD, null, NOW.plusSeconds(60));
    when(plans.selectList(any())).thenReturn(List.of(newest, older));

    Map<Long, DepartureService.PlanDTO> latest = service.latestPlans(List.of(BD));

    assertThat(latest).hasSize(1);
    assertThat(latest.get(BD).status()).isEqualTo(DeparturePlanStatus.DONE);
  }

  private SysUserDeparturePlanDO inserted() {
    ArgumentCaptor<SysUserDeparturePlanDO> captor =
        ArgumentCaptor.forClass(SysUserDeparturePlanDO.class);
    verify(plans).insert(captor.capture());
    return captor.getValue();
  }

  private List<Object> published() {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(events, atLeastOnce()).publishEvent(captor.capture());
    return captor.getAllValues();
  }

  private static void error(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(expected);
  }

  private static AccountDTO account(long id, Role role, DataScope scope) {
    return new AccountDTO(
        id,
        "user" + id,
        null,
        "User " + id,
        null,
        role,
        scope,
        1,
        null,
        null,
        0,
        TENANT,
        "Acme",
        true);
  }

  private static MemberRow member(long id) {
    MemberRow row = new MemberRow();
    row.setUserId(id);
    row.setTenantId(TENANT);
    row.setUsername("user" + id);
    row.setNickname("User " + id);
    row.setRole(Role.STAFF);
    row.setDataScope(DataScope.OWN);
    row.setStatus(1);
    return row;
  }

  private static SysUserDeparturePlanDO plan(long userId, Long heirUserId, Instant effectiveAt) {
    SysUserDeparturePlanDO row = new SysUserDeparturePlanDO();
    row.setId(PLAN_ID);
    row.setTenantId(TENANT);
    row.setUserId(userId);
    row.setPlanType(DeparturePlanType.RESIGN);
    row.setEffectiveAt(effectiveAt);
    row.setHeirUserId(heirUserId);
    row.setInitiator(DepartureInitiator.ADMIN);
    row.setStatus(DeparturePlanStatus.SCHEDULED);
    row.setCreateBy(ACTOR);
    row.setUpdateBy(ACTOR);
    row.setDeleted(false);
    return row;
  }
}
