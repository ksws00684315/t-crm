package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.PayoutAccountMapper;
import com.tcrm.admin.commission.mapper.PrepayMapper;
import com.tcrm.admin.commission.model.KolLedgerRow;
import com.tcrm.admin.commission.model.KolPaidPrepayRow;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.AccountNameApi;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Creator prepay reads stay inside the signed-in company and hide unpaid rows. */
class PrepayKolQueryTest {
  private static final Instant PAID_AT = Instant.parse("2026-10-22T04:00:00Z");
  private final PrepayMapper prepays = mock(PrepayMapper.class);
  private final AtomicLong company = new AtomicLong();
  private PrepayService service;

  @BeforeEach
  void prepare() {
    service =
        new PrepayService(
            prepays,
            mock(KolApi.class),
            mock(AccountNameApi.class),
            mock(PayoutAccountMapper.class),
            mock(BizFileService.class),
            mock(SecretCipher.class),
            mock(CommissionWalletService.class),
            mock(ExternalAuditApi.class));
    when(prepays.listPaidIds(22L, 20, 0L))
        .thenAnswer(
            invocation -> {
              company.set(TenantScope.companyId());
              return List.of(77L);
            });
    when(prepays.countPaid(22L)).thenReturn(1L);
    when(prepays.findPaid(22L, 77L)).thenReturn(paid(true, new BigDecimal("-200.00"), 99L));
    when(prepays.sumRecovered(99L)).thenReturn(new BigDecimal("100.00"));
    when(prepays.listLedger(22L, 10, 10L))
        .thenAnswer(
            invocation -> {
              company.set(TenantScope.companyId());
              return List.of(
                  new KolLedgerRow(
                      99L, 77L, "PP-test", "PREPAY_DEDUCT", new BigDecimal("-300"), PAID_AT, null));
            });
  }

  @Test
  void shouldListPaidRowsInsideTheLoginCompany() {
    PageResult<PrepayService.PaidPrepay> page = service.listPaid(11L, 22L, 1, 20);
    assertThat(company).hasValue(11L);
    assertThat(page.total()).isEqualTo(1L);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().deduction().remainingAmountCny()).isEqualTo("200.00");
    assertThat(page.items().getFirst().walletBalanceCny()).isEqualTo("-200.00");
  }

  @Test
  void shouldHideDeductionAndUseZeroBalance_whenThePaidRowHasNoLedger() {
    when(prepays.countPaidOne(22L, 77L)).thenReturn(1L);
    when(prepays.findPaid(22L, 77L)).thenReturn(paid(false, null, null));
    PrepayService.PaidPrepay row = service.paid(11L, 22L, 77L);
    assertThat(row.deduction()).isNull();
    assertThat(row.walletBalanceCny()).isEqualTo("0.00");
  }

  @Test
  void shouldReturnNotFound_whenThePaidCountIsMissing() {
    when(prepays.countPaidOne(22L, 77L)).thenReturn(0L);
    assertThatThrownBy(() -> service.paid(11L, 22L, 77L))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
    when(prepays.countPaidOne(22L, 77L)).thenReturn(null);
    assertThatThrownBy(() -> service.paid(11L, 22L, 77L)).isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldPageTheLedgerWithTheRequestedOffset() {
    when(prepays.countPaidPayments(22L)).thenReturn(1L);
    when(prepays.countPaidDeductions(22L)).thenReturn(null);
    PageResult<PrepayService.LedgerRow> page = service.ledger(11L, 22L, 2, 10);
    assertThat(company).hasValue(11L);
    assertThat(page.total()).isEqualTo(1L);
    assertThat(page.items().getFirst().amountCny()).isEqualTo("-300.00");
    assertThat(page.items().getFirst().balanceAfterCny()).isNull();
  }

  private static KolPaidPrepayRow paid(boolean deduct, BigDecimal balance, Long deductionId) {
    return new KolPaidPrepayRow(
        77L,
        "PP-test",
        deduct,
        new BigDecimal("300.00"),
        PAID_AT,
        "BANK",
        88L,
        balance,
        deductionId);
  }
}
