package com.tcrm.admin.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.commission.api.PayoutAccountSubmittedEvent;
import com.tcrm.admin.kol.api.BdTransferCancelledEvent;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.kol.api.KolsUnassignedEvent;
import com.tcrm.admin.notification.api.PrepayNotification;
import com.tcrm.admin.notification.api.PrepayNotificationApi;
import com.tcrm.admin.notification.mapper.NotificationMapper;
import com.tcrm.admin.notification.model.NotificationDO;
import com.tcrm.admin.notification.model.NotificationType;
import com.tcrm.admin.order.api.OrderSyncFailedEvent;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.DeparturePlanExecutedEvent;
import com.tcrm.admin.system.api.DeparturePlanSubmittedEvent;
import com.tcrm.admin.system.api.EnabledPrepayReviewerApi;
import com.tcrm.admin.system.api.EnabledSuperAdminApi;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes in-app messages after the business transaction commits, and reads only the signed-in
 * user's own messages. Recipients are resolved here, not by the publisher.
 */
@Service
public class NotificationService implements PrepayNotificationApi {
  static final String BIZ_PAYOUT_ACCOUNT = "PAYOUT_ACCOUNT";
  static final String BIZ_SHOP = "SHOP";
  static final String BIZ_BD_TRANSFER = "BD_TRANSFER";
  static final String BIZ_STAFF = "STAFF";
  static final String BIZ_DEPARTURE = "DEPARTURE_PLAN";

  private final NotificationMapper notifications;
  private final KolApi kols;
  private final EnabledSuperAdminApi superAdmins;
  private final CurrentUserContext current;
  private final EnabledPrepayReviewerApi prepayReviewers;
  private final TransactionTemplate tx;
  private final Clock clock;

  @Autowired
  public NotificationService(
      NotificationMapper notifications,
      KolApi kols,
      EnabledSuperAdminApi superAdmins,
      CurrentUserContext current,
      TransactionTemplate tx,
      EnabledPrepayReviewerApi prepayReviewers) {
    this(
        notifications,
        kols,
        superAdmins,
        current,
        requiresNew(tx),
        prepayReviewers,
        Clock.systemUTC());
  }

  public NotificationService(
      NotificationMapper notifications,
      KolApi kols,
      EnabledSuperAdminApi superAdmins,
      CurrentUserContext current,
      TransactionTemplate tx) {
    this(notifications, kols, superAdmins, current, requiresNew(tx), null, Clock.systemUTC());
  }

  public NotificationService(
      NotificationMapper notifications,
      KolApi kols,
      EnabledSuperAdminApi superAdmins,
      CurrentUserContext current,
      TransactionTemplate tx,
      Clock clock) {
    this(notifications, kols, superAdmins, current, tx, null, clock);
  }

  private NotificationService(
      NotificationMapper notifications,
      KolApi kols,
      EnabledSuperAdminApi superAdmins,
      CurrentUserContext current,
      TransactionTemplate tx,
      EnabledPrepayReviewerApi prepayReviewers,
      Clock clock) {
    this.notifications = notifications;
    this.kols = kols;
    this.superAdmins = superAdmins;
    this.current = current;
    this.prepayReviewers = prepayReviewers;
    this.tx = tx;
    this.clock = clock;
  }

