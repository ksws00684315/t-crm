package com.tcrm.admin.notification.service;

import com.tcrm.admin.kol.api.BdTransferCancelledEvent;
import com.tcrm.admin.kol.api.KolsUnassignedEvent;
import com.tcrm.admin.notification.api.PayoutAccountSubmittedEvent;
import com.tcrm.admin.notification.model.NotificationType;
import com.tcrm.admin.order.api.OrderSyncFailedEvent;
import com.tcrm.admin.system.api.DeparturePlanExecutedEvent;
import com.tcrm.admin.system.api.DeparturePlanSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes messages after the business transaction commits. A write failure is logged and does not
 * affect the business that already committed.
 */
@Component
public class NotificationListener {
  private static final Logger LOG = LoggerFactory.getLogger(NotificationListener.class);
  private final NotificationService notifications;

  public NotificationListener(NotificationService notifications) {
    this.notifications = notifications;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPayoutAccountSubmitted(PayoutAccountSubmittedEvent event) {
    safely(
        event.tenantId(),
        NotificationType.PAYOUT_ACCOUNT_SUBMITTED,
        () -> notifications.record(event));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onOrderSyncFailed(OrderSyncFailedEvent event) {
    safely(event.tenantId(), NotificationType.ORDER_SYNC_FAILED, () -> notifications.record(event));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onBdTransferCancelled(BdTransferCancelledEvent event) {
    safely(
        event.tenantId(),
        NotificationType.BD_TRANSFER_CANCELLED,
        () -> notifications.record(event));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onKolsUnassigned(KolsUnassignedEvent event) {
    safely(event.tenantId(), NotificationType.KOLS_UNASSIGNED, () -> notifications.record(event));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDeparturePlanSubmitted(DeparturePlanSubmittedEvent event) {
    safely(
        event.tenantId(),
        NotificationType.DEPARTURE_PLAN_SUBMITTED,
        () -> notifications.record(event));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDeparturePlanExecuted(DeparturePlanExecutedEvent event) {
    safely(
        event.tenantId(),
        NotificationType.DEPARTURE_PLAN_EXECUTED,
        () -> notifications.record(event));
  }

  private void safely(long tenantId, NotificationType type, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ex) {
      LOG.warn(
          "Failed to write notification {} for company {} ({})",
          type,
          tenantId,
          ex.getClass().getSimpleName());
    }
  }
}
