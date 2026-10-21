package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TransactionalEvents;
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
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resignation and disable plans of BD accounts (task 41, progress rows 07.01 and 07.05).
 *
 * <p>A plan is the only way a BD leaves on a date it chooses: the account itself may submit a
 * resignation, a super administrator may plan either a resignation or a plain disable, and both
 * carry an optional heir. At the effective time the heir receives every creator of the departing
 * account (same ownership rules as a manual transfer), the account is disabled, and the result is
 * written on the plan and announced to the company's super administrators.
 *
 * <p>Execution is idempotent: the plan row is locked and re-read, the handover only moves creators
 * still owned by the departing account, and everything runs in one transaction, so a retry after a
 * failure neither moves a creator twice nor disables half way.
 */
@Service
public class DepartureService {
  private static final Logger LOG = LoggerFactory.getLogger(DepartureService.class);
  private static final int MAX_REASON = 255;
  private static final int MAX_RESULT_MESSAGE = 255;

  /** One plan as shown to the administrator or to the BD itself. */
  public record PlanDTO(
      Long id,
      Long userId,
      DeparturePlanType type,
      Instant effectiveAt,
      Long heirUserId,
      String heirName,
      DepartureInitiator initiator,
      String reason,
      DeparturePlanStatus status,
      Instant executedAt,
      DepartureResult result,
      String resultMessage) {}

  /** A BD that may inherit another BD's creators. */
  public record HeirDTO(Long id, String username, String nickname) {}

  private final SysUserDeparturePlanMapper plans;
  private final SysUserTenantMapper members;
  private final BdUserService bdUsers;
  private final UserService users;
  private final CurrentUserContext current;
  private final ExternalAuditApi audit;
  private final AuditSanitizer sanitizer;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate tx;
  private final Clock clock;

  @Autowired
  public DepartureService(
      SysUserDeparturePlanMapper plans,
      SysUserTenantMapper members,
      BdUserService bdUsers,
      UserService users,
      CurrentUserContext current,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer,
      ApplicationEventPublisher events,
      TransactionTemplate tx) {
    this(plans, members, bdUsers, users, current, audit, sanitizer, events, tx, Clock.systemUTC());
  }

  DepartureService(
      SysUserDeparturePlanMapper plans,
      SysUserTenantMapper members,
      BdUserService bdUsers,
      UserService users,
      CurrentUserContext current,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer,
      ApplicationEventPublisher events,
      TransactionTemplate tx,
      Clock clock) {
    this.plans = plans;
    this.members = members;
    this.bdUsers = bdUsers;
    this.users = users;
    this.current = current;
    this.audit = audit;
    this.sanitizer = sanitizer;
    this.events = events;
    this.tx = tx;
    this.clock = clock;
  }

  /** The plan the signed-in BD submitted about itself, or null when it has none pending. */
  public PlanDTO ownPlan() {
    AccountDTO user = current.user();
    requireBd(user.role(), user.ownScope(), user.status());
    return toDto(scheduledPlan(user.id()));
  }

  /**
   * A BD resigns, effective at a future time it chooses. Only a BD may resign, nobody may name an
   * heir on its own behalf, and one pending plan per account is enough.
   */
  public PlanDTO submitOwn(Instant plannedLeaveAt, String reason) {
    AccountDTO user = current.user();
    requireBd(user.role(), user.ownScope(), user.status());
    Instant effective = requireTime(plannedLeaveAt);
    String name = displayName(user.nickname(), user.username());
    return tx.execute(
        status -> {
          SysUserDeparturePlanDO plan =
              insert(
                  user.tenantId(),
                  user.id(),
                  DeparturePlanType.RESIGN,
                  effective,
                  null,
                  DepartureInitiator.SELF,
                  reason,
                  user.id());
          TransactionalEvents.publish(
              events, new DeparturePlanSubmittedEvent(user.tenantId(), user.id(), name, effective));
          return toDto(plan);
        });
  }