  @Override
  public void deliver(PrepayNotification event) {
    if (prepayReviewers == null) {
      throw new IllegalStateException("prepay notification recipient service is required");
    }
    tx.executeWithoutResult(
        status ->
            TenantScope.runCompany(
                event.tenantId(),
                () -> {
                  NotificationType type = NotificationType.valueOf("PREPAY_" + event.eventType());
                  LinkedHashSet<Long> recipients = new LinkedHashSet<>();
                  if ("SUBMITTED".equals(event.eventType())) {
                    recipients.addAll(prepayReviewers.listEnabledIds());
                    recipients.addAll(superAdmins.listEnabledIds());
                  } else {
                    Long currentBd = currentBd(event.kolId());
                    if (currentBd != null) {
                      recipients.add(currentBd);
                    }
                  }
                  String params =
                      NotificationParamsCodec.write(
                          type,
                          new NotificationParamsDTO(
                              null, null, null, null, null, null, event.prepayNo()));
                  Instant created = now();
                  for (Long userId : recipients) {
                    NotificationDO row = new NotificationDO();
                    row.setId(IdWorker.getId());
                    row.setTenantId(event.tenantId());
                    row.setUserId(userId);
                    row.setType(type);
                    row.setParams(params);
                    row.setBizType("PREPAY");
                    row.setBizId(event.prepayId());
                    row.setCreateTime(created);
                    notifications.insertPrepayEvent(row);
                  }
                }));
  }

  public void record(PayoutAccountSubmittedEvent event) {
    tx.executeWithoutResult(
        status ->
            TenantScope.runCompany(
                event.tenantId(),
                () ->
                    write(
                        currentBd(event.kolId()),
                        NotificationType.PAYOUT_ACCOUNT_SUBMITTED,
                        new NotificationParamsDTO(
                            event.kolCode(), event.accountType(), null, null, null, null, null),
                        BIZ_PAYOUT_ACCOUNT,
                        event.accountId())));
  }

  public void record(OrderSyncFailedEvent event) {
    tx.executeWithoutResult(
        status ->
            TenantScope.runCompany(
                event.tenantId(),
                () ->
                    write(
                        null,
                        NotificationType.ORDER_SYNC_FAILED,
                        new NotificationParamsDTO(
                            null, null, event.shopName(), null, null, null, null),
                        BIZ_SHOP,
                        event.shopId())));
  }

  public void record(BdTransferCancelledEvent event) {
    tx.executeWithoutResult(
        status ->
            TenantScope.runCompany(
                event.tenantId(),
                () ->
                    write(
                        null,
                        NotificationType.BD_TRANSFER_CANCELLED,
                        new NotificationParamsDTO(
                            event.kolCode(), null, null, event.reasonCode(), null, null, null),
                        BIZ_BD_TRANSFER,
                        event.transferId())));
  }

  public void record(KolsUnassignedEvent event) {
    tx.executeWithoutResult(
        status ->
            TenantScope.runCompany(
                event.tenantId(),
                () ->
                    write(
                        null,
                        NotificationType.KOLS_UNASSIGNED,
                        new NotificationParamsDTO(
                            null, null, null, null, event.bdName(), event.count(), null),
                        BIZ_STAFF,
                        event.bdUserId())));
  }

  /** Reminds the super administrators to name the heir of a resignation just submitted. */
  public void record(DeparturePlanSubmittedEvent event) {
    tx.executeWithoutResult(
        status ->
            TenantScope.runCompany(
                event.tenantId(),
                () ->
                    write(
                        null,
                        NotificationType.DEPARTURE_PLAN_SUBMITTED,
                        new NotificationParamsDTO(
                            null,
                            null,
                            null,
                            null,
                            event.bdName(),
                            null,
                            null,
                            event.plannedLeaveAt().toString(),
                            null,
                            null),
                        BIZ_DEPARTURE,
                        event.userId())));
  }

  /** Announces what a plan did when it took effect. */
  public void record(DeparturePlanExecutedEvent event) {
    tx.executeWithoutResult(
        status ->
            TenantScope.runCompany(
                event.tenantId(),
                () ->
                    write(
                        null,
                        NotificationType.DEPARTURE_PLAN_EXECUTED,
                        new NotificationParamsDTO(
                            null,
                            null,
                            null,
                            null,
                            event.bdName(),
                            null,
                            event.heirName(),
                            event.effectiveAt().toString(),
                            event.resultCode(),
                            event.typeCode()),
                        BIZ_DEPARTURE,
                        event.userId())));
  }

