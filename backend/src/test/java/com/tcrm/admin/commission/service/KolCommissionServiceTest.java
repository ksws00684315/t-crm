package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.KolMoneyRow;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.PrepayPaidRow;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.PrepayPaymentRow;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper.KolAccrualRow;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests of the creator overview and fund records (requirement 11.02). */
class KolCommissionServiceTest {
  private static final long TENANT = 7L;
  private static final long KOL = 22L;

  private final CommissionItemStateMapper states = mock(CommissionItemStateMapper.class);
  private final CommissionFinanceQueryMapper finance = mock(CommissionFinanceQueryMapper.class);
  private final WithdrawalMapper withdrawals = mock(WithdrawalMapper.class);
  private final KolCommissionService service =
      new KolCommissionService(states, finance, withdrawals);

  @BeforeEach
  void prepare() {
    lenient().when(states.aggregateByKol(anyCollection())).thenReturn(List.of());
    lenient().when(finance.walletBalances(anyCollection())).thenReturn(List.of());
    lenient().when(finance.recoverySums(anyCollection())).thenReturn(List.of());
    lenient().when(finance.pendingDeductions(anyCollection())).thenReturn(List.of());
    lenient().when(finance.withdrawalPaidSums(anyCollection())).thenReturn(List.of());
    lenient().when(finance.prepayPaidSums(anyCollection(), any(), any())).thenReturn(List.of());
    lenient().when(finance.prepayPayments(anyCollection(), any(), any())).thenReturn(List.of());
    lenient().when(withdrawals.sumActiveOccupied(anyLong())).thenReturn(BigDecimal.ZERO);
    lenient().when(withdrawals.listKolPaidWithdrawals(anyLong())).thenReturn(List.of());
  }

  @Test
  void shouldReturnZeroOverviewWithoutAnyActivity() {
    var overview = TenantScope.callCompany(TENANT, () -> service.overview(TENANT, KOL));
    assertThat(overview.income().estimated()).isEqualTo("0.00");
    assertThat(overview.income().available()).isEqualTo("0.00");
    assertThat(overview.income().cumulativePaid()).isEqualTo("0.00");
    assertThat(overview.asOf()).isNotNull();
  }

  @Test
  void shouldComposeFiveLayerMoneyFromAggregates() {
    // Hand calc: estimated 500, frozen 200, balance 100, deducted 50, pending 150, prepay paid 300.
    when(states.aggregateByKol(List.of(KOL)))
        .thenReturn(
            List.of(
                new KolAccrualRow(KOL, new BigDecimal("500.00"), new BigDecimal("200.00"), 3L)));
    when(finance.walletBalances(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("100.00"))));
    when(finance.recoverySums(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("50.00"))));
    when(finance.pendingDeductions(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("150.00"))));
    when(finance.withdrawalPaidSums(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("20.00"))));
    when(finance.prepayPaidSums(eq(List.of(KOL)), any(), any()))
        .thenReturn(
            List.of(
                new PrepayPaidRow(
                    KOL, new BigDecimal("300.00"), Instant.parse("2026-10-22T08:00:00Z"))));
    var overview = TenantScope.callCompany(TENANT, () -> service.overview(TENANT, KOL));
    assertThat(overview.income().estimated()).isEqualTo("500.00");
    assertThat(overview.income().frozen()).isEqualTo("200.00");
    assertThat(overview.income().available()).isEqualTo("100.00");
    assertThat(overview.income().occupied()).isEqualTo("0.00");
    assertThat(overview.income().deducted()).isEqualTo("50.00");
    assertThat(overview.income().pendingDeduction()).isEqualTo("150.00");
    assertThat(overview.income().commissionPaid()).isEqualTo("20.00");
    assertThat(overview.income().prepayPaid()).isEqualTo("300.00");
    assertThat(overview.income().paid()).isEqualTo("320.00");
  }

  @Test
  void shouldPageUnifiedRecordsNewestFirstFromDatabaseOrder() {
    // The mapper returns newest first; the service only slices.
    when(finance.prepayPayments(eq(List.of(KOL)), any(), any()))
        .thenReturn(
            List.of(
                payment(2L, "300.00", Instant.parse("2026-10-22T08:00:00Z")),
                payment(1L, "100.00", Instant.parse("2026-10-21T08:00:00Z"))));
    PageResult<KolCommissionService.FundRecord> page =
        TenantScope.callCompany(TENANT, () -> service.fundRecords(TENANT, KOL, 2, 1));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().id()).isEqualTo(1L);
    assertThat(page.items().getFirst().type()).isEqualTo("PREPAY");
    assertThat(page.items().getFirst().publicStatus()).isEqualTo("PAID");
  }

  @Test
  void shouldReturnEmptyPageBeyondRange() {
    PageResult<KolCommissionService.FundRecord> page =
        TenantScope.callCompany(TENANT, () -> service.fundRecords(TENANT, KOL, 5, 20));
    assertThat(page.total()).isZero();
    assertThat(page.items()).isEmpty();
  }

  private static PrepayPaymentRow payment(long id, String amount, Instant paidAt) {
    return new PrepayPaymentRow(
        id,
        7000L + id,
        "PP-" + id,
        "PREPAY",
        KOL,
        new BigDecimal(amount),
        paidAt,
        "ab***@example.test",
        55L,
        true,
        "PAID",
        paidAt.minusSeconds(3600));
  }
}
