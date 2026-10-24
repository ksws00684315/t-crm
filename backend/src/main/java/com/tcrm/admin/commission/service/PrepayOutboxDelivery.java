package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.PrepayOutboxMapper;
import com.tcrm.admin.commission.model.PrepayOutboxClaim;
import com.tcrm.admin.notification.api.PrepayNotification;
import com.tcrm.admin.notification.api.PrepayNotificationApi;
import com.tcrm.admin.shared.TenantScope;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers one prepay outbox row inside the caller's transaction, so a failure can defer the row
 * and commit together with the attempt. Notification is written through {@link
 * PrepayNotificationApi} in that same transaction.
 */
@Service
public class PrepayOutboxDelivery {
  private static final Logger LOG = LoggerFactory.getLogger(PrepayOutboxDelivery.class);
  private final PrepayOutboxMapper outbox;
  private final PrepayNotificationApi notifications;

  public PrepayOutboxDelivery(PrepayOutboxMapper outbox, PrepayNotificationApi notifications) {
    this.outbox = outbox;
    this.notifications = notifications;
  }

  @Transactional(rollbackFor = Exception.class)
  public void deliverOne() {
    TenantScope.runSystem(
        "deliver prepay notification outbox across companies",
        () -> {
          PrepayOutboxClaim event = outbox.claimDue(Instant.now());
          if (event == null) {
            return;
          }
          try {
            TenantScope.runCompany(
                event.tenantId(),
                () -> {
                  notifications.deliver(
                      new PrepayNotification(
                          event.tenantId(),
                          event.prepayId(),
                          event.kolId(),
                          event.applicantId(),
                          event.prepayNo(),
                          event.eventType()));
                  outbox.markDelivered(event.id(), Instant.now());
                });
          } catch (RuntimeException exception) {
            TenantScope.runCompany(
                event.tenantId(), () -> outbox.defer(event.id(), Instant.now().plusSeconds(60)));
            LOG.warn(
                "Prepay notification delivery failed for task {} ({})",
                event.prepayNo(),
                exception.getClass().getSimpleName());
          }
        });
  }
}