  public PageResult<NotificationDTO> page(int page, int size, Boolean unread) {
    AccountDTO user = requireCompanyUser();
    Page<NotificationDO> result =
        notifications.selectPage(
            new Page<>(page, size),
            new LambdaQueryWrapper<NotificationDO>()
                .eq(NotificationDO::getUserId, user.id())
                .isNull(Boolean.TRUE.equals(unread), NotificationDO::getReadTime)
                .orderByDesc(NotificationDO::getCreateTime, NotificationDO::getId));
    return new PageResult<>(
        result.getRecords().stream().map(this::toDto).toList(), result.getTotal(), page, size);
  }

  public long unreadCount() {
    AccountDTO user = requireCompanyUser();
    Long count =
        notifications.selectCount(
            new LambdaQueryWrapper<NotificationDO>()
                .eq(NotificationDO::getUserId, user.id())
                .isNull(NotificationDO::getReadTime));
    return count == null ? 0L : count;
  }

  /** Marks one of the caller's messages read. Missing or someone else's message is a 404. */
  public void markRead(long id) {
    AccountDTO user = requireCompanyUser();
    NotificationDO row =
        notifications.selectOne(
            new LambdaQueryWrapper<NotificationDO>()
                .eq(NotificationDO::getId, id)
                .eq(NotificationDO::getUserId, user.id()));
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (row.getReadTime() != null) {
      return;
    }
    notifications.update(
        null,
        new LambdaUpdateWrapper<NotificationDO>()
            .eq(NotificationDO::getId, id)
            .eq(NotificationDO::getUserId, user.id())
            .isNull(NotificationDO::getReadTime)
            .set(NotificationDO::getReadTime, now()));
  }

  public void markAllRead() {
    AccountDTO user = requireCompanyUser();
    notifications.update(
        null,
        new LambdaUpdateWrapper<NotificationDO>()
            .eq(NotificationDO::getUserId, user.id())
            .isNull(NotificationDO::getReadTime)
            .set(NotificationDO::getReadTime, now()));
  }

  private void write(
      Long bdUserId,
      NotificationType type,
      NotificationParamsDTO params,
      String bizType,
      Long bizId) {
    List<Long> recipients = recipients(bdUserId);
    if (recipients.isEmpty()) {
      return;
    }
    String json = NotificationParamsCodec.write(type, params);
    Instant created = now();
    Long tenantId = TenantScope.companyId();
    for (Long userId : recipients) {
      NotificationDO row = new NotificationDO();
      row.setTenantId(tenantId);
      row.setUserId(userId);
      row.setType(type);
      row.setParams(json);
      row.setBizType(bizType);
      row.setBizId(bizId);
      row.setCreateTime(created);
      notifications.insert(row);
    }
  }

  private List<Long> recipients(Long bdUserId) {
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    if (bdUserId != null) {
      ids.add(bdUserId);
    }
    List<Long> admins = superAdmins.listEnabledIds();
    if (admins != null) {
      ids.addAll(admins);
    }
    return List.copyOf(ids);
  }

  private Long currentBd(long kolId) {
    List<KolRefDTO> refs = kols.listByIds(List.of(kolId));
    if (refs == null || refs.isEmpty()) {
      return null;
    }
    return refs.getFirst().bdUserId();
  }

  private AccountDTO requireCompanyUser() {
    AccountDTO user = current.user();
    if (user.role() == Role.PLATFORM_ADMIN || user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user;
  }

  private NotificationDTO toDto(NotificationDO row) {
    return new NotificationDTO(
        row.getId(),
        row.getType(),
        NotificationParamsCodec.read(row.getParams()),
        row.getBizType(),
        row.getBizId(),
        row.getReadTime(),
        row.getCreateTime());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private static TransactionTemplate requiresNew(TransactionTemplate source) {
    PlatformTransactionManager manager = source.getTransactionManager();
    if (manager == null) {
      throw new IllegalStateException("Transaction manager is required");
    }
    TransactionTemplate next = new TransactionTemplate(manager);
    next.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return next;
  }
}
