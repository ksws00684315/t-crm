package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.WithdrawalOutboxMapper;
import com.tcrm.admin.commission.model.WithdrawalOutboxClaim;
import com.tcrm.admin.notification.api.WithdrawalNotification;
import com.tcrm.admin.notification.api.WithdrawalNotificationApi;
import com.tcrm.admin.shared.TenantScope;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers one withdrawal outbox row inside the caller's transaction, so a failure can defer the
 * row and commit together with the attempt. Notification is written through {@link
 * WithdrawalNotificationApi} in that same transaction.
 */
@Service
public class WithdrawalOutboxDelivery {
  private static final Logger LOG = LoggerFactory.getLogger(WithdrawalOutboxDelivery.class);
  private final WithdrawalOutboxMapper outbox;
  private final WithdrawalNotificationApi notifications;

  public WithdrawalOutboxDelivery(
      WithdrawalOutboxMapper outbox, WithdrawalNotificationApi notifications) {
    this.outbox = outbox;
    this.notifications = notifications;
  }

  @Transactional(rollbackFor = Exception.class)
  public void deliverOne() {
    TenantScope.runSystem(
        "deliver withdrawal notification outbox across companies",
        () -> {
          WithdrawalOutboxClaim event = outbox.claimDue(Instant.now());
          if (event == null) {
            return;
          }
          try {
            TenantScope.runCompany(
                event.tenantId(),
                () -> {
                  notifications.deliver(
                      new WithdrawalNotification(
                          event.tenantId(),
                          event.withdrawalId(),
                          event.kolId(),
                          event.kolCode(),
                          event.applicationNo(),
                          event.eventType()));
                  outbox.markDelivered(event.id(), Instant.now());
                });
          } catch (RuntimeException exception) {
            TenantScope.runCompany(
                event.tenantId(), () -> outbox.defer(event.id(), Instant.now().plusSeconds(60)));
            LOG.warn(
                "Withdrawal notification delivery failed for task {} ({})",
                event.applicationNo(),
                exception.getClass().getSimpleName());
          }
        });
  }
}
