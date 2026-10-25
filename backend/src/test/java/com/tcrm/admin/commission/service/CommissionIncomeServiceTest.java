package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.commission.service.CommissionIncomeService.CommissionLine;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomeDetail;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomeFilter;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomePage;
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
  private final KolApi kols = mock(KolApi.class);
  private final OrderSettlementApi orderSettlement = mock(OrderSettlementApi.class);
  private final ProductAccessApi products = mock(ProductAccessApi.class);
  private final AccountNameApi accountNames = mock(AccountNameApi.class);
  private final CommissionIncomeService service =
      new CommissionIncomeService(
          states, revisions, finance, kols, orderSettlement, products, accountNames);

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
    lenient().when(finance.prepayPaidSums(anyCollection(), any(), any())).thenReturn(List.of());
    lenient().when(finance.prepayPayments(anyCollection(), any(), any())).thenReturn(List.of());
    lenient()
        .when(orderSettlement.aggregateKolOrders(anyCollection(), any(), any()))
        .thenReturn(List.of());
    lenient().when(orderSettlement.listOrdersByIds(anyCollection())).thenReturn(List.of());
    lenient().when(orderSettlement.listItemsByOrderIds(anyCollection())).thenReturn(List.of());
    lenient().when(products.listProducts(anyCollection())).thenReturn(List.of());
    lenient().when(products.listRates(anyCollection())).thenReturn(List.of());
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
    when(finance.prepayPaidSums(eq(List.of(KOL)), any(), any()))
        .thenReturn(
            List.of(new PrepayPaidRow(KOL, new BigDecimal("300.00"), PAY.plusSeconds(3600))));
    when(orderSettlement.aggregateKolOrders(eq(List.of(KOL)), any(), any()))
        .thenReturn(List.of(new OrderSettlementApi.KolOrderRowDTO(KOL, 4, 9)));
    when(accountNames.listByIds(anyCollection()))
        .thenReturn(List.of(new AccountNameDTO(9L, "BD One")));

    IncomePage page =
        TenantScope.callCompany(TENANT, () -> service.list(superAdmin(), filter(null, null)));
    var row = page.page().items().getFirst();
    // Hand calc: estimated 500, frozen 200, available 120 − 0 occupied, cumulative 20 + 300 = 320.
    assertThat(row.money().estimated()).isEqualByComparingTo("500.00");
    assertThat(row.money().frozen()).isEqualByComparingTo("200.00");
    assertThat(row.money().available()).isEqualByComparingTo("120.00");
    assertThat(row.money().cumulativePaid()).isEqualByComparingTo("320.00");
    assertThat(row.money().deducted()).isEqualByComparingTo("50.00");
    assertThat(row.money().pendingDeduction()).isEqualByComparingTo("150.00");
    assertThat(row.money().prepayPaid()).isEqualByComparingTo("300.00");
    assertThat(row.money().commissionPaid()).isEqualByComparingTo("20.00");
    assertThat(row.orderCount()).isEqualTo(4);
    assertThat(row.quantity()).isEqualTo(9);
    assertThat(row.bd()).isEqualTo("BD One");
    assertThat(row.unverified()).isEqualTo(3);
    assertThat(page.summary().paid()).isEqualByComparingTo("300.00");
    assertThat(page.summary().unverified()).isEqualTo(3);
  }

  @Test
  void shouldHidePaymentsWhenPaymentTypeIsCommissionOnly() {
    when(finance.prepayPayments(anyCollection(), any(), any()))
        .thenReturn(List.of(prepayPayment()));
    IncomeDetail detail =
        TenantScope.callCompany(
            TENANT, () -> service.detail(superAdmin(), KOL, filter(null, "COMMISSION")));
    assertThat(detail.payments()).isEmpty();
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
        .thenReturn(List.of(new OrderRefDTO(5001L, "WD-1", 30L, PAY, "PAID")));
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
    assertThat(line.image()).isEqualTo("https://img");
    assertThat(line.rateVersion()).isEqualTo(3);
    assertThat(detail.income().kolId()).isEqualTo(KOL);
    assertThat(detail.income().name()).isEqualTo("Kol A");
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
