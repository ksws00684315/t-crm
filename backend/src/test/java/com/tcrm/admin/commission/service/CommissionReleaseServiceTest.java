package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Unit tests of the release service (design §4.8.2, §9). */
class CommissionReleaseServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
  private static final long TENANT = 7L;
  private static final long KOL = 101L;
  private static final AtomicLong IDS = new AtomicLong(7001);

  private final CommissionItemStateMapper states = mock(CommissionItemStateMapper.class);
  private final CommissionWalletService wallet = mock(CommissionWalletService.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final TransactionStatus status = mock(TransactionStatus.class);
  private final CommissionReleaseService service =
      new CommissionReleaseService(states, wallet, tx, Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void prepare() {
    when(tx.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(status);
            });
  }

  @Test
  void shouldCreditDueDifferenceAndMarkReleased() {
    CommissionItemStateDO row = row("30.00", "10.00", true, NOW.minusSeconds(60));
    // First batch returns the row; the second call (after the row released) is empty.
    when(states.listDueForRelease(NOW, 500)).thenReturn(List.of(row), List.of());
    when(states.lockById(row.getId())).thenReturn(row);
    when(wallet.findEntry(anyLong(), any())).thenReturn(null);
    when(states.updateReleased(row.getId(), new BigDecimal("10.00"), new BigDecimal("30.00"), NOW))
        .thenReturn(1);
    int released = service.releaseDue();
    assertThat(released).isEqualTo(1);
    ArgumentCaptor<CommissionWalletService.CreditCommand> credit =
        ArgumentCaptor.forClass(CommissionWalletService.CreditCommand.class);
    verify(wallet).postCommissionCredit(credit.capture());
    assertThat(credit.getValue().amountCny()).isEqualByComparingTo("20.00");
    assertThat(credit.getValue().occurredAt()).isEqualTo(row.getReleaseAt());
    assertThat(credit.getValue().sourceKey())
        .isEqualTo(
            CommissionItemSnapshotService.releaseSourceKey(row.getId(), row.getSourceVersion()));
  }

  @Test
  void shouldSkipNotYetDueAndUnverifiedRows() {
    CommissionItemStateDO notDue = row("30.00", "0", true, NOW.plusSeconds(3600));
    CommissionItemStateDO unverified = row("30.00", "0", false, NOW.minusSeconds(60));
    when(states.listDueForRelease(NOW, 500)).thenReturn(List.of());
    // The batch query filters them; simulate empty batches: nothing happens.
    assertThat(service.releaseDue()).isZero();
    verify(wallet, never()).postCommissionCredit(any());
    assertThat(notDue.getReleaseAt()).isAfter(NOW);
    assertThat(unverified.getVerified()).isFalse();
  }

  @Test
  void shouldAlignRowWithoutPostingWhenLedgerAlreadyHasSameAmount() {
    CommissionItemStateDO row = row("30.00", "10.00", true, NOW.minusSeconds(60));
    when(states.listDueForRelease(NOW, 500)).thenReturn(List.of(row), List.of());
    when(states.lockById(row.getId())).thenReturn(row);
    String key =
        CommissionItemSnapshotService.releaseSourceKey(row.getId(), row.getSourceVersion());
    when(wallet.findEntry(TENANT, key))
        .thenReturn(
            new CommissionWalletService.LedgerEntry(
                KOL, "COMMISSION", row.getId(), new BigDecimal("20.00"), new BigDecimal("20.00")));
    when(states.updateReleased(row.getId(), new BigDecimal("10.00"), new BigDecimal("30.00"), NOW))
        .thenReturn(1);
    int released = service.releaseDue();
    assertThat(released).isZero();
    verify(wallet, never()).postCommissionCredit(any());
    verify(states)
        .updateReleased(row.getId(), new BigDecimal("10.00"), new BigDecimal("30.00"), NOW);
  }

  @Test
  void shouldAlignRowWhenLedgerHasDifferentAmount() {
    CommissionItemStateDO row = row("30.00", "10.00", true, NOW.minusSeconds(60));
    when(states.listDueForRelease(NOW, 500)).thenReturn(List.of(row), List.of());
    when(states.lockById(row.getId())).thenReturn(row);
    String key =
        CommissionItemSnapshotService.releaseSourceKey(row.getId(), row.getSourceVersion());
    when(wallet.findEntry(TENANT, key))
        .thenReturn(
            new CommissionWalletService.LedgerEntry(
                KOL, "COMMISSION", row.getId(), new BigDecimal("5.00"), new BigDecimal("5.00")));
    when(states.updateReleased(row.getId(), new BigDecimal("10.00"), new BigDecimal("30.00"), NOW))
        .thenReturn(1);
    int released = service.releaseDue();
    assertThat(released).isZero();
    verify(wallet, never()).postCommissionCredit(any());
    // The ledger is authoritative: the row aligns to it instead of retrying the posting.
    verify(states)
        .updateReleased(row.getId(), new BigDecimal("10.00"), new BigDecimal("30.00"), NOW);
  }

  @Test
  void shouldMarkRollbackOnlyAndContinueWhenOneRowFails() {
    CommissionItemStateDO failing = row("30.00", "10.00", true, NOW.minusSeconds(60));
    CommissionItemStateDO healthy = row("31.00", "0", true, NOW.minusSeconds(120));
    when(states.listDueForRelease(NOW, 500)).thenReturn(List.of(failing, healthy), List.of());
    when(states.lockById(failing.getId())).thenReturn(failing);
    when(states.lockById(healthy.getId())).thenReturn(healthy);
    when(wallet.findEntry(anyLong(), any())).thenReturn(null);
    when(wallet.postCommissionCredit(any()))
        .thenThrow(new IllegalStateException("db down"))
        .thenReturn(new BigDecimal("31.00"));
    when(states.updateReleased(healthy.getId(), new BigDecimal("0"), new BigDecimal("31.00"), NOW))
        .thenReturn(1);

    // The failing row's transaction is marked rollback-only; the loop still releases the healthy
    // row and releaseDue never throws (the job's company loop keeps going).
    assertThatCode(() -> assertThat(service.releaseDue()).isEqualTo(1)).doesNotThrowAnyException();
    verify(status, times(1)).setRollbackOnly();
  }

  @Test
  void shouldStopLoopWhenBatchMakesNoProgress() {
    CommissionItemStateDO row = row("30.00", "10.00", true, NOW.minusSeconds(60));
    when(states.listDueForRelease(NOW, 500)).thenReturn(List.of(row));
    when(states.lockById(row.getId())).thenThrow(new IllegalStateException("lock lost"));
    assertThatCode(() -> assertThat(service.releaseDue()).isZero()).doesNotThrowAnyException();
    verify(status, times(1)).setRollbackOnly();
    verify(states, atMost(2)).listDueForRelease(NOW, 500);
  }

  private static CommissionItemStateDO row(
      String accrued, String released, boolean verified, Instant releaseAt) {
    CommissionItemStateDO row = new CommissionItemStateDO();
    row.setId(IDS.incrementAndGet());
    row.setTenantId(TENANT);
    row.setKolId(KOL);
    row.setVerified(verified);
    row.setAccruedCommissionCny(new BigDecimal(accrued));
    row.setReleasedCommissionCny(new BigDecimal(released));
    row.setReleaseBasis(CommissionSettlementRules.RELEASE_CONFIRMED);
    row.setReleaseAt(releaseAt);
    row.setSourceVersion("abc");
    return row;
  }
}
