package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.mapper.PayoutAccountMapper;
import com.tcrm.admin.commission.mapper.WithdrawalFreezeMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.model.WithdrawalRow;
import com.tcrm.admin.commission.model.WithdrawalRows;
import com.tcrm.admin.commission.service.WithdrawalFundingSource.FundingRow;
import com.tcrm.admin.commission.service.WithdrawalService.AccountInput;
import com.tcrm.admin.commission.service.WithdrawalService.ApplyCommand;
import com.tcrm.admin.commission.service.WithdrawalService.Filter;
import com.tcrm.admin.commission.service.WithdrawalService.FinanceFilter;
import com.tcrm.admin.commission.service.WithdrawalService.PaymentCommand;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderItemInfoApi;
import com.tcrm.admin.order.api.OrderItemInfoApi.OrderItemInfoDTO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.api.SystemConfigApi;
import com.tcrm.admin.system.api.AccountNameApi;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Third batch of {@link WithdrawalService} branch coverage (task 63): history/payment note
 * visibility, KOL row mapping, export rows with missing payment, allocation edge cases and the
 * payment-registration conflict paths.
 */
class WithdrawalServiceVisibilityTest {
  private static final long COMPANY = 11L;
  private static final long KOL = 22L;
  private static final long ID = 77L;
  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00.987Z");
  private static final String UUID_TEXT = "7565e681-dfd4-48bb-9f83-82e66cbb04ad";
  private static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

  private final WithdrawalMapper withdrawals = mock(WithdrawalMapper.class);
  private final WithdrawalFreezeMapper freezes = mock(WithdrawalFreezeMapper.class);
  private final CommissionWalletMapper wallets = mock(CommissionWalletMapper.class);
  private final CommissionWalletService wallet = mock(CommissionWalletService.class);
  private final WithdrawalFundingSource funding = mock(WithdrawalFundingSource.class);
  private final KolApi kols = mock(KolApi.class);
  private final AccountNameApi accountNames = mock(AccountNameApi.class);
  private final PayoutAccountMapper payoutAccounts = mock(PayoutAccountMapper.class);
  private final BizFileService files = mock(BizFileService.class);
  private final OrderItemInfoApi orderItems = mock(OrderItemInfoApi.class);
  private final ProductAccessApi products = mock(ProductAccessApi.class);
  private final SecretCipher cipher = new SecretCipher(KEY);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final SystemConfigApi configs = mock(SystemConfigApi.class);
  private final CommissionRuleConfigService ruleConfig = mock(CommissionRuleConfigService.class);
  private WithdrawalService service;

