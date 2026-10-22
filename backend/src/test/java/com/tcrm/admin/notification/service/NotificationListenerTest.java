package com.tcrm.admin.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.tcrm.admin.commission.api.PayoutAccountSubmittedEvent;
import com.tcrm.admin.kol.api.BdTransferCancelledEvent;
import com.tcrm.admin.kol.api.KolsUnassignedEvent;
import com.tcrm.admin.order.api.OrderSyncFailedEvent;
import com.tcrm.admin.system.api.DeparturePlanExecutedEvent;
import com.tcrm.admin.system.api.DeparturePlanSubmittedEvent;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class NotificationListenerTest {
  private final NotificationService notifications = mock(NotificationService.class);
  private final NotificationListener listener = new NotificationListener(notifications);

  @Test
  void shouldSwallowAWriteFailure() {
    doThrow(new IllegalStateException("database"))
        .when(notifications)
        .record(org.mockito.ArgumentMatchers.any(PayoutAccountSubmittedEvent.class));
    assertThatCode(
            () ->
                listener.onPayoutAccountSubmitted(
                    new PayoutAccountSubmittedEvent(1L, 2L, 3L, "KOL0002", "PAYPAL")))
        .doesNotThrowAnyException();
    assertThatCode(() -> listener.onOrderSyncFailed(new OrderSyncFailedEvent(1L, 9L, "Shop")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                listener.onBdTransferCancelled(
                    new BdTransferCancelledEvent(1L, 2L, 3L, "KOL0002", "OWNERSHIP_CHANGED")))
        .doesNotThrowAnyException();
    assertThatCode(() -> listener.onKolsUnassigned(new KolsUnassignedEvent(1L, 5L, "Blake", 1)))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                listener.onDeparturePlanSubmitted(
                    new DeparturePlanSubmittedEvent(
                        1L, 5L, "Blake", Instant.parse("2026-10-30T10:00:00Z"))))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                listener.onDeparturePlanExecuted(
                    new DeparturePlanExecutedEvent(
                        1L,
                        5L,
                        "Blake",
                        "RESIGN",
                        Instant.parse("2026-10-30T10:00:00Z"),
                        "Carol",
                        "TRANSFERRED")))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldListenAfterCommit() throws Exception {
    int listeners = 0;
    for (Method method : NotificationListener.class.getDeclaredMethods()) {
      TransactionalEventListener annotation =
          method.getAnnotation(TransactionalEventListener.class);
      if (annotation == null) {
        continue;
      }
      listeners++;
      assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
    assertThat(listeners).isEqualTo(6);
  }
}
