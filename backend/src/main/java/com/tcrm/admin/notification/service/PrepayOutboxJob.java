package com.tcrm.admin.notification.service;

import com.tcrm.admin.notification.api.PrepayNotification;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Delivers prepay notification outbox rows with retry and recipient-level idempotency. */
@Component
public class PrepayOutboxJob {
  private static final Logger LOG = LoggerFactory.getLogger(PrepayOutboxJob.class);
  private final JdbcTemplate jdbc;
  private final NotificationService notifications;

  public PrepayOutboxJob(JdbcTemplate jdbc, NotificationService notifications) {
    this.jdbc = jdbc;
    this.notifications = notifications;
  }

  @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
  @Transactional(rollbackFor = Exception.class)
  public void deliverOne() {
    List<OutboxEvent> events =
        jdbc.query(
            "SELECT o.id, o.tenant_id, o.prepay_id, o.event_type, p.kol_id, p.applicant_id, p.prepay_no FROM crm_prepay_outbox o JOIN crm_prepay p ON p.tenant_id = o.tenant_id AND p.id = o.prepay_id WHERE o.delivered_at IS NULL AND o.next_attempt_at <= ? ORDER BY o.next_attempt_at, o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED",
            (rs, index) ->
                new OutboxEvent(
                    rs.getLong("id"),
                    rs.getLong("tenant_id"),
                    rs.getLong("prepay_id"),
                    rs.getString("event_type"),
                    rs.getLong("kol_id"),
                    rs.getLong("applicant_id"),
                    rs.getString("prepay_no")),
            Timestamp.from(Instant.now()));
    if (events.isEmpty()) {
      return;
    }
    OutboxEvent event = events.getFirst();
    try {
      notifications.deliver(
          new PrepayNotification(
              event.tenantId(),
              event.prepayId(),
              event.kolId(),
              event.applicantId(),
              event.prepayNo(),
              event.type()));
      jdbc.update(
          "UPDATE crm_prepay_outbox SET delivered_at = ?, attempts = attempts + 1 WHERE tenant_id = ? AND id = ? AND delivered_at IS NULL",
          Timestamp.from(Instant.now()),
          event.tenantId(),
          event.id());
    } catch (RuntimeException exception) {
      jdbc.update(
          "UPDATE crm_prepay_outbox SET attempts = attempts + 1, next_attempt_at = ? WHERE tenant_id = ? AND id = ? AND delivered_at IS NULL",
          Timestamp.from(Instant.now().plusSeconds(60)),
          event.tenantId(),
          event.id());
      LOG.warn(
          "Prepay notification delivery failed for task {} ({})",
          event.prepayNo(),
          exception.getClass().getSimpleName());
    }
  }

  private record OutboxEvent(
      long id,
      long tenantId,
      long prepayId,
      String type,
      long kolId,
      long applicantId,
      String prepayNo) {}
}