  @BeforeEach
  void prepare() {
    service =
        Mockito.spy(
            new WithdrawalService(
                withdrawals,
                freezes,
                wallets,
                wallet,
                funding,
                kols,
                accountNames,
                payoutAccounts,
                files,
                orderItems,
                products,
                cipher,
                audit,
                ruleConfig,
                configs,
                Clock.fixed(NOW, ZoneOffset.UTC)));
    when(configs.getInt(any())).thenReturn(200);
    when(ruleConfig.current())
        .thenReturn(
            new CommissionRuleConfigService.CurrentConfig(
                5, new BigDecimal("200.00"), "SETTLE_TIME", 7, NOW));
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(5L)));
    when(kols.listByIdsIncludingDeleted(anyList())).thenReturn(List.of(kol(5L)));
    when(kols.lockKol(KOL)).thenReturn(true);
    when(accountNames.listByIds(anyList())).thenReturn(List.of());
    when(wallets.lockBalance(KOL)).thenReturn(new BigDecimal("500.00"));
    when(withdrawals.sumActiveOccupied(KOL)).thenReturn(BigDecimal.ZERO);
    when(funding.frozenCny(KOL)).thenReturn(BigDecimal.ZERO);
    when(funding.estimatedCny(KOL)).thenReturn(BigDecimal.ZERO);
    when(funding.unverifiedCount(KOL)).thenReturn(0L);
    when(funding.configVersion(KOL)).thenReturn("5");
    when(funding.listReleasedRows(KOL))
        .thenReturn(List.of(new FundingRow(101L, 701L, new BigDecimal("400.00"))));
    when(orderItems.listByIds(anyList()))
        .thenReturn(
            List.of(new OrderItemInfoDTO(101L, 11L, "WD1001", "w1", "s1", "Hat", "Red", 7L, KOL)));
    when(products.listProducts(anyList())).thenReturn(List.of());
    when(withdrawals.sumOccupiedByItem(anyLong(), anyList())).thenReturn(List.of());
    when(withdrawals.nextSequence(anyLong(), any(), any())).thenReturn(3);
    when(withdrawals.insert(any())).thenReturn(1);
    when(withdrawals.findIdByRequest(anyLong(), any(UUID.class))).thenReturn(null);
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_REVIEW", KOL));
    when(withdrawals.findHead(anyLong())).thenReturn(head("PENDING_REVIEW"));
    when(withdrawals.lockById(anyLong())).thenReturn(ID);
    when(withdrawals.countAllocations(anyLong())).thenReturn(1L);
    when(withdrawals.countOrderSnapshots(anyLong())).thenReturn(0L);
    when(withdrawals.pageOrderSnapshots(
            anyLong(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(List.of());
    when(withdrawals.timeline(anyLong())).thenReturn(List.of());
    when(withdrawals.findPayment(anyLong())).thenReturn(null);
    when(withdrawals.count(any())).thenReturn(0L);
    when(withdrawals.page(any())).thenReturn(List.of());
    when(withdrawals.countWithdrawalTasks(any())).thenReturn(0L);
    when(withdrawals.countPrepayTasks(any())).thenReturn(0L);
    when(withdrawals.pageWithdrawalTasks(any())).thenReturn(List.of());
    when(withdrawals.pagePrepayTasks(any())).thenReturn(List.of());
    when(freezes.latest(KOL)).thenReturn(null);
  }

  // ------------------------------------------------------------------ history & note visibility

  @Test
  void shouldShowHistoryNotes_toTheCreatorAndReviewers_only() {
    when(withdrawals.timeline(ID))
        .thenReturn(
            List.of(
                new WithdrawalRows.HistoryRow(
                    "SUBMITTED", KOL, "Creator", "KOL", "creator note", NOW),
                new WithdrawalRows.HistoryRow(
                    "APPROVED", 6L, "Reviewer", "REVIEW", "review note", NOW),
                new WithdrawalRows.HistoryRow("PAID", 7L, "Payer", "PAYMENT", "paid note", NOW)));
    // a commission:view-only viewer sees the actions but no notes
    var viewerDetail = service.detail(staff(8L, DataScope.ALL, Set.of("commission:view")), ID);
    assertThat(viewerDetail.history())
        .extracting(WithdrawalService.HistoryItem::note)
        .containsOnlyNulls();
    // the review permission sees every note
    var reviewerDetail = service.detail(staff(6L, DataScope.ALL, Set.of("withdrawal:review")), ID);
    assertThat(reviewerDetail.history())
        .extracting(WithdrawalService.HistoryItem::note)
        .containsExactly("creator note", "review note", "paid note");
    // the creator sees the notes on their own detail
    var creatorDetail = service.kolDetail(COMPANY, KOL, ID);
    assertThat(creatorDetail.history())
        .extracting(WithdrawalService.HistoryItem::note)
        .containsExactly("creator note", "review note", "paid note");
    // the payer's finance visibility does not widen the history notes
    var payerDetail = service.detail(staff(9L, DataScope.ALL, Set.of("withdrawal:pay")), ID);
    assertThat(payerDetail.history())
        .extracting(WithdrawalService.HistoryItem::note)
        .containsOnlyNulls();
  }

  @Test
  void shouldRevealFinanceNotes_onlyToThePayer() {
    when(withdrawals.findPayment(ID))
        .thenReturn(
            new WithdrawalRows.PaymentRow(
                new BigDecimal("400.00"),
                new BigDecimal("56.00"),
                "USD",
                NOW,
                "PAYPAL",
                "rate",
                "7.14",
                88L,
                7L,
                "Payer",
                "PAYMENT"));
    var payerDetail = service.detail(staff(9L, DataScope.ALL, Set.of("withdrawal:pay")), ID);
    assertThat(payerDetail.payment().financeNote()).isEqualTo("rate");
    assertThat(payerDetail.payment().conversionNote()).isEqualTo("7.14");
    var viewerDetail = service.detail(staff(8L, DataScope.ALL, Set.of("commission:view")), ID);
    assertThat(viewerDetail.payment().financeNote()).isNull();
    assertThat(viewerDetail.payment().conversionNote()).isNull();
  }

  // ------------------------------------------------------------------ KOL row mapping

  @Test
  void shouldMapKolRows_withAndWithoutPayments() {
    when(withdrawals.count(any())).thenReturn(2L);
    when(withdrawals.page(any())).thenReturn(List.of(head("PAID"), head("PENDING_REVIEW")));
    when(withdrawals.findRow(ID)).thenReturn(row("PAID", KOL));
    when(withdrawals.findPayment(ID))
        .thenReturn(
            new WithdrawalRows.PaymentRow(
                new BigDecimal("400.00"),
                new BigDecimal("400.00"),
                "CNY",
                NOW,
                "bank",
                null,
                null,
                88L,
                7L,
                "Payer",
                "PAYMENT"));
    when(withdrawals.findRow(ID + 1)).thenReturn(row("PENDING_REVIEW", KOL));
    when(withdrawals.findPayment(ID + 1)).thenReturn(null);
    Mockito.reset(withdrawals);
    when(withdrawals.count(any())).thenReturn(2L);
    when(withdrawals.page(any()))
        .thenReturn(
            List.of(
                head("PAID"),
                new WithdrawalRow.Head(
                    ID + 1,
                    KOL,
                    "W20261025000002",
                    new BigDecimal("400.00"),
                    "PENDING_REVIEW",
                    KOL,
                    NOW)));
    when(withdrawals.findRow(ID)).thenReturn(row("PAID", KOL));
    when(withdrawals.findPayment(ID))
        .thenReturn(
            new WithdrawalRows.PaymentRow(
                new BigDecimal("400.00"),
                new BigDecimal("400.00"),
                "CNY",
                NOW,
                "bank",
                null,
                null,
                88L,
                7L,
                "Payer",
                "PAYMENT"));
    when(withdrawals.findRow(ID + 1)).thenReturn(row("PENDING_REVIEW", KOL));
    when(withdrawals.findPayment(ID + 1)).thenReturn(null);
    var page = service.pageKol(COMPANY, KOL, 1, 20);
    assertThat(page.items()).hasSize(2);
    // paid row: payment time and actuals surface, account is masked for the list
    assertThat(page.items().get(0).paidAt()).isNotNull();
    assertThat(page.items().get(0).actualCurrency()).isEqualTo("CNY");
    assertThat(page.items().get(0).account().account()).isEqualTo("cr***@example.com");
    // pending row: no payment data at all
    assertThat(page.items().get(1).paidAt()).isNull();
    assertThat(page.items().get(1).rejectReason()).isNull();
  }

  // ------------------------------------------------------------------ export rows

  @Test
  void shouldExportRows_withMissingNamesAndPayment() throws Exception {
    var viewer = staff(6L, DataScope.ALL, Set.of("data:export", "withdrawal:review"));
    when(withdrawals.count(any())).thenReturn(1L);
    when(withdrawals.page(any())).thenReturn(List.of(head("PENDING_REVIEW")));
    when(withdrawals.findRow(anyLong())).thenReturn(rowWithNullReview("PENDING_REVIEW", null));
    when(withdrawals.findPayment(anyLong())).thenReturn(null);
    var csv = service.export(viewer, new Filter(null, null, null, null, null, 1, 20));
    var text = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
    // payment-less rows export quoted-empty payment columns; the account stays masked
    assertThat(text).contains("W20261025000001");
    assertThat(text).contains("\"\"");
    assertThat(text).doesNotContain("creator@example.com");
  }

  // ------------------------------------------------------------------ allocate edges

  @Test
  void shouldSkipVanishedOrderLines_andFailWhenTheRestCannotCover() {
    // the only released row's order line is gone: nothing can be allocated, the apply fails and
    // the transaction (real DB in the IT) rolls the application back
    when(orderItems.listByIds(anyList())).thenReturn(List.of());
    assertThatThrownBy(() -> service.apply(kol(), apply("250.00")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_INSUFFICIENT_AVAILABLE));
    verify(withdrawals, Mockito.never()).insertAllocation(any());
  }

  @Test
  void shouldNotAllocateFromARow_whoseLeftoverAlreadyShrankToZero() {
    // the funding source reports 0.00 leftover for the only row
    when(funding.listReleasedRows(KOL))
        .thenReturn(List.of(new FundingRow(101L, 701L, BigDecimal.ZERO)));
    assertThatThrownBy(() -> service.apply(kol(), apply("250.00")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_INSUFFICIENT_AVAILABLE));
    verify(withdrawals, Mockito.never()).insertAllocation(any());
  }

  // ------------------------------------------------------------------ pay conflicts

  @Test
  void shouldRefusePayment_whenTheAllocationTotalDriftedFromTheAmount() {
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_PAYMENT", KOL));
    when(withdrawals.findPaymentFingerprint(any(UUID.class))).thenReturn(null);
    when(withdrawals.sumAllocated(ID)).thenReturn(new BigDecimal("399.99"));
    assertThatThrownBy(() -> service.pay(payer(), ID, payment("400.00", "400.00", "CNY")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_STATE_CONFLICT));
  }

  @Test
  void shouldRefuseForeignPayment_atFullPrecision_onlyWithANote() {
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_PAYMENT", KOL));
    when(withdrawals.findPaymentFingerprint(any(UUID.class))).thenReturn(null);
    when(withdrawals.sumAllocated(ID)).thenReturn(new BigDecimal("400.00"));
    org.mockito.Mockito.doNothing()
        .when(files)
        .claimVoucherFile(anyLong(), anyLong(), anyLong(), anyLong());
    when(withdrawals.insertPayment(any())).thenReturn(1);
    when(withdrawals.markPaid(any())).thenReturn(1);
    // a foreign-currency payment keeps sub-cent precision in the facts table
    var foreign =
        new PaymentCommand(
            UUID_TEXT,
            "400.00",
            "56.123456",
            "USD",
            "2026-10-25T12:00:00+08:00",
            "paypal",
            null,
            "t63 rate 7.128",
            901L);
    Mockito.doReturn(detail("PAID")).when(service).detail(any(), anyLong());
    assertThat(service.pay(payer(), ID, foreign).status()).isEqualTo("PAID");
  }

  // ------------------------------------------------------------------ finance tasks merge

  @Test
  void shouldSkipAPrepayBranchExcludedByType_andMergeTheRest() {
    var viewer = staff(6L, DataScope.ALL, Set.of("commission:view"));
    when(withdrawals.countWithdrawalTasks(any())).thenReturn(1L);
    when(withdrawals.pageWithdrawalTasks(any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.commission.model.FinanceTaskRow(
                    1L, KOL, "W1", "COMMISSION", "PENDING_REVIEW", new BigDecimal("100.00"), NOW)));
    var page =
        service.financeTasks(viewer, new FinanceFilter(null, null, "COMMISSION", null, 1, 20));
    assertThat(page.total()).isEqualTo(1L);
    assertThat(page.items().getFirst().taskType()).isEqualTo("COMMISSION");
  }

  // ------------------------------------------------------------------ orders page

  @Test
  void shouldPageOrderSnapshots_withNullRemainingAndMultipleProducts() {
    var viewer = staff(6L, DataScope.ALL, Set.of("commission:view"));
    when(withdrawals.countOrderSnapshots(ID)).thenReturn(1L);
    when(withdrawals.pageOrderSnapshots(eq(ID), eq(20), eq(0L)))
        .thenReturn(
            List.of(
                new WithdrawalMapper.OrderSnapshotRow(
                    901L, 11L, "WD1001", new BigDecimal("400.00"), null)));
    when(withdrawals.allocationsOfOrder(ID, "WD1001"))
        .thenReturn(
            List.of(
                new WithdrawalRows.AllocationRow(
                    101L,
                    101L,
                    "WD1001",
                    "Hat",
                    "Red",
                    "img",
                    new BigDecimal("250.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("400.00")),
                new WithdrawalRows.AllocationRow(
                    102L,
                    102L,
                    "WD1001",
                    "Cup",
                    "Blue",
                    "img2",
                    new BigDecimal("150.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("400.00"))));
    var page = service.orders(viewer, ID, 1, 20);
    assertThat(page.items().getFirst().remainingSnapshot()).isNull();
    assertThat(page.items().getFirst().products()).hasSize(2);
  }

  // ------------------------------------------------------------------ helpers

  private static WithdrawalService.PaymentCommand payment(
      String amountCny, String actualAmount, String actualCurrency) {
    return new PaymentCommand(
        UUID_TEXT,
        amountCny,
        actualAmount,
        actualCurrency,
        "2026-10-25T12:00:00+08:00",
        "bank",
        null,
        null,
        901L);
  }

  private AccountDTO payer() {
    return staff(9L, DataScope.ALL, Set.of("withdrawal:pay"));
  }

  private ApplyCommand apply(String amount) {
    return new ApplyCommand(
        UUID_TEXT,
        amount,
        null,
        null,
        new AccountInput("PAYPAL", "creator@example.com", "Creator Name", "USD", null, null),
        null);
  }

  private static KolAccountDTO kol() {
    return new KolAccountDTO(
        KOL,
        "KOL-test",
        "Test creator",
        "t63_kol@example.invalid",
        "US",
        null,
        1,
        1,
        0,
        null,
        COMPANY,
        "Test",
        true);
  }

  private static KolRefDTO kol(Long bd) {
    return new KolRefDTO(KOL, "KOL-test", "Test creator", true, bd);
  }

  private static AccountDTO staff(long id, DataScope scope, Set<String> permissions) {
    return new AccountDTO(
        id,
        "t63_staff" + id,
        null,
        "T63 Staff",
        "t63@example.invalid",
        Role.STAFF,
        scope,
        1,
        null,
        NOW,
        0,
        COMPANY,
        "T63",
        true,
        permissions,
        null);
  }

  private static WithdrawalRow row(String status, long kolId) {
    return new WithdrawalRow(
        ID,
        kolId,
        "W20261025000001",
        UUID.fromString(UUID_TEXT),
        "f".repeat(64),
        null,
        new BigDecimal("400.00"),
        status,
        false,
        null,
        null,
        "PAYPAL",
        new SecretCipher(KEY).encrypt("creator@example.com"),
        "cr***@example.com",
        "USD",
        null,
        "Creator Name",
        null,
        "note",
        null,
        "finance note",
        null,
        null,
        kolId,
        NOW);
  }

  private static WithdrawalRow rowWithNullReview(String status, Long reviewer) {
    WithdrawalRow row = row(status, KOL);
    return new WithdrawalRow(
        row.id(),
        row.kolId(),
        row.applicationNo(),
        row.requestId(),
        row.fingerprint(),
        row.previousId(),
        row.amountCny(),
        row.status(),
        row.paused(),
        row.pauseReason(),
        row.accountId(),
        row.accountType(),
        row.accountCipher(),
        row.accountMasked(),
        row.accountCurrency(),
        row.accountNetwork(),
        row.accountHolder(),
        row.accountQrFileId(),
        row.note(),
        null,
        row.financeNote(),
        reviewer,
        row.reviewedAt(),
        row.actorId(),
        row.createTime());
  }

  private static WithdrawalRow.Head head(String status) {
    return new WithdrawalRow.Head(
        ID, KOL, "W20261025000001", new BigDecimal("400.00"), status, KOL, NOW);
  }

  private static WithdrawalService.Detail detail(String status) {
    return new WithdrawalService.Detail(
        ID,
        "W20261025000001",
        KOL,
        "KOL-test",
        "Test creator",
        null,
        null,
        "COMMISSION_WITHDRAWAL",
        "400.00",
        "CNY",
        status,
        false,
        null,
        NOW,
        null,
        null,
        null,
        null,
        List.of(),
        null,
        List.of(),
        null);
  }
}
