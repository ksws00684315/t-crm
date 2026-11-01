package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.model.LedgerSourceRow;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@code postWithdrawalDebit} of the shared wallet (task 63): ledger row and balance. */
class CommissionWalletWithdrawalDebitTest {
  private static final long COMPANY = 11L;
  private static final long KOL = 22L;
  private static final long WITHDRAWAL = 77L;
  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00.987Z");

  private final CommissionWalletMapper wallets = mock(CommissionWalletMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private CommissionWalletService service;

  @BeforeEach
  void prepare() {
    service = new CommissionWalletService(wallets, kols, Clock.fixed(NOW, ZoneOffset.UTC));
    lenient().when(wallets.lockBalance(KOL)).thenReturn(new BigDecimal("400.00"));
  }

  /** Owner decision 2026-11-01: 余额等于金额允许(付完为 0),不足即拒绝(422),余额不动。 */
  @Test
  void shouldAllowThePayment_whenTheBalanceEqualsTheAmount() {
    lenient().when(wallets.lockBalance(KOL)).thenReturn(new BigDecimal("300.00"));
    var balance =
        service.postWithdrawalDebit(COMPANY, KOL, WITHDRAWAL, new BigDecimal("300"), NOW, 6L);
    assertThat(balance).isEqualByComparingTo("0.00");
  }

  @Test
  void shouldRefuseThePayment_whenTheBalanceIsShort_andLeaveTheWalletUntouched() {
    when(wallets.lockBalance(KOL)).thenReturn(new BigDecimal("100.00"));
    assertThatThrownBy(
            () ->
                service.postWithdrawalDebit(
                    COMPANY, KOL, WITHDRAWAL, new BigDecimal("300"), NOW, 6L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_INSUFFICIENT_AVAILABLE));
    verify(wallets, never())
        .insertLedger(
            anyLong(), anyLong(), any(), any(), any(), anyLong(), any(), any(), any(), anyLong(),
            any());
    verify(wallets, never()).updateBalance(anyLong(), any(), any());
  }

  @Test
  void shouldPostNegativeWithdrawalEntry_andLowerTheBalance() {
    var balance =
        service.postWithdrawalDebit(COMPANY, KOL, WITHDRAWAL, new BigDecimal("300"), NOW, 6L);
    // 400.00 - 300.00 = 100.00 (hand computed)
    assertThat(balance).isEqualByComparingTo("100.00");
    ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
    verify(wallets)
        .insertLedger(
            anyLong(),
            eq(KOL),
            eq("COMMISSION_WITHDRAWAL"),
            amount.capture(),
            eq("WITHDRAWAL"),
            eq(WITHDRAWAL),
            eq("WITHDRAWAL:" + WITHDRAWAL),
            eq(NOW.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)),
            eq(new BigDecimal("100.00")),
            eq(6L),
            any(Instant.class));
    assertThat(amount.getValue()).isEqualByComparingTo("-300.00");
    verify(wallets).updateBalance(eq(KOL), eq(new BigDecimal("100.00")), any(Instant.class));
  }

  @Test
  void shouldReplayTheSameSourceKey_withoutDoubleBooking() {
    when(wallets.findBySourceKey("WITHDRAWAL:" + WITHDRAWAL))
        .thenReturn(
            new LedgerSourceRow(
                new BigDecimal("-300.00"),
                new BigDecimal("100.00"),
                KOL,
                "WITHDRAWAL",
                WITHDRAWAL));
    var balance =
        service.postWithdrawalDebit(COMPANY, KOL, WITHDRAWAL, new BigDecimal("300.00"), NOW, 6L);
    assertThat(balance).isEqualByComparingTo("100.00");
    org.mockito.Mockito.verify(wallets, org.mockito.Mockito.never())
        .updateBalance(anyLong(), any(BigDecimal.class), any(Instant.class));
  }

  @Test
  void shouldRejectNonPositiveOrOverPreciseAmounts() {
    assertThatThrownBy(
            () -> service.postWithdrawalDebit(COMPANY, KOL, WITHDRAWAL, BigDecimal.ZERO, NOW, 6L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    assertThatThrownBy(
            () ->
                service.postWithdrawalDebit(
                    COMPANY, KOL, WITHDRAWAL, new BigDecimal("1.234"), NOW, 6L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  @Test
  void shouldRefuseThePayment_whenAPrepayDeductionLeftTheBalanceShort() {
    // Changed with the 2026-11-01 owner decision (was: allowed the wallet to go negative when a
    // prepay deduction raced in between approval and payment). A payment must no longer drive the
    // wallet below zero: wallet 100.00, withdrawal 300.00 → refused 422, wallet untouched; the
    // shortfall is handled by the finance side (retry after refill or reject), and the negative
    // balances that DO appear come from later clawbacks/COMMISSION_ADJUSTMENT instead.
    when(wallets.lockBalance(KOL)).thenReturn(new BigDecimal("100.00"));
    assertThatThrownBy(
            () ->
                service.postWithdrawalDebit(
                    COMPANY, KOL, WITHDRAWAL, new BigDecimal("300.00"), NOW, 6L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_INSUFFICIENT_AVAILABLE));
  }

  @Test
  void shouldRunInsideTheGivenCompanyScope() {
    service.postWithdrawalDebit(COMPANY, KOL, WITHDRAWAL, new BigDecimal("50.00"), NOW, 6L);
    // the wallet row is ensured inside the withdrawal's company scope
    verify(wallets).insertIgnore(anyLong(), eq(KOL), any(Instant.class));
  }
}