  /** The BD withdraws the plan it submitted; a plan an administrator created is not its to end. */
  public void cancelOwn() {
    AccountDTO user = current.user();
    tx.executeWithoutResult(
        status -> {
          SysUserDeparturePlanDO plan = scheduledPlan(user.id());
          if (plan == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          if (plan.getInitiator() != DepartureInitiator.SELF) {
            throw new BusinessException(ErrorCode.DEPARTURE_SELF_SUBMITTED_ONLY);
          }
          cancel(plan, user.id());
        });
  }

  /**
   * Schedules a resignation or disable of one BD. A missing or past effective time runs the plan in
   * this request; a future one waits for the minute job.
   */
  public PlanDTO create(
      long userId, DeparturePlanType type, Instant effectiveAt, Long heirUserId, String reason) {
    AccountDTO actor = current.user();
    if (type == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (userId == actor.id()) {
      throw new BusinessException(ErrorCode.USER_CANNOT_DISABLE_SELF);
    }
    long tenantId = actor.tenantId();
    MemberRow target = requireBdMember(tenantId, userId);
    Instant effective = effectiveAt == null ? now() : effectiveAt.truncatedTo(ChronoUnit.MILLIS);
    validateHeir(tenantId, userId, heirUserId);
    String name = displayName(target.getNickname(), target.getUsername());
    PlanDTO created =
        tx.execute(
            status ->
                toDto(
                    insert(
                        tenantId,
                        userId,
                        type,
                        effective,
                        heirUserId,
                        DepartureInitiator.ADMIN,
                        reason,
                        actor.id())));
    return effective.isAfter(now()) ? created : execute(created.id());
  }

  /** Changes the heir, the effective time or the reason of a pending plan. */
  public PlanDTO update(long userId, Instant effectiveAt, Long heirUserId, String reason) {
    long tenantId = current.user().tenantId();
    validateHeir(tenantId, userId, heirUserId);
    PlanDTO updated =
        tx.execute(
            status -> {
              SysUserDeparturePlanDO plan = lockScheduled(tenantId, userId);
              if (plan == null) {
                throw new BusinessException(ErrorCode.DEPARTURE_PLAN_NOT_SCHEDULED);
              }
              Instant effective =
                  effectiveAt == null ? plan.getEffectiveAt() : requireTime(effectiveAt);
              plan.setHeirUserId(heirUserId);
              plan.setEffectiveAt(effective);
              plan.setReason(normalizeReason(reason));
              plans.update(
                  null,
                  new LambdaUpdateWrapper<SysUserDeparturePlanDO>()
                      .eq(SysUserDeparturePlanDO::getId, plan.getId())
                      .eq(SysUserDeparturePlanDO::getStatus, DeparturePlanStatus.SCHEDULED)
                      .set(SysUserDeparturePlanDO::getHeirUserId, heirUserId)
                      .set(SysUserDeparturePlanDO::getEffectiveAt, effective)
                      .set(SysUserDeparturePlanDO::getReason, plan.getReason())
                      .set(SysUserDeparturePlanDO::getUpdateBy, current.user().id())
                      .set(SysUserDeparturePlanDO::getUpdateTime, now()));
              return toDto(plan);
            });
    return updated.effectiveAt().isAfter(now()) ? updated : execute(updated.id());
  }

  /** Withdraws a pending plan; the account keeps working and its creators stay where they are. */
  public void cancel(long userId) {
    tx.executeWithoutResult(
        status -> {
          SysUserDeparturePlanDO plan = lockScheduled(current.user().tenantId(), userId);
          if (plan == null) {
            throw new BusinessException(ErrorCode.DEPARTURE_PLAN_NOT_SCHEDULED);
          }
          cancel(plan, current.user().id());
        });
  }

  /** The pending plan of one account, for the administrator's plan dialog. */
  public PlanDTO plan(long userId) {
    return toDto(scheduledPlan(userId));
  }

  /** BDs of this company that may inherit creators, excluding the departing account itself. */
  public List<HeirDTO> heirs(Long excludeUserId) {
    long tenantId = current.user().tenantId();
    List<BdUserDTO> usable = bdUsers.listUsable();
    Set<Long> busy = scheduledUserIds(tenantId);
    return usable.stream()
        .filter(bd -> !bd.id().equals(excludeUserId))
        .filter(bd -> !busy.contains(bd.id()))
        .map(bd -> new HeirDTO(bd.id(), bd.username(), displayName(bd.nickname(), bd.username())))
        .toList();
  }

  /** Newest plan of each listed account, for the account list column (task 41 §2.2). */
  public Map<Long, PlanDTO> latestPlans(Collection<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Map.of();
    }
    List<SysUserDeparturePlanDO> rows =
        plans.selectList(
            new LambdaQueryWrapper<SysUserDeparturePlanDO>()
                .in(SysUserDeparturePlanDO::getUserId, userIds)
                .orderByDesc(SysUserDeparturePlanDO::getCreateTime, SysUserDeparturePlanDO::getId));
    Map<Long, String> names =
        heirNames(
            rows.stream()
                .map(SysUserDeparturePlanDO::getHeirUserId)
                .filter(Objects::nonNull)
                .toList());
    Map<Long, PlanDTO> latest = new LinkedHashMap<>();
    for (SysUserDeparturePlanDO row : rows) {
      String heir = row.getHeirUserId() == null ? null : names.get(row.getHeirUserId());
      latest.putIfAbsent(row.getUserId(), toDto(row, heir));
    }
    return latest;
  }

  /** At most 200 due plans per company per minute; the job holds the distributed lock. */
  public void executeDueBatch() {
    List<SysUserDeparturePlanDO> due =
        plans.selectList(
            new LambdaQueryWrapper<SysUserDeparturePlanDO>()
                .select(SysUserDeparturePlanDO::getId)
                .eq(SysUserDeparturePlanDO::getStatus, DeparturePlanStatus.SCHEDULED)
                .le(SysUserDeparturePlanDO::getEffectiveAt, now())
                .orderByAsc(SysUserDeparturePlanDO::getEffectiveAt, SysUserDeparturePlanDO::getId)
                .last("LIMIT 200"));
    for (SysUserDeparturePlanDO row : due) {
      try {
        execute(row.getId());
      } catch (RuntimeException e) {
        // The transaction rolled back, so the plan stays SCHEDULED and the next run retries it.
        LOG.error(
            "Departure plan {} failed; retained for retry ({})",
            row.getId(),
            e.getClass().getSimpleName());
      }
    }
  }

  /**
   * Runs one due plan in its own transaction: hand the creators over, disable the account, record
   * the result and announce it. Re-reads the plan under a row lock so two instances cannot both run
   * it.
   */
  public PlanDTO execute(Long id) {
    return tx.execute(
        status -> {
          SysUserDeparturePlanDO plan = lockScheduledById(id);
          if (plan == null) {
            return null;
          }
          if (plan.getEffectiveAt().isAfter(now())) {
            return toDto(plan);
          }
          long tenantId = plan.getTenantId();
          MemberRow member = members.getMember(plan.getUserId(), tenantId);
          if (member == null) {
            plan.setStatus(DeparturePlanStatus.CANCELLED);
            plan.setResultMessage("账号已不存在");
            writeStatus(plan, DeparturePlanStatus.CANCELLED);
            return toDto(plan);
          }
          String name = displayName(member.getNickname(), member.getUsername());
          if (member.getRole() != Role.STAFF || member.getDataScope() != DataScope.OWN) {
            // Promoted after the plan was made (or its scope widened): a plan only ever disables a
            // BD, and disabling a super administrator here would bypass the "last one" guard.
            plan.setStatus(DeparturePlanStatus.CANCELLED);
            plan.setResultMessage("账号已不是商务，计划作废");
            writeStatus(plan, DeparturePlanStatus.CANCELLED);
            return toDto(plan);
          }
          DepartureResult result;
          String message;
          String problem = heirProblem(tenantId, plan.getUserId(), plan.getHeirUserId());
          if (plan.getHeirUserId() != null && problem == null) {
            // Published before the disable: the creator module moves the KOLs before the disable
            // event of the same transaction would unassign them (task 41 §2.1).
            TransactionalEvents.publish(
                events,
                new DepartureHandoverEvent(
                    tenantId,
                    plan.getUserId(),
                    plan.getHeirUserId(),
                    plan.getEffectiveAt(),
                    plan.getReason() == null || plan.getReason().isBlank()
                        ? "离职交接"
                        : plan.getReason(),
                    plan.getCreateBy()));
            result = DepartureResult.TRANSFERRED;
            message = "达人已转交继承商务";
          } else if (plan.getHeirUserId() != null) {
            result = DepartureResult.HEIR_INVALID;
            message = problem;
          } else {
            result = DepartureResult.UNASSIGNED;
            message = "未指定继承商务，达人转为未分配";
          }
          users.disableMembership(tenantId, plan.getUserId(), plan.getCreateBy());
          plan.setStatus(DeparturePlanStatus.DONE);
          plan.setExecutedAt(now());
          plan.setResult(result);
          plan.setResultMessage(truncate(message));
          writeStatus(plan, DeparturePlanStatus.DONE);
          recordExecution(plan);
          TransactionalEvents.publish(
              events,
              new DeparturePlanExecutedEvent(
                  tenantId,
                  plan.getUserId(),
                  name,
                  plan.getPlanType().name(),
                  plan.getEffectiveAt(),
                  heirName(plan.getHeirUserId()),
                  result.name()));
          return toDto(plan);
        });
  }

  private SysUserDeparturePlanDO insert(
      long tenantId,
      long userId,
      DeparturePlanType type,
      Instant effectiveAt,
      Long heirUserId,
      DepartureInitiator initiator,
      String reason,
      Long actorId) {
    if (scheduledPlan(userId) != null) {
      throw new BusinessException(ErrorCode.DEPARTURE_PLAN_EXISTS);
    }
    SysUserDeparturePlanDO plan = new SysUserDeparturePlanDO();
    plan.setTenantId(tenantId);
    plan.setUserId(userId);
    plan.setPlanType(type);
    plan.setEffectiveAt(effectiveAt);
    plan.setHeirUserId(heirUserId);
    plan.setInitiator(initiator);
    plan.setReason(normalizeReason(reason));
    plan.setStatus(DeparturePlanStatus.SCHEDULED);
    plan.setCreateBy(actorId);
    plan.setUpdateBy(actorId);
    plan.setDeleted(false);
    try {
      plans.insert(plan);
    } catch (DuplicateKeyException e) {
      // uk_sys_user_departure_plan_scheduled: another request created the plan first.
      throw new BusinessException(ErrorCode.DEPARTURE_PLAN_EXISTS);
    }
    return plan;
  }

  /** Ends a pending plan; the caller has already locked and checked it. */
  private void cancel(SysUserDeparturePlanDO plan, Long actorId) {
    int changed =
        plans.update(
            null,
            new LambdaUpdateWrapper<SysUserDeparturePlanDO>()
                .eq(SysUserDeparturePlanDO::getId, plan.getId())
                .eq(SysUserDeparturePlanDO::getStatus, DeparturePlanStatus.SCHEDULED)
                .set(SysUserDeparturePlanDO::getStatus, DeparturePlanStatus.CANCELLED)
                .set(SysUserDeparturePlanDO::getUpdateBy, actorId)
                .set(SysUserDeparturePlanDO::getUpdateTime, now()));
    if (changed != 1) {
      throw new BusinessException(ErrorCode.DEPARTURE_PLAN_NOT_SCHEDULED);
    }
    plan.setStatus(DeparturePlanStatus.CANCELLED);
  }

  /** Writes the terminal columns of a plan executed (or abandoned) by the job. */
  private void writeStatus(SysUserDeparturePlanDO plan, DeparturePlanStatus status) {
    plans.update(
        null,
        new LambdaUpdateWrapper<SysUserDeparturePlanDO>()
            .eq(SysUserDeparturePlanDO::getId, plan.getId())
            .eq(SysUserDeparturePlanDO::getStatus, DeparturePlanStatus.SCHEDULED)
            .set(SysUserDeparturePlanDO::getStatus, status)
            .set(SysUserDeparturePlanDO::getExecutedAt, plan.getExecutedAt())
            .set(SysUserDeparturePlanDO::getResult, plan.getResult())
            .set(SysUserDeparturePlanDO::getResultMessage, plan.getResultMessage())
            .set(SysUserDeparturePlanDO::getUpdateBy, plan.getCreateBy())
            .set(SysUserDeparturePlanDO::getUpdateTime, now()));
  }

  /** System execution is audited; the actor is whoever created the plan. */
  private void recordExecution(SysUserDeparturePlanDO plan) {
    long actorId = plan.getCreateBy() == null ? plan.getUserId() : plan.getCreateBy();
    audit.recordTenantWriteOperation(
        plan.getTenantId(),
        actorId,
        "User",
        "Execute departure plan",
        "SYSTEM",
        "/admin-api/system/users",
        sanitizer.sanitize(
            new ExecutionAuditDTO(
                plan.getId(),
                plan.getUserId(),
                plan.getPlanType().name(),
                plan.getEffectiveAt(),
                plan.getResult() == null ? null : plan.getResult().name())));
  }

  /** 422 unless the heir is usable, in the same company, not the departing account, and free. */
  private void validateHeir(long tenantId, long subject, Long heirUserId) {
    String problem = heirProblem(tenantId, subject, heirUserId);
    if (problem != null) {
      throw new BusinessException(ErrorCode.DEPARTURE_INVALID_HEIR);
    }
  }

  /** Why {@code heirUserId} cannot inherit, or null when it can. */
  private String heirProblem(long tenantId, long subject, Long heirUserId) {
    if (heirUserId == null) {
      return null;
    }
    if (heirUserId == subject) {
      return "继承商务不能是本人";
    }
    BdUserDTO heir = bdUsers.getById(heirUserId);
    if (heir == null || !heir.usable()) {
      return "继承商务已不可用：需账号启用、数据范围本人并具备达人编辑权限";
    }
    if (scheduledPlan(heirUserId) != null) {
      return "继承商务自己已有未执行的离职或停用计划";
    }
    return null;
  }

  private MemberRow requireBdMember(long tenantId, long userId) {
    MemberRow member = members.getMember(userId, tenantId);
    if (member == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (member.getRole() != Role.STAFF || member.getDataScope() != DataScope.OWN) {
      throw new BusinessException(ErrorCode.DEPARTURE_NOT_BD_ACCOUNT);
    }
    return member;
  }

  private static void requireBd(Role role, boolean ownScope, Integer status) {
    if (role != Role.STAFF || !ownScope) {
      throw new BusinessException(ErrorCode.DEPARTURE_NOT_BD_ACCOUNT);
    }
    if (!Integer.valueOf(1).equals(status)) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }
  }

  private Instant requireTime(Instant effectiveAt) {
    if (effectiveAt == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    Instant effective = effectiveAt.truncatedTo(ChronoUnit.MILLIS);
    if (effective.isBefore(now())) {
      throw new BusinessException(ErrorCode.DEPARTURE_INVALID_TIME);
    }
    return effective;
  }

  private static String normalizeReason(String reason) {
    if (reason == null || reason.isBlank()) {
      return null;
    }
    String value = reason.strip();
    if (value.length() > MAX_REASON) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return value;
  }

  private static String truncate(String message) {
    if (message == null || message.length() <= MAX_RESULT_MESSAGE) {
      return message;
    }
    return message.substring(0, MAX_RESULT_MESSAGE);
  }

  /** The pending plan of {@code userId} in the current company, or null. */
  private SysUserDeparturePlanDO scheduledPlan(long userId) {
    return plans.selectOne(
        new LambdaQueryWrapper<SysUserDeparturePlanDO>()
            .eq(SysUserDeparturePlanDO::getUserId, userId)
            .eq(SysUserDeparturePlanDO::getStatus, DeparturePlanStatus.SCHEDULED)
            .orderByDesc(SysUserDeparturePlanDO::getId)
            .last("LIMIT 1"));
  }

  private SysUserDeparturePlanDO lockScheduled(long tenantId, long userId) {
    return plans.selectOne(
        new LambdaQueryWrapper<SysUserDeparturePlanDO>()
            .eq(SysUserDeparturePlanDO::getTenantId, tenantId)
            .eq(SysUserDeparturePlanDO::getUserId, userId)
            .eq(SysUserDeparturePlanDO::getStatus, DeparturePlanStatus.SCHEDULED)
            .last("FOR UPDATE"));
  }

  private SysUserDeparturePlanDO lockScheduledById(long id) {
    return plans.selectOne(
        new LambdaQueryWrapper<SysUserDeparturePlanDO>()
            .eq(SysUserDeparturePlanDO::getId, id)
            .eq(SysUserDeparturePlanDO::getStatus, DeparturePlanStatus.SCHEDULED)
            .last("FOR UPDATE"));
  }

  private Set<Long> scheduledUserIds(long tenantId) {
    return plans
        .selectList(
            new LambdaQueryWrapper<SysUserDeparturePlanDO>()
                .select(SysUserDeparturePlanDO::getUserId)
                .eq(SysUserDeparturePlanDO::getTenantId, tenantId)
                .eq(SysUserDeparturePlanDO::getStatus, DeparturePlanStatus.SCHEDULED))
        .stream()
        .map(SysUserDeparturePlanDO::getUserId)
        .collect(Collectors.toCollection(HashSet::new));
  }

  private String heirName(Long heirUserId) {
    if (heirUserId == null) {
      return null;
    }
    return heirNames(List.of(heirUserId)).get(heirUserId);
  }

  private Map<Long, String> heirNames(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return Map.of();
    }
    return bdUsers.listByIds(Set.copyOf(ids)).stream()
        .collect(Collectors.toMap(BdUserDTO::id, bd -> displayName(bd.nickname(), bd.username())));
  }

  private static String displayName(String nickname, String username) {
    return nickname == null || nickname.isBlank() ? username : nickname;
  }

  private PlanDTO toDto(SysUserDeparturePlanDO plan) {
    if (plan == null) {
      return null;
    }
    return toDto(plan, heirName(plan.getHeirUserId()));
  }

  private static PlanDTO toDto(SysUserDeparturePlanDO plan, String heirName) {
    return new PlanDTO(
        plan.getId(),
        plan.getUserId(),
        plan.getPlanType(),
        plan.getEffectiveAt(),
        plan.getHeirUserId(),
        heirName,
        plan.getInitiator(),
        plan.getReason(),
        plan.getStatus(),
        plan.getExecutedAt(),
        plan.getResult(),
        plan.getResultMessage());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private record ExecutionAuditDTO(
      Long planId, Long userId, String planType, Instant effectiveAt, String result) {}
}
