package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.WithdrawalOutboxMapper;
import com.tcrm.admin.commission.model.WithdrawalOutboxClaim;
import com.tcrm.admin.notification.api.WithdrawalNotification;
import com.tcrm.admin.notification.api.WithdrawalNotificationApi;
import com.tcrm.admin.shared.TenantScope;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WithdrawalOutboxDeliveryTest {
  private final WithdrawalOutboxMapper outbox = mock(WithdrawalOutboxMapper.class);
  private final WithdrawalNotificationApi notifications = mock(WithdrawalNotificationApi.class);
  private final WithdrawalOutboxDelivery delivery =
      new WithdrawalOutboxDelivery(outbox, notifications);

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
    ArgumentCaptor<WithdrawalNotification> sent =
        ArgumentCaptor.forClass(WithdrawalNotification.class);
    verify(notifications).deliver(sent.capture());
    assertThat(sent.getValue())
        .isEqualTo(
            new WithdrawalNotification(8L, 77L, 22L, "KOL-test", "CW20261025000001", "PAID"));
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

  private static WithdrawalOutboxClaim claim() {
    return new WithdrawalOutboxClaim(4L, 8L, 77L, "PAID", 22L, "KOL-test", "CW20261025000001");
  }
}
