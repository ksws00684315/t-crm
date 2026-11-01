package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.model.WithdrawalWrite;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Refund pause linkage (L-032, ADR-032 §6 退款阻断联动): the locking order, the re-checks under the lock
 * and the PAUSED/RESUMED history rows. SQL behavior is covered by the ITs.
 */
class WithdrawalPauseServiceTest {
  private static final long ITEM_STATE = 501L;
  private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");

  private final WithdrawalMapper withdrawals = mock(WithdrawalMapper.class);
  private final WithdrawalPauseService service =
      new WithdrawalPauseService(withdrawals, Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void prepare() {
    when(withdrawals.findActiveIdsByItemState(anyLong(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(List.of());
  }

  @Test
  void shouldPauseHoldingApplications_andWriteSystemPausedHistory() {
    when(withdrawals.findActiveIdsByItemState(ITEM_STATE, false)).thenReturn(List.of(7L, 3L));
    when(withdrawals.lockById(3L)).thenReturn(3L);
    when(withdrawals.lockById(7L)).thenReturn(7L);
    when(withdrawals.pause(anyLong(), any(), any())).thenReturn(1);

    service.pauseForItem(ITEM_STATE);

    // Ascending lock order regardless of the candidate order: payment takes the same single-row
    // FOR UPDATE, so the two transactions serialize instead of interleaving.
    var order = org.mockito.Mockito.inOrder(withdrawals);
    order.verify(withdrawals).lockById(3L);
    order.verify(withdrawals).pause(eq(3L), eq(WithdrawalPauseService.PAUSE_REASON), eq(NOW));
    order.verify(withdrawals).lockById(7L);
    order.verify(withdrawals).pause(eq(7L), eq(WithdrawalPauseService.PAUSE_REASON), eq(NOW));
    ArgumentCaptor<WithdrawalWrite.History> history =
        ArgumentCaptor.forClass(WithdrawalWrite.History.class);
    verify(withdrawals, org.mockito.Mockito.times(2)).insertHistory(history.capture());
    assertThat(history.getAllValues())
        .allSatisfy(
            row -> {
              assertThat(row.action()).isEqualTo("PAUSED");
              assertThat(row.actorId()).isNull();
              assertThat(row.actorRole()).isEqualTo("SYSTEM");
              assertThat(row.note()).isEqualTo(WithdrawalPauseService.PAUSE_REASON);
              assertThat(row.createTime()).isEqualTo(NOW);
            });
  }

  @Test
  void shouldSkipApplicationThatFinishedWhileWaitingForTheLock() {
    when(withdrawals.findActiveIdsByItemState(ITEM_STATE, false)).thenReturn(List.of(7L));
    when(withdrawals.lockById(7L)).thenReturn(7L);
    // The re-check in the UPDATE: the application was paid while the lock was waited for.
    when(withdrawals.pause(eq(7L), any(), any())).thenReturn(0);

    service.pauseForItem(ITEM_STATE);

    verify(withdrawals, never()).insertHistory(any());
  }

  @Test
  void shouldResumeOnlyWhenNoAllocatedLineBlocksAnyMore_andWriteResumedHistory() {
    when(withdrawals.findActiveIdsByItemState(ITEM_STATE, true)).thenReturn(List.of(9L));
    when(withdrawals.lockById(9L)).thenReturn(9L);
    when(withdrawals.countBlockedItems(9L)).thenReturn(0L);
    when(withdrawals.resume(9L, NOW)).thenReturn(1);

    service.resumeForItem(ITEM_STATE);

    verify(withdrawals).resume(9L, NOW);
    ArgumentCaptor<WithdrawalWrite.History> history =
        ArgumentCaptor.forClass(WithdrawalWrite.History.class);
    verify(withdrawals).insertHistory(history.capture());
    assertThat(history.getValue().action()).isEqualTo("RESUMED");
    assertThat(history.getValue().actorId()).isNull();
    assertThat(history.getValue().actorRole()).isEqualTo("SYSTEM");
    assertThat(history.getValue().note()).isNull();
  }

  @Test
  void shouldKeepApplicationPaused_whenAnotherAllocatedLineStillBlocks() {
    when(withdrawals.findActiveIdsByItemState(ITEM_STATE, true)).thenReturn(List.of(9L));
    when(withdrawals.lockById(9L)).thenReturn(9L);
    // The application holds a second line whose refund is still open.
    when(withdrawals.countBlockedItems(9L)).thenReturn(1L);

    service.resumeForItem(ITEM_STATE);

    verify(withdrawals, never()).resume(anyLong(), any());
    verify(withdrawals, never()).insertHistory(any());
  }

  @Test
  void shouldNotTouchAlreadyResumedApplication() {
    when(withdrawals.findActiveIdsByItemState(ITEM_STATE, true)).thenReturn(List.of(9L));
    when(withdrawals.lockById(9L)).thenReturn(9L);
    when(withdrawals.countBlockedItems(9L)).thenReturn(0L);
    // The application was withdrawn while the lock was waited for.
    when(withdrawals.resume(9L, NOW)).thenReturn(0);

    service.resumeForItem(ITEM_STATE);

    verify(withdrawals, never()).insertHistory(any());
  }
}
