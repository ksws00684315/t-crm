package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
  private final WithdrawalFundingSource funding = mock(WithdrawalFundingSource.class);
  private final KolCommissionService service =
      new KolCommissionService(states, finance, withdrawals, funding);

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
    // Default row-level allocatable mirrors the wallet remainder so the tests keep their hand
    // computed five-layer numbers; the cap itself gets its own test below.
    lenient()
        .when(funding.availableCny(anyLong(), any(), any()))
        .thenAnswer(
            invocation -> {
              BigDecimal balance = invocation.getArgument(1);
              BigDecimal occupied = invocation.getArgument(2);
              return balance.subtract(occupied);
            });
    lenient().when(funding.frozenShortfallCny(anyLong(), any(), any())).thenReturn(BigDecimal.ZERO);
    lenient().when(withdrawals.listKolWithdrawals(anyLong())).thenReturn(List.of());
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
  void shouldKeepTheFiveLayerEquation_whenBlockedReleasedMoneyLeavesTheAvailableFigure() {
    // 预估 6.50 = 冻结 4.33 + 可提现 2.17 + 占用 0.00;the blocked released line stays visible.
    when(states.aggregateByKol(List.of(KOL)))
        .thenReturn(List.of(new KolAccrualRow(KOL, new BigDecimal("6.50"), BigDecimal.ZERO, 0L)));
    when(finance.walletBalances(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("6.50"))));
    when(funding.availableCny(KOL, new BigDecimal("6.50"), BigDecimal.ZERO))
        .thenReturn(new BigDecimal("2.17"));
    when(funding.frozenShortfallCny(KOL, new BigDecimal("6.50"), BigDecimal.ZERO))
        .thenReturn(new BigDecimal("4.33"));
    var overview = TenantScope.callCompany(TENANT, () -> service.overview(TENANT, KOL));
    assertThat(overview.income().estimated()).isEqualTo("6.50");
    assertThat(overview.income().frozen()).isEqualTo("4.33");
    assertThat(overview.income().available()).isEqualTo("2.17");
    assertThat(overview.income().occupied()).isEqualTo("0.00");
  }

  @Test
  void shouldCapOverviewAvailableAtRowLevelAllocatable_whenLineIsInAfterSales() {
    // Hand calc: balance 100.00, occupied 0.00, but the released line sits in an open refund, so
    // the row level only funds 30.00 (L-033).
    when(finance.walletBalances(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("100.00"))));
    when(funding.availableCny(KOL, new BigDecimal("100.00"), BigDecimal.ZERO))
        .thenReturn(new BigDecimal("30.00"));
    var overview = TenantScope.callCompany(TENANT, () -> service.overview(TENANT, KOL));
    assertThat(overview.income().available()).isEqualTo("30.00");
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

  @Test
  void shouldListEveryWithdrawalStatusWithRealStatusAndNoReceiptTimeUntilPaid() {
    Instant base = Instant.parse("2026-10-25T00:00:00Z");
    when(withdrawals.listKolWithdrawals(KOL))
        .thenReturn(
            List.of(
                withdrawal(5L, "PAID", base.plusSeconds(500), base.plusSeconds(900)),
                withdrawal(4L, "WITHDRAWN", base.plusSeconds(400), null),
                withdrawal(3L, "REJECTED", base.plusSeconds(300), null),
                withdrawal(2L, "PENDING_PAYMENT", base.plusSeconds(200), null),
                withdrawal(1L, "PENDING_REVIEW", base.plusSeconds(100), null)));

    PageResult<KolCommissionService.FundRecord> page =
        TenantScope.callCompany(TENANT, () -> service.fundRecords(TENANT, KOL, 1, 20));

    assertThat(page.total()).isEqualTo(5);
    assertThat(page.items())
        .extracting(KolCommissionService.FundRecord::publicStatus)
        .containsExactly("PAID", "WITHDRAWN", "REJECTED", "PENDING_PAYMENT", "PENDING_REVIEW");
    assertThat(page.items())
        .extracting(KolCommissionService.FundRecord::receivedAt)
        .containsExactly(base.plusSeconds(900), null, null, null, null);
    assertThat(page.items()).allMatch(row -> "COMMISSION".equals(row.type()));
    assertThat(page.items().getFirst().maskedRecipient()).isEqualTo("pa***@example.test");
  }

  @Test
  void shouldMergeWithdrawalsAndPaidPrepaysNewestApplicationFirstAndPageThem() {
    // Application times: prepay 10-24 08:00, withdrawal 10-25 00:01, withdrawal 10-23 00:00.
    when(withdrawals.listKolWithdrawals(KOL))
        .thenReturn(
            List.of(
                withdrawal(8L, "PENDING_REVIEW", Instant.parse("2026-10-25T00:01:00Z"), null),
                withdrawal(7L, "REJECTED", Instant.parse("2026-10-23T00:00:00Z"), null)));
    when(finance.prepayPayments(eq(List.of(KOL)), any(), any()))
        .thenReturn(List.of(payment(2L, "300.00", Instant.parse("2026-10-24T09:00:00Z"))));

    var first = TenantScope.callCompany(TENANT, () -> service.fundRecords(TENANT, KOL, 1, 2));
    var second = TenantScope.callCompany(TENANT, () -> service.fundRecords(TENANT, KOL, 2, 2));

    assertThat(first.total()).isEqualTo(3);
    assertThat(first.items())
        .extracting(KolCommissionService.FundRecord::type)
        .containsExactly("COMMISSION", "PREPAY");
    assertThat(second.items())
        .extracting(KolCommissionService.FundRecord::sourceId)
        .containsExactly(7L);
    // The prepay row comes only from paid payments; there is no unpaid prepay source to leak.
    verify(finance, org.mockito.Mockito.times(2)).prepayPayments(eq(List.of(KOL)), any(), any());
  }

  @Test
  void shouldAskTheMapperForTheSignedInCreatorOnly() {
    TenantScope.callCompany(TENANT, () -> service.fundRecords(TENANT, KOL, 1, 20));

    verify(withdrawals).listKolWithdrawals(KOL);
    verify(finance).prepayPayments(eq(List.of(KOL)), any(), any());
  }

  private static WithdrawalMapper.KolWithdrawalFundRow withdrawal(
      long id, String status, Instant submittedAt, Instant paidAt) {
    return new WithdrawalMapper.KolWithdrawalFundRow(
        id,
        "WD-" + id,
        new BigDecimal("200.00"),
        "pa***@example.test",
        status,
        submittedAt,
        paidAt);
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
