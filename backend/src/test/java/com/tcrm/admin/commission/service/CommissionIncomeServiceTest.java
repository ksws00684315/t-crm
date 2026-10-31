package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.KolMoneyRow;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.PrepayPaidRow;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.PrepayPaymentRow;
import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper.KolAccrualRow;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.KolAmountRow;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.PaidPaymentRow;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.PaidWindowRow;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.commission.service.CommissionIncomeService.CommissionLine;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomeDetail;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomeFilter;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomePage;
import com.tcrm.admin.commission.service.CommissionIncomeService.PaymentLine;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderSettlementApi;
import com.tcrm.admin.order.api.OrderSettlementApi.ItemRefDTO;
import com.tcrm.admin.order.api.OrderSettlementApi.OrderRefDTO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ProductAccessApi.ProductRefDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.AccountNameApi;
import com.tcrm.admin.system.api.AccountNameDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests of the creator income list and detail (requirement 11.02). */
class CommissionIncomeServiceTest {
  private static final long TENANT = 7L;
  private static final long KOL = 77L;
  private static final Instant PAY = Instant.parse("2026-10-10T08:00:00Z");

  private final CommissionItemStateMapper states = mock(CommissionItemStateMapper.class);
  private final CommissionItemRevisionMapper revisions = mock(CommissionItemRevisionMapper.class);
  private final CommissionFinanceQueryMapper finance = mock(CommissionFinanceQueryMapper.class);
  private final WithdrawalMapper withdrawals = mock(WithdrawalMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final OrderSettlementApi orderSettlement = mock(OrderSettlementApi.class);
  private final ProductAccessApi products = mock(ProductAccessApi.class);
  private final AccountNameApi accountNames = mock(AccountNameApi.class);
  private final CommissionIncomeService service =
      new CommissionIncomeService(
          states, revisions, finance, withdrawals, kols, orderSettlement, products, accountNames);

  @BeforeEach
  void prepare() {
    lenient().when(states.aggregateByKol(anyCollection())).thenReturn(List.of());
    lenient().when(states.listByKol(anyLong())).thenReturn(List.of());
    lenient().when(states.aggregateCompany()).thenReturn(null);
    lenient().when(finance.activeKolIds()).thenReturn(List.of(KOL));
    lenient().when(finance.walletBalances(anyCollection())).thenReturn(List.of());
    lenient().when(finance.recoverySums(anyCollection())).thenReturn(List.of());
    lenient().when(finance.pendingDeductions(anyCollection())).thenReturn(List.of());
    lenient().when(finance.withdrawalPaidSums(anyCollection())).thenReturn(List.of());
    lenient()
        .when(
            finance.prepayPaidSums(
                anyCollection(), nullable(Instant.class), nullable(Instant.class)))
        .thenReturn(List.of());
    lenient()
        .when(
            finance.prepayPayments(
                anyCollection(), nullable(Instant.class), nullable(Instant.class)))
        .thenReturn(List.of());
    lenient().when(withdrawals.sumActiveOccupiedByKol(anyCollection())).thenReturn(List.of());
    lenient()
        .when(
            withdrawals.sumPaidInWindow(
                anyCollection(), nullable(Instant.class), nullable(Instant.class)))
        .thenReturn(List.of());
    lenient()
        .when(
            withdrawals.listPaidPayments(
                anyCollection(), nullable(Instant.class), nullable(Instant.class)))
        .thenReturn(List.of());
    lenient()
        .when(orderSettlement.aggregateKolOrders(anyCollection(), any(), any()))
        .thenReturn(List.of());
    lenient().when(orderSettlement.listOrdersByIds(anyCollection())).thenReturn(List.of());
    lenient().when(orderSettlement.listItemsByOrderIds(anyCollection())).thenReturn(List.of());
    lenient().when(products.listProducts(anyCollection())).thenReturn(List.of());
    lenient().when(accountNames.listByIds(anyCollection())).thenReturn(List.of());
    lenient()
        .when(kols.listByIds(anyList()))
        .thenReturn(
            List.of(
                new KolRefDTO(
                    KOL, "KOL001", "Kol A", true, 9L, "kol@t62.test", null, null, false)));
    lenient().when(kols.listByBdUserId(anyLong())).thenReturn(List.of());
    lenient().when(kols.findIdsByKeyword(any())).thenReturn(List.of());
  }

  @Test
  void shouldReturnEmptyPageForOwnScopeWithoutKols() {
    IncomePage page =
        TenantScope.callCompany(TENANT, () -> service.list(staffOwn(), filter(null, null)));
    assertThat(page.page().total()).isZero();
    assertThat(page.page().items()).isEmpty();
    assertThat(page.summary().creators()).isZero();
    assertThat(page.summary().commissionPaid()).isEqualByComparingTo("0.00");
    assertThat(page.summary().prepayPaid()).isEqualByComparingTo("0.00");
    assertThat(page.summary().paid()).isEqualByComparingTo("0.00");
  }

  @Test
  void shouldIntersectKeywordWithActiveKols() {
    when(kols.findIdsByKeyword("kol")).thenReturn(List.of(KOL, 999L));
    IncomePage page =
        TenantScope.callCompany(TENANT, () -> service.list(superAdmin(), filter("kol", null)));
    assertThat(page.page().total()).isEqualTo(1);
    assertThat(page.page().items().getFirst().kolId()).isEqualTo(KOL);
    assertThat(page.page().items().getFirst().email()).isEqualTo("kol@t62.test");
  }

  @Test
  void shouldComposeRowMoneyAndBdName() {
    when(states.aggregateByKol(List.of(KOL)))
        .thenReturn(
            List.of(
                new KolAccrualRow(KOL, new BigDecimal("500.00"), new BigDecimal("200.00"), 3L)));
    when(finance.walletBalances(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("120.00"))));
    when(finance.recoverySums(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("50.00"))));
    when(finance.pendingDeductions(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("150.00"))));
    when(finance.withdrawalPaidSums(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("20.00"))));
    when(withdrawals.sumPaidInWindow(
            eq(List.of(KOL)), nullable(Instant.class), nullable(Instant.class)))
        .thenReturn(List.of(new PaidWindowRow(KOL, new BigDecimal("20.00"), PAY)));
    when(finance.prepayPaidSums(eq(List.of(KOL)), nullable(Instant.class), nullable(Instant.class)))
        .thenReturn(
            List.of(new PrepayPaidRow(KOL, new BigDecimal("300.00"), PAY.plusSeconds(3600))));
    when(orderSettlement.aggregateKolOrders(eq(List.of(KOL)), any(), any()))
        .thenReturn(List.of(new OrderSettlementApi.KolOrderRowDTO(KOL, 4, 9)));
    when(accountNames.listByIds(anyCollection()))
        .thenReturn(List.of(new AccountNameDTO(9L, "BD One")));

    IncomePage page =
        TenantScope.callCompany(TENANT, () -> service.list(superAdmin(), filter(null, null)));
    var row = page.page().items().getFirst();
    // Hand calc: estimated 500, frozen 200, balance 120 − occupied 0, cumulative ledger 20 + prepay
    // 300 = 320. The window stub equals the ledger here; the window test covers a smaller window.
    assertThat(row.money().estimated()).isEqualByComparingTo("500.00");
    assertThat(row.money().frozen()).isEqualByComparingTo("200.00");
    assertThat(row.money().available()).isEqualByComparingTo("120.00");
    assertThat(row.money().occupied()).isEqualByComparingTo("0.00");
    assertThat(row.money().cumulativePaid()).isEqualByComparingTo("320.00");
    assertThat(row.money().deducted()).isEqualByComparingTo("50.00");
    assertThat(row.money().pendingDeduction()).isEqualByComparingTo("150.00");
    assertThat(row.money().prepayPaid()).isEqualByComparingTo("300.00");
    assertThat(row.money().commissionPaid()).isEqualByComparingTo("20.00");
    assertThat(row.money().lastPaidAt()).isEqualTo(PAY.plusSeconds(3600));
    assertThat(row.orderCount()).isEqualTo(4);
    assertThat(row.quantity()).isEqualTo(9);
    assertThat(row.bd()).isEqualTo("BD One");
    assertThat(row.unverified()).isEqualTo(3);
    // Hand calc: summary paid = commission 20 + windowed prepay 300.
    assertThat(page.summary().commissionPaid()).isEqualByComparingTo("20.00");
    assertThat(page.summary().prepayPaid()).isEqualByComparingTo("300.00");
    assertThat(page.summary().paid()).isEqualByComparingTo("320.00");
    assertThat(page.summary().unverified()).isEqualTo(3);

    IncomePage commissionOnly =
        TenantScope.callCompany(
            TENANT, () -> service.list(superAdmin(), filter(null, "COMMISSION")));
    var commissionRow = commissionOnly.page().items().getFirst().money();
    assertThat(commissionRow.commissionPaid()).isEqualByComparingTo("20.00");
    assertThat(commissionRow.prepayPaid()).isEqualByComparingTo("0.00");
    assertThat(commissionRow.periodPaid()).isEqualByComparingTo("20.00");
    assertThat(commissionRow.lastPaidAt()).isEqualTo(PAY);
    assertThat(commissionRow.cumulativePaid()).isEqualByComparingTo("320.00");
    assertThat(commissionOnly.summary().commissionPaid()).isEqualByComparingTo("20.00");
    assertThat(commissionOnly.summary().prepayPaid()).isEqualByComparingTo("0.00");
    assertThat(commissionOnly.summary().paid()).isEqualByComparingTo("20.00");

    IncomePage prepayOnly =
        TenantScope.callCompany(TENANT, () -> service.list(superAdmin(), filter(null, "PREPAY")));
    var prepayRow = prepayOnly.page().items().getFirst().money();
    assertThat(prepayRow.commissionPaid()).isEqualByComparingTo("0.00");
    assertThat(prepayRow.prepayPaid()).isEqualByComparingTo("300.00");
    assertThat(prepayRow.periodPaid()).isEqualByComparingTo("300.00");
    assertThat(prepayRow.lastPaidAt()).isEqualTo(PAY.plusSeconds(3600));
    assertThat(prepayRow.cumulativePaid()).isEqualByComparingTo("320.00");
    assertThat(prepayOnly.summary().commissionPaid()).isEqualByComparingTo("0.00");
    assertThat(prepayOnly.summary().prepayPaid()).isEqualByComparingTo("300.00");
    assertThat(prepayOnly.summary().paid()).isEqualByComparingTo("300.00");
  }

  @Test
  void shouldKeepCumulativeOutsideThePayWindowAndAllowNegativeAvailable() {
    Instant start = Instant.parse("2026-10-01T00:00:00Z");
    Instant end = Instant.parse("2026-11-01T00:00:00Z");
    Instant paidAt = Instant.parse("2026-10-10T08:00:00Z");
    Instant prepayAt = Instant.parse("2026-10-09T00:00:00Z");
    when(finance.walletBalances(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("20.00"))));
    when(withdrawals.sumActiveOccupiedByKol(List.of(KOL)))
        .thenReturn(List.of(new KolAmountRow(KOL, new BigDecimal("30.00"))));
    when(finance.withdrawalPaidSums(List.of(KOL)))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("40.00"))));
    when(finance.prepayPaidSums(eq(List.of(KOL)), isNull(), isNull()))
        .thenReturn(
            List.of(
                new PrepayPaidRow(
                    KOL, new BigDecimal("30.00"), Instant.parse("2026-09-01T00:00:00Z"))));
    when(finance.prepayPaidSums(eq(List.of(KOL)), eq(start), eq(end)))
        .thenReturn(List.of(new PrepayPaidRow(KOL, new BigDecimal("10.00"), prepayAt)));
    when(withdrawals.sumPaidInWindow(eq(List.of(KOL)), eq(start), eq(end)))
        .thenReturn(List.of(new PaidWindowRow(KOL, new BigDecimal("15.00"), paidAt)));

    IncomeFilter window = new IncomeFilter(null, null, start, end, 1, 20);
    IncomePage page = TenantScope.callCompany(TENANT, () -> service.list(superAdmin(), window));
    var money = page.page().items().getFirst().money();
    // Hand calc: balance 20 − occupied 30 = −10. Cumulative is the ledger 40 plus all prepay 30.
    // The window keeps 15 of commission and 10 of prepay.
    assertThat(money.occupied()).isEqualByComparingTo("30.00");
    assertThat(money.available()).isEqualByComparingTo("-10.00");
    assertThat(money.cumulativePaid()).isEqualByComparingTo("70.00");
    assertThat(money.commissionPaid()).isEqualByComparingTo("15.00");
    assertThat(money.prepayPaid()).isEqualByComparingTo("10.00");
    assertThat(money.periodPaid()).isEqualByComparingTo("25.00");
    assertThat(money.lastPaidAt()).isEqualTo(paidAt);
    assertThat(page.summary().paid()).isEqualByComparingTo("25.00");
    assertThat(page.summary().commissionPaid()).isEqualByComparingTo("15.00");
    assertThat(page.summary().prepayPaid()).isEqualByComparingTo("10.00");

    IncomeFilter commission = new IncomeFilter(null, "COMMISSION", start, end, 1, 20);
    IncomePage commissionPage =
        TenantScope.callCompany(TENANT, () -> service.list(superAdmin(), commission));
    var commissionMoney = commissionPage.page().items().getFirst().money();
    assertThat(commissionMoney.commissionPaid()).isEqualByComparingTo("15.00");
    assertThat(commissionMoney.prepayPaid()).isEqualByComparingTo("0.00");
    assertThat(commissionMoney.periodPaid()).isEqualByComparingTo("15.00");
    assertThat(commissionMoney.lastPaidAt()).isEqualTo(paidAt);
    assertThat(commissionMoney.cumulativePaid()).isEqualByComparingTo("70.00");
    assertThat(commissionPage.summary().paid()).isEqualByComparingTo("15.00");
    assertThat(commissionPage.summary().prepayPaid()).isEqualByComparingTo("0.00");

    IncomeFilter prepay = new IncomeFilter(null, "PREPAY", start, end, 1, 20);
    IncomePage prepayPage =
        TenantScope.callCompany(TENANT, () -> service.list(superAdmin(), prepay));
    var prepayMoney = prepayPage.page().items().getFirst().money();
    assertThat(prepayMoney.commissionPaid()).isEqualByComparingTo("0.00");
    assertThat(prepayMoney.prepayPaid()).isEqualByComparingTo("10.00");
    assertThat(prepayMoney.periodPaid()).isEqualByComparingTo("10.00");
    assertThat(prepayMoney.lastPaidAt()).isEqualTo(prepayAt);
    assertThat(prepayMoney.cumulativePaid()).isEqualByComparingTo("70.00");
    assertThat(prepayPage.summary().paid()).isEqualByComparingTo("10.00");
    assertThat(prepayPage.summary().commissionPaid()).isEqualByComparingTo("0.00");
  }

  @Test
  void shouldMergePaidWithdrawalsIntoPaymentsAndFilterByType() {
    when(finance.prepayPayments(anyCollection(), nullable(Instant.class), nullable(Instant.class)))
        .thenReturn(List.of(prepayPayment()));
    when(finance.recoverySums(anyCollection()))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("4.00"))));
    when(finance.pendingDeductions(anyCollection()))
        .thenReturn(List.of(new KolMoneyRow(KOL, new BigDecimal("6.00"))));
    when(withdrawals.listPaidPayments(
            eq(List.of(KOL)), nullable(Instant.class), nullable(Instant.class)))
        .thenReturn(
            List.of(
                withdrawalPayment(200L, new BigDecimal("15.000000")),
                withdrawalPayment(100L, new BigDecimal("15.123456"))));

    IncomeDetail both =
        TenantScope.callCompany(
            TENANT, () -> service.detail(superAdmin(), KOL, filter(null, null)));
    assertThat(both.payments()).extracting(PaymentLine::id).containsExactly(8001L, 200L, 100L);
    PaymentLine whole = both.payments().get(1);
    assertThat(whole.type()).isEqualTo("COMMISSION");
    assertThat(whole.sourceNo()).isEqualTo("W20261010000015");
    assertThat(whole.withdrawalId()).isEqualTo(9100L);
    assertThat(whole.amountCny()).isEqualTo("15.00");
    assertThat(whole.actualAmount()).isEqualTo("15.00");
    assertThat(whole.actualCurrency()).isEqualTo("USD");
    assertThat(whole.voucher()).isEqualTo(8801L);
    assertThat(whole.deduct()).isEqualByComparingTo("0");
    assertThat(whole.deducted()).isEqualByComparingTo("0.00");
    assertThat(whole.pending()).isEqualByComparingTo("0.00");
    PaymentLine precise = both.payments().get(2);
    assertThat(precise.actualAmount()).isEqualTo("15.123456");
    PaymentLine prepay = both.payments().getFirst();
    assertThat(prepay.type()).isEqualTo("PREPAY");
    assertThat(prepay.deducted()).isEqualByComparingTo("4.00");
    assertThat(prepay.pending()).isEqualByComparingTo("6.00");

    IncomeDetail commissionOnly =
        TenantScope.callCompany(
            TENANT, () -> service.detail(superAdmin(), KOL, filter(null, "COMMISSION")));
    assertThat(commissionOnly.payments())
        .extracting(PaymentLine::type)
        .containsExactly("COMMISSION", "COMMISSION");

    IncomeDetail prepayOnly =
        TenantScope.callCompany(
            TENANT, () -> service.detail(superAdmin(), KOL, filter(null, "PREPAY")));
    assertThat(prepayOnly.payments()).extracting(PaymentLine::type).containsExactly("PREPAY");
  }

  @Test
  void shouldHideLinesAndPaymentsForMissingKol() {
    when(kols.listByIds(List.of(404L))).thenReturn(List.of());
    assertThatThrownBy(
            () ->
                TenantScope.callCompany(
                    TENANT, () -> service.detail(superAdmin(), 404L, filter(null, null))))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldHideDetailFromForeignBd() {
    assertThatThrownBy(
            () ->
                TenantScope.callCompany(
                    TENANT, () -> service.detail(staffOwn(), KOL, filter(null, null))))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldBuildLinesWithDebitDirectionAndMissingParts() {
    when(states.listByKol(KOL)).thenReturn(List.of(stateRow()));
    when(orderSettlement.listOrdersByIds(anyCollection()))
        .thenReturn(List.of(new OrderRefDTO(5001L, "WD-1", 30L, PAY, "PAID", null)));
    when(orderSettlement.listItemsByOrderIds(anyCollection()))
        .thenReturn(
            List.of(
                new ItemRefDTO(
                    9001L,
                    5001L,
                    "item",
                    "sku",
                    1,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    KOL,
                    51L)));
    when(products.listProducts(anyCollection()))
        .thenReturn(List.of(new ProductRefDTO(51L, "P1", "Product", "https://img", 1)));
    CommissionItemRevisionDO revision = new CommissionItemRevisionDO();
    revision.setItemStateId(7001L);
    revision.setRate(new BigDecimal("0.1000"));
    when(revisions.listLatestByItemStateIds(anyCollection())).thenReturn(List.of(revision));

    IncomeDetail detail =
        TenantScope.callCompany(
            TENANT, () -> service.detail(superAdmin(), KOL, filter(null, null)));
    CommissionLine line = detail.lines().getFirst();
    // released 5 − accrued 10 = -5 → clawed-back difference shows as DEBIT.
    assertThat(line.direction()).isEqualTo("DEBIT");
    assertThat(line.adjustment()).isEqualTo("-5.00");
    assertThat(line.commission()).isEqualTo("10.00");
    assertThat(line.product()).isEqualTo("item");
    assertThat(line.sku()).isEqualTo("sku");
    assertThat(line.image()).isEqualTo("https://img");
    assertThat(line.rateVersion()).isEqualTo(3);
    assertThat(detail.income().kolId()).isEqualTo(KOL);
    assertThat(detail.income().name()).isEqualTo("Kol A");
  }

  @Test
  void shouldTakeNamesFromTheOrderLineAndLeaveSkuNullWhenAbsent() {
    CommissionItemStateDO named = stateRow();
    CommissionItemStateDO blankSku = stateRow();
    blankSku.setId(7002L);
    blankSku.setOrderItemId(9002L);
    CommissionItemStateDO missing = stateRow();
    missing.setId(7003L);
    missing.setOrderItemId(9003L);
    when(revisions.listLatestByItemStateIds(anyCollection())).thenReturn(List.of());
    when(states.listByKol(KOL)).thenReturn(List.of(named, blankSku, missing));
    when(orderSettlement.listOrdersByIds(anyCollection()))
        .thenReturn(List.of(new OrderRefDTO(5001L, "WD-1", 30L, PAY, "PAID", null)));
    when(orderSettlement.listItemsByOrderIds(anyCollection()))
        .thenReturn(
            List.of(
                new ItemRefDTO(
                    9001L,
                    5001L,
                    "Tee",
                    "Red / M",
                    1,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    KOL,
                    null),
                new ItemRefDTO(
                    9002L,
                    5001L,
                    "Hat",
                    "  ",
                    1,
                    new BigDecimal("40.00"),
                    BigDecimal.ZERO,
                    KOL,
                    null)));

    IncomeDetail detail =
        TenantScope.callCompany(
            TENANT, () -> service.detail(superAdmin(), KOL, filter(null, null)));
    assertThat(detail.lines()).hasSize(3);
    assertThat(detail.lines().get(0).product()).isEqualTo("Tee");
    assertThat(detail.lines().get(0).sku()).isEqualTo("Red / M");
    assertThat(detail.lines().get(1).product()).isEqualTo("Hat");
    assertThat(detail.lines().get(1).sku()).isNull();
    assertThat(detail.lines().get(2).product()).isNull();
    assertThat(detail.lines().get(2).sku()).isNull();
  }

  private static PaidPaymentRow withdrawalPayment(long paymentId, BigDecimal actual) {
    return new PaidPaymentRow(
        paymentId,
        9100L,
        "W20261010000015",
        KOL,
        new BigDecimal("15.00"),
        actual,
        "USD",
        PAY,
        8801L);
  }

  private static PrepayPaymentRow prepayPayment() {
    return new PrepayPaymentRow(
        8001L,
        7001L,
        "PP-1",
        "PREPAY",
        KOL,
        new BigDecimal("300.00"),
        PAY.plusSeconds(3600),
        "ab***@t62.test",
        55L,
        true,
        "PAID",
        PAY);
  }

  private static CommissionItemStateDO stateRow() {
    CommissionItemStateDO row = new CommissionItemStateDO();
    row.setId(7001L);
    row.setTenantId(TENANT);
    row.setOrderId(5001L);
    row.setOrderItemId(9001L);
    row.setKolId(KOL);
    row.setVerified(true);
    row.setAccruedCommissionCny(new BigDecimal("10.00"));
    row.setReleasedCommissionCny(new BigDecimal("5.00"));
    row.setReleaseBasis(CommissionSettlementRules.RELEASE_CONFIRMED);
    row.setConfigVersion(3);
    row.setBasisCny(new BigDecimal("100.00"));
    return row;
  }

  private static IncomeFilter filter(String keyword, String paymentType) {
    return new IncomeFilter(keyword, paymentType, null, null, 1, 20);
  }

  private static AccountDTO superAdmin() {
    return new AccountDTO(
        5L,
        "t62_admin",
        null,
        "Task 62",
        "t62@example.test",
        Role.SUPER_ADMIN,
        null,
        1,
        null,
        null,
        0,
        TENANT,
        "Task 62 Co",
        true,
        java.util.Set.of(),
        null);
  }

  private static AccountDTO staffOwn() {
    return new AccountDTO(
        99L,
        "t62_bd",
        null,
        "BD",
        "bd@t62.test",
        Role.STAFF,
        com.tcrm.admin.security.api.DataScope.OWN,
        1,
        null,
        null,
        0,
        TENANT,
        "Task 62 Co",
        true,
        java.util.Set.of(),
        null);
  }
}
