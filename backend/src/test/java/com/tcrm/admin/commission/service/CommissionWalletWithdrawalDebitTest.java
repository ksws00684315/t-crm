package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.model.LedgerSourceRow;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.api.SystemConfigApi;
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
  private final SystemConfigApi configs = mock(SystemConfigApi.class);
  private CommissionWalletService service;

  @BeforeEach
  void prepare() {
    service = new CommissionWalletService(wallets, kols, configs, Clock.fixed(NOW, ZoneOffset.UTC));
    lenient().when(wallets.lockBalance(KOL)).thenReturn(new BigDecimal("400.00"));
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
  void shouldAllowNegativeResultingBalance_whenPrepayDeductionRacedIn() {
    // wallet 100.00, withdrawal 300.00 → balance -200.00 recovered by later commission credits
    when(wallets.lockBalance(KOL)).thenReturn(new BigDecimal("100.00"));
    var balance =
        service.postWithdrawalDebit(COMPANY, KOL, WITHDRAWAL, new BigDecimal("300.00"), NOW, 6L);
    assertThat(balance).isEqualByComparingTo("-200.00");
  }

  @Test
  void shouldRunInsideTheGivenCompanyScope() {
    service.postWithdrawalDebit(COMPANY, KOL, WITHDRAWAL, new BigDecimal("50.00"), NOW, 6L);
    // the wallet row is ensured inside the withdrawal's company scope
    verify(wallets).insertIgnore(anyLong(), eq(KOL), any(Instant.class));
  }
}
