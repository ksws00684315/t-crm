package com.tcrm.admin.commission.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/** Wallet reconciliation (design §4.8): balance vs ledger, occupation inside the balance. */
class CommissionWalletReconcileJobTest {
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final EnabledTenantApi tenants = mock(EnabledTenantApi.class);
  private final CommissionWalletMapper wallets = mock(CommissionWalletMapper.class);
  private final WithdrawalMapper withdrawals = mock(WithdrawalMapper.class);
  private final CommissionWalletReconcileJob job =
      new CommissionWalletReconcileJob(locks, tenants, wallets, withdrawals);
  private final Logger logger =
      (Logger) LoggerFactory.getLogger(CommissionWalletReconcileJob.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void prepare() throws InterruptedException {
    logger.setLevel(Level.INFO);
    appender.start();
    logger.addAppender(appender);
    // run the locked body directly so the reconciliation logic executes in the test JVM
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(3)).run();
              return true;
            })
        .when(locks)
        .run(org.mockito.ArgumentMatchers.anyString(), anyLong(), anyLong(), any(Runnable.class));
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  @Test
  void shouldPassQuietly_whenBalanceEqualsLedgerAndOccupationFits() throws InterruptedException {
    when(wallets.listWalletKolIds()).thenReturn(List.of(22L));
    when(wallets.findBalance(22L)).thenReturn(new BigDecimal("250.00"));
    when(wallets.sumLedgerCny(22L)).thenReturn(new BigDecimal("250.00"));
    when(withdrawals.sumFundingOccupied(22L)).thenReturn(new BigDecimal("250.00"));
    job.reconcile();
    verify(wallets).findBalance(22L);
  }

  @Test
  void shouldComparePerCreator_evenWhenTheFirstMismatches() throws InterruptedException {
    // creator 22 drifts from its ledger; creator 23 is still consistent — both get compared
    when(wallets.listWalletKolIds()).thenReturn(List.of(22L, 23L));
    when(wallets.findBalance(22L)).thenReturn(new BigDecimal("250.00"));
    when(wallets.sumLedgerCny(22L)).thenReturn(new BigDecimal("300.00"));
    when(withdrawals.sumFundingOccupied(22L)).thenReturn(new BigDecimal("0.00"));
    AtomicInteger checked = new AtomicInteger();
    when(wallets.findBalance(23L))
        .thenAnswer(
            invocation -> {
              checked.incrementAndGet();
              return new BigDecimal("10.00");
            });
    when(wallets.sumLedgerCny(23L)).thenReturn(new BigDecimal("10.00"));
    when(withdrawals.sumFundingOccupied(23L)).thenReturn(new BigDecimal("0.00"));
    job.reconcile();
    assertThat(checked.get()).isEqualTo(1);
  }

  @Test
  void shouldFlagOccupationBeyondBalance_asItsOwnMismatch() throws InterruptedException {
    // balance equals the ledger, but a prepay deduction raced past an approved withdrawal
    when(wallets.listWalletKolIds()).thenReturn(List.of(22L));
    when(wallets.findBalance(22L)).thenReturn(new BigDecimal("100.00"));
    when(wallets.sumLedgerCny(22L)).thenReturn(new BigDecimal("100.00"));
    when(withdrawals.sumFundingOccupied(22L)).thenReturn(new BigDecimal("250.00"));
    job.reconcile();
    ArgumentCaptor<Long> kol = ArgumentCaptor.forClass(Long.class);
    verify(withdrawals).sumFundingOccupied(kol.capture());
    assertThat(kol.getValue()).isEqualTo(22L);
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(line -> line.startsWith("withdrawal occupation exceeds wallet balance"));
  }

  @Test
  void shouldStayQuiet_whenTheBalanceIsNegativeWithoutWithdrawals() throws InterruptedException {
    // A prepay deduction (or a clawback) may leave a creator in debt; later credits recover it, so
    // an occupation of zero never exceeds the balance and the daily run must not log an error.
    when(wallets.listWalletKolIds()).thenReturn(List.of(22L));
    when(wallets.findBalance(22L)).thenReturn(new BigDecimal("-500.00"));
    when(wallets.sumLedgerCny(22L)).thenReturn(new BigDecimal("-500.00"));
    when(withdrawals.sumFundingOccupied(22L)).thenReturn(new BigDecimal("0.00"));
    job.reconcile();
    assertThat(appender.list).isEmpty();
  }

  @Test
  void shouldFlagOccupation_whenTheBalanceIsNegativeAndWithdrawalsExist()
      throws InterruptedException {
    // A negative balance together with a live withdrawal stays the case that needs manual
    // attention (task 63).
    when(wallets.listWalletKolIds()).thenReturn(List.of(22L));
    when(wallets.findBalance(22L)).thenReturn(new BigDecimal("-500.00"));
    when(wallets.sumLedgerCny(22L)).thenReturn(new BigDecimal("-500.00"));
    when(withdrawals.sumFundingOccupied(22L)).thenReturn(new BigDecimal("100.00"));
    job.reconcile();
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(line -> line.startsWith("withdrawal occupation exceeds wallet balance"));
  }

  @Test
  void shouldRunEachEnabledTenant_insideItsCompanyScope() throws InterruptedException {
    when(wallets.listWalletKolIds()).thenReturn(List.of());
    AtomicInteger tenants_seen = new AtomicInteger();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (TenantScope.companyId() != null) {
                tenants_seen.incrementAndGet();
              }
              return List.of();
            })
        .when(wallets)
        .listWalletKolIds();
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L, 2L));
    job.reconcile();
    assertThat(tenants_seen.get()).isEqualTo(2);
  }

  @Test
  void shouldSkipTheOccupationCheck_whenTheWalletRowIsMissing() throws InterruptedException {
    // A missing wallet row is already a balance-vs-ledger mismatch; the occupation comparison
    // only makes sense against a real balance, so the job flags and moves on.
    when(wallets.listWalletKolIds()).thenReturn(List.of(22L));
    when(wallets.findBalance(22L)).thenReturn(null);
    when(wallets.sumLedgerCny(22L)).thenReturn(BigDecimal.ZERO);
    job.reconcile();
    verify(wallets).sumLedgerCny(22L);
    verify(withdrawals, org.mockito.Mockito.never()).sumFundingOccupied(anyLong());
  }
}
