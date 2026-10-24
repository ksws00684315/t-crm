package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.PrepayOutboxMapper;
import com.tcrm.admin.commission.model.PrepayOutboxClaim;
import com.tcrm.admin.notification.api.PrepayNotification;
import com.tcrm.admin.notification.api.PrepayNotificationApi;
import com.tcrm.admin.shared.TenantScope;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PrepayOutboxDeliveryTest {
  private final PrepayOutboxMapper outbox = mock(PrepayOutboxMapper.class);
  private final PrepayNotificationApi notifications = mock(PrepayNotificationApi.class);
  private final PrepayOutboxDelivery delivery = new PrepayOutboxDelivery(outbox, notifications);

  @Test
  void shouldDoNothing_whenNoEventIsDue() {
    when(outbox.claimDue(any())).thenReturn(null);
    delivery.deliverOne();
    verify(notifications, never()).deliver(any());
    verify(outbox, never()).markDelivered(any(Long.class), any());
    verify(outbox, never()).defer(any(Long.class), any());
  }

  @Test
  void shouldDeliverAndMarkTheRowInsideTheEventCompany() {
    when(outbox.claimDue(any())).thenReturn(claim());
    doAnswer(
            invocation -> {
              assertThat(TenantScope.companyId()).isEqualTo(8L);
              return null;
            })
        .when(notifications)
        .deliver(any());
    delivery.deliverOne();
    ArgumentCaptor<PrepayNotification> sent = ArgumentCaptor.forClass(PrepayNotification.class);
    verify(notifications).deliver(sent.capture());
    assertThat(sent.getValue())
        .isEqualTo(new PrepayNotification(8L, 77L, 22L, 5L, "PP-test", "PAID"));
    verify(outbox).markDelivered(org.mockito.ArgumentMatchers.eq(4L), any());
    verify(outbox, never()).defer(any(Long.class), any());
  }

  @Test
  void shouldDefer_whenDeliveryFails() {
    when(outbox.claimDue(any())).thenReturn(claim());
    doThrow(new IllegalStateException("down")).when(notifications).deliver(any());
    Instant before = Instant.now();
    delivery.deliverOne();
    ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
    verify(outbox).defer(org.mockito.ArgumentMatchers.eq(4L), next.capture());
    verify(outbox, never()).markDelivered(any(Long.class), any());
    assertThat(next.getValue()).isAfterOrEqualTo(before.plusSeconds(60));
  }

  private static PrepayOutboxClaim claim() {
    return new PrepayOutboxClaim(4L, 8L, 77L, "PAID", 22L, 5L, "PP-test");
  }
}
