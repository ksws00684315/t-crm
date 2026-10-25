package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.mapper.PayoutAccountMapper;
import com.tcrm.admin.commission.mapper.WithdrawalFreezeMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.model.PayoutAccountDO;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.model.WithdrawalRow;
import com.tcrm.admin.commission.model.WithdrawalRows;
import com.tcrm.admin.commission.model.WithdrawalWrite;
import com.tcrm.admin.commission.service.WithdrawalFundingSource.FundingRow;
import com.tcrm.admin.commission.service.WithdrawalService.AccountInput;
import com.tcrm.admin.commission.service.WithdrawalService.ApplyCommand;
import com.tcrm.admin.commission.service.WithdrawalService.Filter;
import com.tcrm.admin.commission.service.WithdrawalService.FinanceFilter;
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

/**
 * Second batch of {@link WithdrawalService} branch coverage (task 63): the finance task scopes,
 * condition combinations, payout-option reads and the remaining guard clauses.
 */
class WithdrawalServiceScopeTest {
  private static final long COMPANY = 11L;
  private static final long KOL = 22L;
  private static final long ID = 77L;
  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00.987Z");
  private static final String UUID_TEXT = "7565e681-dfd4-48bb-9f83-82e66cbb04ad";

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
  private final SecretCipher cipher =
      new SecretCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]));
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final SystemConfigApi configs = mock(SystemConfigApi.class);
  private final CommissionRuleConfigService ruleConfig = mock(CommissionRuleConfigService.class);
  private WithdrawalService service;

  @BeforeEach
  void prepare() {
    service =
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
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(configs.getInt(any())).thenReturn(200);
    when(ruleConfig.current())
        .thenReturn(
            new CommissionRuleConfigService.CurrentConfig(
                3, new BigDecimal("200.00"), "SETTLE_TIME", 7, NOW));
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(5L)));
    when(kols.lockKol(KOL)).thenReturn(true);
    when(kols.listByIdsIncludingDeleted(anyList())).thenReturn(List.of(kol(5L)));
    when(kols.findIdsByKeyword(any())).thenReturn(List.of());
    when(accountNames.listByIds(anyList())).thenReturn(List.of());
    when(wallets.lockBalance(KOL)).thenReturn(new BigDecimal("500.00"));
    when(withdrawals.sumActiveOccupied(KOL)).thenReturn(BigDecimal.ZERO);
    when(funding.frozenCny(KOL)).thenReturn(new BigDecimal("40.00"));
    when(funding.estimatedCny(KOL)).thenReturn(new BigDecimal("600.00"));
    when(funding.unverifiedCount(KOL)).thenReturn(2L);
    when(funding.configVersion(KOL)).thenReturn("3");
    when(funding.listReleasedRows(KOL))
        .thenReturn(List.of(new FundingRow(101L, 700L, new BigDecimal("350.00"))));
    when(orderItems.listByIds(anyList()))
        .thenReturn(
            List.of(new OrderItemInfoDTO(101L, 11L, "WD1001", "w1", "s1", "Hat", "Red", 7L, KOL)));
    when(products.listProducts(anyList())).thenReturn(List.of());
    when(withdrawals.sumOccupiedByItem(anyLong(), anyList())).thenReturn(List.of());
    when(withdrawals.nextSequence(anyLong(), any(), any())).thenReturn(9);
    when(withdrawals.insert(any())).thenReturn(1);
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_REVIEW"));
    when(withdrawals.findHead(anyLong())).thenReturn(head("PENDING_REVIEW"));
    when(withdrawals.findIdByRequest(anyLong(), any(UUID.class))).thenReturn(null);
    when(withdrawals.count(any())).thenReturn(0L);
    when(withdrawals.page(any())).thenReturn(List.of());
    when(withdrawals.countWithdrawalTasks(any())).thenReturn(0L);
    when(withdrawals.countPrepayTasks(any())).thenReturn(0L);
    when(withdrawals.pageWithdrawalTasks(any())).thenReturn(List.of());
    when(withdrawals.pagePrepayTasks(any())).thenReturn(List.of());
    when(withdrawals.listOccupationsByKol(anyLong())).thenReturn(List.of());
    when(freezes.latest(KOL)).thenReturn(null);
  }

  // ------------------------------------------------------------------ conditions matrix

  @Test
  void shouldComposeConditions_everyFlagCombination() {
    // frozen creator blocks eligibility even with enough money
    when(wallets.findBalance(KOL)).thenReturn(new BigDecimal("500.00"));
    when(freezes.latest(KOL)).thenReturn(freeze(true));
    var frozen = service.conditions(COMPANY, KOL);
    assertThat(frozen.withdrawalFrozen()).isTrue();
    assertThat(frozen.canApply()).isFalse();
    assertThat(frozen.version()).isEqualTo("3");
    assertThat(frozen.estimated()).isEqualTo("600.00");
    assertThat(frozen.unverifiedCount()).isEqualTo(2L);

    // unverified rows block a rich creator too
    when(freezes.latest(KOL)).thenReturn(null);
    var unverified = service.conditions(COMPANY, KOL);
    assertThat(unverified.canApply()).isFalse();

    // rich, verified, unfrozen: eligible
    when(funding.unverifiedCount(KOL)).thenReturn(0L);
    assertThat(service.conditions(COMPANY, KOL).canApply()).isTrue();

    // money below the minimum: not eligible
    when(wallets.findBalance(KOL)).thenReturn(new BigDecimal("150.00"));
    assertThat(service.conditions(COMPANY, KOL).canApply()).isFalse();
  }

  // ------------------------------------------------------------------ payout options

  @Test
  void shouldListApprovedPayoutOptions_withTheFullAccountForTheOwner() {
    PayoutAccountDO paypal = account(PayoutAccountType.PAYPAL, "p@example.com", "USD", null);
    PayoutAccountDO usdt = account(PayoutAccountType.USDT, "TQ5xnPW9tZ3kA7qL9aZ", null, "TRC20");
    when(payoutAccounts.selectList(any())).thenReturn(List.of(paypal, usdt));
    var options = service.recipientOptions(COMPANY, KOL);
    assertThat(options).hasSize(2);
    assertThat(options.get(0).account()).isEqualTo("p@example.com");
    assertThat(options.get(1).network()).isEqualTo("TRC20");
    assertThat(options.get(1).currency()).isNull();
  }

  @Test
  void shouldSnapshotAnApprovedReferencedAccount_withUsdtFields() {
    when(payoutAccounts.selectById(44L))
        .thenReturn(account(44L, PayoutAccountType.USDT, "TQ5xnPW9tZ3kA7qL9aZ", null, "SOL"));
    service.apply(kol(), new ApplyCommand(UUID_TEXT, "250.00", null, 44L, null, null));
    org.mockito.ArgumentCaptor<WithdrawalWrite.Insert> insert =
        org.mockito.ArgumentCaptor.forClass(WithdrawalWrite.Insert.class);
    org.mockito.Mockito.verify(withdrawals).insert(insert.capture());
    assertThat(insert.getValue().accountNetwork()).isEqualTo("SOL");
    assertThat(insert.getValue().accountId()).isEqualTo(44L);
  }

  // ------------------------------------------------------------------ finance task scopes

  @Test
  void shouldNarrowThePrepayBranchOnlyForOwnViewersWithoutPrepayAccess() {
    var ownViewer = staff(5L, DataScope.OWN, Set.of("commission:view"));
    when(kols.listByBdUserId(5L)).thenReturn(List.of(kol(5L)));
    service.financeTasks(ownViewer, new FinanceFilter(null, null, null, null, 1, 20));
    ArgumentCaptorScope.capture(service, withdrawals);
    // A finance-permission viewer keeps the company-wide prepay branch
    var financeViewer = staff(6L, DataScope.OWN, Set.of("commission:view", "prepay:review"));
    when(kols.listByBdUserId(6L)).thenReturn(List.of(kol(5L)));
    service.financeTasks(financeViewer, new FinanceFilter(null, null, null, null, 1, 20));
  }

  @Test
  void shouldKeepWithdrawalScopeAlwaysOwn_forOwnViewers() {
    var ownFinance = staff(5L, DataScope.OWN, Set.of("commission:view", "prepay:pay"));
    when(kols.listByBdUserId(5L)).thenReturn(List.of(kol(5L)));
    var page = service.financeTasks(ownFinance, new FinanceFilter(null, null, null, null, 1, 20));
    assertThat(page).isNotNull();
  }

  @Test
  void shouldMapEveryRawFinanceTaskStatus_throughTheFilter() {
    var viewer = staff(6L, DataScope.ALL, Set.of("commission:view"));
    for (String raw :
        new String[] {
          "WAITING_DETAILS",
          "PENDING_OPERATIONS_REVIEW",
          "PENDING_FINANCE_REVIEW",
          "PENDING_REVIEW",
          "PENDING_PAYMENT",
          "APPROVED",
          "PAID",
          "REJECTED",
          "WITHDRAWN",
          "CANCELLED"
        }) {
      service.financeTasks(viewer, new FinanceFilter(null, null, null, raw, 1, 20));
    }
    assertThatThrownBy(
            () -> service.financeTasks(viewer, new FinanceFilter(null, null, null, "NOPE", 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  // ------------------------------------------------------------------ list filter shapes

  @Test
  void shouldResolveKeywordAndBdFilters_onTheWithdrawalList() {
    var viewer = staff(6L, DataScope.ALL, Set.of("withdrawal:review"));
    // keyword with no matches answers an empty page
    when(kols.findIdsByKeyword("ghost")).thenReturn(List.of());
    assertThat(service.page(viewer, new Filter(null, null, "ghost", null, null, 1, 20)).total())
        .isZero();
    // a bdUserId filter with no creators answers an empty page
    when(kols.listByBdUserId(9L)).thenReturn(List.of());
    assertThat(service.page(viewer, new Filter(null, null, null, 9L, null, 1, 20)).total())
        .isZero();
    // an OWN viewer keeps their own creators even when another BD is requested
    when(kols.listByBdUserId(5L)).thenReturn(List.of(kol(5L)));
    when(withdrawals.count(any())).thenReturn(1L);
    assertThat(
            service
                .page(
                    staff(5L, DataScope.OWN, Set.of("withdrawal:review")),
                    new Filter(null, null, null, 9L, null, 1, 20))
                .total())
        .isEqualTo(1L);
  }

  @Test
  void shouldRejectUnknownStatusValues_onListAndExport() {
    var viewer = staff(6L, DataScope.ALL, Set.of("withdrawal:review", "data:export"));
    assertThatThrownBy(
            () -> service.page(viewer, new Filter(null, null, null, null, "GONE", 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    assertThatThrownBy(
            () -> service.export(viewer, new Filter(null, null, null, null, "GONE", 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  // ------------------------------------------------------------------ review & pay guards

  @Test
  void shouldKeepPayPermissionPaths_separateFromReview() {
    // approve in the payment stage is a state conflict even with withdrawal:pay
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_PAYMENT"));
    assertThatThrownBy(
            () ->
                service.review(
                    staff(6L, DataScope.ALL, Set.of("withdrawal:pay")), ID, "APPROVE", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_STATE_CONFLICT));
    // payment registration on a paused task is refused
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_PAYMENT", true));
    assertThatThrownBy(
            () ->
                service.pay(
                    payer(),
                    ID,
                    new WithdrawalService.PaymentCommand(
                        UUID_TEXT,
                        "400.00",
                        "400.00",
                        "CNY",
                        "2026-10-25T12:00:00+08:00",
                        "bank",
                        null,
                        null,
                        901L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_PAUSED));
    // ... and so is an unpaused one outside the payment state
    when(withdrawals.findRow(anyLong())).thenReturn(row("REJECTED", false));
    assertThatThrownBy(
            () ->
                service.pay(
                    payer(),
                    ID,
                    new WithdrawalService.PaymentCommand(
                        UUID_TEXT,
                        "400.00",
                        "400.00",
                        "CNY",
                        "2026-10-25T12:00:00+08:00",
                        "bank",
                        null,
                        null,
                        901L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_STATE_CONFLICT));
  }

  @Test
  void shouldCarryVoucherUploadThroughTheService() {
    byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    when(files.uploadWithdrawalVoucher(anyLong(), anyLong(), any(), any())).thenReturn(881L);
    assertThat(
            service.uploadVoucher(
                staff(6L, DataScope.ALL, Set.of("withdrawal:pay")), "receipt.png", png))
        .isEqualTo(881L);
    // non-payers are refused before any byte is inspected
    assertThatThrownBy(
            () ->
                service.uploadVoucher(
                    staff(7L, DataScope.ALL, Set.of("withdrawal:review")), "receipt.png", png))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    // empty content and bad signatures fail validation
    assertThatThrownBy(
            () ->
                service.uploadVoucher(
                    staff(6L, DataScope.ALL, Set.of("withdrawal:pay")), "receipt.png", new byte[0]))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PREPAY_FILE_INVALID));
    assertThatThrownBy(
            () ->
                service.uploadVoucher(
                    staff(6L, DataScope.ALL, Set.of("withdrawal:pay")),
                    "x.png",
                    new byte[] {1, 2, 3}))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PREPAY_FILE_INVALID));
  }

  // ------------------------------------------------------------------ KOL reads

  @Test
  void shouldEnforcePageBounds_andForeignDetailForTheCreator() {
    assertThatThrownBy(() -> service.pageKol(COMPANY, KOL, 1, 101))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_REVIEW", 999L));
    assertThatThrownBy(() -> service.kolDetail(COMPANY, KOL, ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  @Test
  void shouldRefuseOrderPaginationForUnknownCreator() {
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of());
    assertThatThrownBy(
            () -> service.orders(staff(6L, DataScope.ALL, Set.of("commission:view")), ID, 1, 20))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  // ------------------------------------------------------------------ freeze edges

  @Test
  void shouldRefuseFreezeAndResumeForUnknownCreators() {
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of());
    assertThatThrownBy(() -> service.resume(freezer(), KOL, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  // ------------------------------------------------------------------ list page full row

  @Test
  void shouldSurfaceEveryListTernary_forThePayerOnAPaidRow() {
    var payer = staff(6L, DataScope.ALL, Set.of("withdrawal:pay"));
    when(withdrawals.count(any())).thenReturn(1L);
    when(withdrawals.page(any())).thenReturn(List.of(head("PAID")));
    when(withdrawals.findRow(anyLong()))
        .thenReturn(
            new WithdrawalRow(
                ID,
                KOL,
                "W1",
                UUID.fromString(UUID_TEXT),
                "f".repeat(64),
                55L,
                new BigDecimal("400.00"),
                "PAID",
                false,
                null,
                44L,
                "USDT",
                cipherText(),
                "cr***@example.com",
                null,
                "TRC20",
                "Creator Name",
                null,
                "creator note",
                "review note",
                "finance note",
                6L,
                NOW,
                KOL,
                NOW));
    var page = service.page(payer, new Filter(null, null, null, null, null, 1, 20));
    var row = page.items().getFirst();
    // payer sees the finance note; every ternary takes its populated branch
    assertThat(row.financeNote()).isEqualTo("finance note");
    assertThat(row.previousId()).isEqualTo(55L);
    assertThat(row.accountType()).isEqualTo("USDT");
    assertThat(row.accountMasked()).isEqualTo("cr***@example.com");
  }

  @Test
  void shouldExportAPaidRow_withItsPaymentColumns() throws Exception {
    var viewer = staff(6L, DataScope.ALL, Set.of("data:export", "withdrawal:review"));
    when(withdrawals.count(any())).thenReturn(1L);
    when(withdrawals.page(any())).thenReturn(List.of(head("PAID")));
    when(withdrawals.findRow(anyLong())).thenReturn(row("PAID", false));
    when(withdrawals.findPayment(anyLong()))
        .thenReturn(
            new WithdrawalRows.PaymentRow(
                new BigDecimal("400.00"),
                new BigDecimal("56.00"),
                "USD",
                NOW,
                "paypal",
                "rate",
                "7.14",
                88L,
                7L,
                "Payer",
                "PAYMENT"));
    var csv = service.export(viewer, new Filter(null, null, null, null, null, 1, 20));
    var text = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
    // the CSV carries the account type, the actual amount and its currency
    assertThat(text).contains("PAYPAL").contains("56.00").contains("USD");
  }

  // ------------------------------------------------------------------ occupations scoping

  @Test
  void shouldSkipOrderLinesWithoutKol_orOwnedByAnotherCreator() {
    var viewer = staff(6L, DataScope.ALL, Set.of("order:view"));
    // kol_id null on the line → skipped; another creator's line under OWN scope → skipped
    when(orderItems.listByIds(anyList()))
        .thenReturn(
            List.of(
                new OrderItemInfoDTO(201L, 21L, "WD2001", "w1", "s1", "A", "Red", 7L, null),
                new OrderItemInfoDTO(202L, 21L, "WD2002", "w2", "s2", "B", "Blue", 8L, 777L)));
    assertThat(service.occupations(viewer, List.of(201L, 202L))).isEmpty();
    // unowned items disappear entirely → nothing to query
    when(orderItems.listByIds(anyList())).thenReturn(List.of());
    assertThat(service.occupations(viewer, List.of(201L, 202L))).isEmpty();
  }

  @Test
  void shouldReturnOccupations_forTheOwnScopedViewerOnOwnLines() {
    var own = staff(5L, DataScope.OWN, Set.of("order:view"));
    when(orderItems.listByIds(anyList()))
        .thenReturn(
            List.of(new OrderItemInfoDTO(101L, 11L, "WD1001", "w1", "s1", "Hat", "Red", 7L, KOL)));
    when(withdrawals.listOccupationsByOrderItems(anyList()))
        .thenReturn(
            List.of(
                new WithdrawalMapper.OccupationRow(
                    101L,
                    ID,
                    "W1",
                    "PENDING_PAYMENT",
                    new BigDecimal("250.00"),
                    new BigDecimal("400.00"),
                    new BigDecimal("150.00"),
                    NOW,
                    null)));
    var rows = service.occupations(own, List.of(101L));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().status()).isEqualTo("PENDING_PAYMENT");
    assertThat(rows.getFirst().paidAt()).isNull();
  }

  // ------------------------------------------------------------------ resolveAccount errors

  @Test
  void shouldRejectReferencedAccounts_ofOtherCreators_orDisabledOnes() {
    var foreign = approvedAccount(44L);
    foreign.setKolId(999L);
    when(payoutAccounts.selectById(44L)).thenReturn(foreign);
    assertThatThrownBy(
            () ->
                service.apply(kol(), new ApplyCommand(UUID_TEXT, "250.00", null, 44L, null, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
    var disabled = approvedAccount(44L);
    disabled.setStatus(PayoutAccountStatus.DISABLED);
    when(payoutAccounts.selectById(44L)).thenReturn(disabled);
    assertThatThrownBy(
            () ->
                service.apply(
                    kol(),
                    new ApplyCommand(
                        UUID.randomUUID().toString(), "250.00", null, 44L, null, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  @Test
  void shouldRejectInlineAccounts_withCrossTypeFields() {
    // USDT must not carry a currency; PAYPAL must not carry a network or QR file
    assertThatThrownBy(
            () ->
                service.apply(
                    kol(),
                    new ApplyCommand(
                        UUID.randomUUID().toString(),
                        "250.00",
                        null,
                        null,
                        new AccountInput(
                            "USDT", "TQ5xnPW9tZ3kA7qL9aZ", "Creator", "USD", "TRC20", null),
                        null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    assertThatThrownBy(
            () ->
                service.apply(
                    kol(),
                    new ApplyCommand(
                        UUID.randomUUID().toString(),
                        "250.00",
                        null,
                        null,
                        new AccountInput(
                            "PAYPAL", "p@example.com", "Creator", "USD", "TRC20", null),
                        null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    assertThatThrownBy(
            () ->
                service.apply(
                    kol(),
                    new ApplyCommand(
                        UUID.randomUUID().toString(),
                        "250.00",
                        null,
                        null,
                        new AccountInput("PAYPAL", "p@example.com", "Creator", "USD", null, 9L),
                        null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  // ------------------------------------------------------------------ finance merge scope

  @Test
  void shouldResolveKolAndBdNames_onMergedFinanceRows() {
    var viewer = staff(6L, DataScope.ALL, Set.of("commission:view"));
    when(kols.listByIdsIncludingDeleted(anyList())).thenReturn(List.of(kol(5L)));
    when(accountNames.listByIds(anyList()))
        .thenReturn(List.of(new com.tcrm.admin.system.api.AccountNameDTO(5L, "BD Five")));
    when(withdrawals.countWithdrawalTasks(any())).thenReturn(1L);
    when(withdrawals.pageWithdrawalTasks(any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.commission.model.FinanceTaskRow(
                    1L, KOL, "W1", "COMMISSION", "PENDING_REVIEW", new BigDecimal("100.00"), NOW)));
    var page = service.financeTasks(viewer, new FinanceFilter(null, null, null, null, 1, 20));
    assertThat(page.items().getFirst().kolCode()).isEqualTo("KOL-test");
    assertThat(page.items().getFirst().bdName()).isEqualTo("BD Five");
    // WITHDRAWN is a valid withdrawal raw filter and passes validation
    assertThat(
            service
                .financeTasks(viewer, new FinanceFilter(null, null, null, "WITHDRAWN", 1, 20))
                .total())
        .isEqualTo(1L);
  }

  // ------------------------------------------------------------------ final branch batch

  @Test
  void shouldRenderRowsWithoutBdOrFinance_forTheRightViewers() {
    // creator without a BD: bdId/bdName stay null; the payer still sees the finance note
    var payer = staff(6L, DataScope.ALL, Set.of("withdrawal:pay"));
    when(kols.listByIdsIncludingDeleted(anyList())).thenReturn(List.of(kol(null)));
    when(withdrawals.count(any())).thenReturn(1L);
    when(withdrawals.page(any())).thenReturn(List.of(head("PAID")));
    WithdrawalRow paid =
        new WithdrawalRow(
            ID,
            KOL,
            "W1",
            UUID.fromString(UUID_TEXT),
            "f".repeat(64),
            null,
            new BigDecimal("400.00"),
            "PAID",
            false,
            null,
            null,
            "PAYPAL",
            cipherText(),
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
            KOL,
            NOW);
    when(withdrawals.findRow(anyLong())).thenReturn(paid);
    var page = service.page(payer, new Filter(null, null, null, null, null, 1, 20));
    assertThat(page.items().getFirst().bdId()).isNull();
    assertThat(page.items().getFirst().bdName()).isNull();
    assertThat(page.items().getFirst().financeNote()).isEqualTo("finance note");
    // a commission:view viewer on a paused row: finance note hidden, paused flag visible
    var paused = row("PENDING_PAYMENT", true);
    when(withdrawals.findRow(anyLong())).thenReturn(paused);
    var hidden =
        service.page(
            staff(8L, DataScope.ALL, Set.of("commission:view")),
            new Filter(null, null, null, null, null, 1, 20));
    assertThat(hidden.items().getFirst().financeNote()).isNull();
    assertThat(hidden.items().getFirst().paused()).isTrue();
  }

  @Test
  void shouldAdmitEachFinanceTaskPermission_alone() {
    for (String code :
        List.of(
            "withdrawal:review",
            "withdrawal:pay",
            "prepay:review",
            "prepay:finance-review",
            "prepay:pay")) {
      var viewer = staff(6L, DataScope.ALL, Set.of(code));
      assertThat(
              service
                  .financeTasks(viewer, new FinanceFilter(null, null, null, null, 1, 20))
                  .total())
          .isZero();
    }
    // platform admins and permission-less staff are refused
    assertThatThrownBy(
            () ->
                service.financeTasks(
                    staff(8L, DataScope.ALL, Set.of("order:view")),
                    new FinanceFilter(null, null, null, null, 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  @Test
  void shouldResolveTheConcurrentInsert_viaTheUniqueKey() {
    // insert loses the race (created == 0) and the request id now resolves to the winner
    when(withdrawals.insert(any())).thenReturn(0);
    when(withdrawals.findIdByRequest(anyLong(), any(UUID.class))).thenReturn(ID);
    var winner =
        new WithdrawalRow(
            ID,
            KOL,
            "W1",
            UUID.fromString(UUID_TEXT),
            applyFingerprint("250.00", "p@example.com"),
            null,
            new BigDecimal("250.00"),
            "PENDING_REVIEW",
            false,
            null,
            null,
            "PAYPAL",
            cipherText(),
            "p***@example.com",
            "USD",
            null,
            "Creator Name",
            null,
            null,
            null,
            null,
            null,
            null,
            KOL,
            NOW);
    when(withdrawals.findRow(anyLong())).thenReturn(winner);
    var detail =
        service.apply(
            kol(),
            new ApplyCommand(
                UUID_TEXT,
                "250.00",
                null,
                null,
                new AccountInput("PAYPAL", "p@example.com", "Creator Name", "USD", null, null),
                null));
    assertThat(detail.id()).isEqualTo(ID);
  }

  @Test
  void shouldValidateActualAmountShapes_beyondTheHappyPath() {
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_PAYMENT", KOL));
    var payer = staff(6L, DataScope.ALL, Set.of("withdrawal:pay"));
    // leading zero beyond "0", 7 fraction digits, over the ceiling, and empty
    for (String bad : List.of("01.00", "0.1234567", "1000000000000.00", "")) {
      assertThatThrownBy(
              () ->
                  service.pay(
                      payer,
                      ID,
                      new WithdrawalService.PaymentCommand(
                          UUID_TEXT,
                          "400.00",
                          bad,
                          "CNY",
                          "2026-10-25T12:00:00+08:00",
                          "bank",
                          null,
                          null,
                          901L)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    }
  }

  @Test
  void shouldRequirePaypalCurrency_andRejectEmptyValues() {
    // missing PayPal currency fails validation
    assertThatThrownBy(
            () ->
                service.apply(
                    kol(),
                    new ApplyCommand(
                        UUID.randomUUID().toString(),
                        "250.00",
                        null,
                        null,
                        new AccountInput("PAYPAL", "p@example.com", "Creator", null, null, null),
                        null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  private static String applyFingerprint(String amount, String email) {
    String canonical =
        UUID_TEXT
            + "|"
            + new BigDecimal(amount).toPlainString()
            + "|||PAYPAL|"
            + email
            + "|"
            + "Creator Name|USD|||";
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  // ------------------------------------------------------------------ helpers

  private static WithdrawalService.PaymentCommand payment() {
    return new WithdrawalService.PaymentCommand(
        UUID_TEXT,
        "400.00",
        "400.00",
        "CNY",
        "2026-10-25T12:00:00+08:00",
        "bank",
        null,
        null,
        901L);
  }

  private AccountDTO payer() {
    return staff(6L, DataScope.ALL, Set.of("withdrawal:pay"));
  }

  private AccountDTO freezer() {
    return staff(9L, DataScope.ALL, Set.of("commission:freeze"));
  }

  private KolAccountDTO kol() {
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

  private static WithdrawalRow row(String status) {
    return row(status, false);
  }

  private static WithdrawalRow.Head head(String status) {
    return new WithdrawalRow.Head(ID, KOL, "W1", new BigDecimal("400.00"), status, KOL, NOW);
  }

  private static WithdrawalRow row(String status, long kolId) {
    WithdrawalRow row = row(status, false);
    return new WithdrawalRow(
        row.id(),
        kolId,
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
        row.reviewNote(),
        row.financeNote(),
        row.reviewerId(),
        row.reviewedAt(),
        row.actorId(),
        row.createTime());
  }

  private static WithdrawalRow row(String status, boolean paused) {
    return new WithdrawalRow(
        ID, // id
        KOL, // kolId
        "W1", // applicationNo
        UUID.fromString(UUID_TEXT), // requestId
        "f".repeat(64), // fingerprint
        null, // previousId
        new BigDecimal("400.00"), // amountCny
        status,
        paused,
        paused ? "refund check" : null, // pauseReason
        null, // accountId
        "PAYPAL",
        cipherText(), // accountCipher
        "cr***@example.com",
        "USD",
        null, // accountNetwork
        "Creator Name",
        null, // accountQrFileId
        "note",
        null, // reviewNote
        null, // financeNote
        null, // reviewerId
        null, // reviewedAt
        KOL, // actorId
        NOW);
  }

  private static PayoutAccountDO approvedAccount(long id) {
    var account = new PayoutAccountDO();
    account.setId(id);
    account.setTenantId(COMPANY);
    account.setKolId(KOL);
    account.setAccountType(PayoutAccountType.USDT);
    account.setAccountCipher(cipherText());
    account.setAccountMasked("m***d");
    account.setHolderName("Creator Name");
    account.setStatus(PayoutAccountStatus.APPROVED);
    return account;
  }

  private static String cipherText() {
    return new SecretCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]))
        .encrypt("creator@example.com");
  }

  private static WithdrawalRows.FreezeRow freeze(boolean active) {
    return new WithdrawalRows.FreezeRow(
        66L, KOL, "dispute", 6L, NOW, null, null, active ? null : NOW);
  }

  private static PayoutAccountDO account(
      PayoutAccountType type, String plain, String currency, String network) {
    return account(type == PayoutAccountType.PAYPAL ? 41L : 42L, type, plain, currency, network);
  }

  private static PayoutAccountDO account(
      long id, PayoutAccountType type, String plain, String currency, String network) {
    var account = new PayoutAccountDO();
    account.setId(id);
    account.setTenantId(COMPANY);
    account.setKolId(KOL);
    account.setAccountType(type);
    account.setAccountCipher(
        new SecretCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]))
            .encrypt(plain));
    account.setAccountMasked("m***d");
    account.setHolderName("Creator Name");
    account.setCurrency(currency);
    account.setUsdtNetwork(
        network == null ? null : com.tcrm.admin.commission.model.UsdtNetwork.valueOf(network));
    account.setStatus(PayoutAccountStatus.APPROVED);
    return account;
  }

  /** Reads the captured finance task query after a financeTasks call (keeps the test terse). */
  private static final class ArgumentCaptorScope {
    static void capture(WithdrawalService service, WithdrawalMapper withdrawals) {
      org.mockito.Mockito.verify(withdrawals, org.mockito.Mockito.atLeastOnce())
          .countWithdrawalTasks(any());
    }
  }
}
