package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.mapper.PayoutAccountMapper;
import com.tcrm.admin.commission.mapper.WithdrawalFreezeMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.model.FinanceTaskQuery;
import com.tcrm.admin.commission.model.FinanceTaskRow;
import com.tcrm.admin.commission.model.PayoutAccountDO;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.model.WithdrawalRow;
import com.tcrm.admin.commission.model.WithdrawalRows;
import com.tcrm.admin.commission.model.WithdrawalWrite;
import com.tcrm.admin.commission.service.WithdrawalFundingSource.FundingRow;
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
import org.mockito.ArgumentCaptor;

/**
 * Withdrawal state machine, money invariants, masking and scope; SQL execution is covered by the
 * ITs. Money fixtures list the hand-computed values in comments.
 */
class WithdrawalServiceTest {
  private static final long COMPANY = 11L;
  private static final long ID = 77L;
  private static final long KOL = 22L;
  private static final long ITEM_1 = 101L;
  private static final long ITEM_2 = 102L;
  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00.987654Z");
  private static final String UUID_TEXT = "7565e681-dfd4-48bb-9f83-82e66cbb04ad";
  private static final long VOUCHER_ID = 881L;

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
  private static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);
  private static final SecretCipher STATIC_CIPHER = new SecretCipher(KEY);
  private final SecretCipher cipher = new SecretCipher(KEY);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final SystemConfigApi configs = mock(SystemConfigApi.class);
  private final CommissionRuleConfigService ruleConfig = mock(CommissionRuleConfigService.class);
  private WithdrawalService service;

  @BeforeEach
  void prepare() {
    service =
        spy(
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
    when(configs.getInt("commission.minimum-withdrawal-cny")).thenReturn(200);
    when(ruleConfig.current())
        .thenReturn(
            new CommissionRuleConfigService.CurrentConfig(
                0, new BigDecimal("200.00"), "SETTLE_TIME", 7, null));
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(5L)));
    when(kols.lockKol(KOL)).thenReturn(true);
    when(wallets.lockBalance(KOL)).thenReturn(new BigDecimal("500.00"));
    when(withdrawals.sumActiveOccupied(KOL)).thenReturn(BigDecimal.ZERO);
    when(funding.frozenCny(KOL)).thenReturn(new BigDecimal("120.00"));
    when(funding.estimatedCny(KOL)).thenReturn(new BigDecimal("0.00"));
    when(funding.unverifiedCount(KOL)).thenReturn(0L);
    when(funding.configVersion(KOL)).thenReturn(0);
    when(funding.listReleasedRows(KOL))
        .thenReturn(
            List.of(
                new FundingRow(ITEM_1, 0L, new BigDecimal("350.00")),
                new FundingRow(ITEM_2, 0L, new BigDecimal("150.00"))));
    when(orderItems.listByIds(anyList()))
        .thenReturn(
            List.of(
                new OrderItemInfoDTO(ITEM_1, 11L, "WD1001", "w1", "sku1", "Hat", "Red", 7L, KOL),
                new OrderItemInfoDTO(ITEM_2, 11L, "WD1002", "w2", "sku2", "Cup", "Blue", 8L, KOL)));
    when(products.listProducts(anyList())).thenReturn(List.of());
    when(withdrawals.sumOccupiedByItem(eq(KOL), anyList())).thenReturn(List.of());
    when(withdrawals.nextSequence(anyLong(), any(), any())).thenReturn(7);
    when(freezes.latest(KOL)).thenReturn(null);
    when(withdrawals.countAllocations(anyLong())).thenReturn(2L);
    when(withdrawals.timeline(anyLong())).thenReturn(List.of());
    when(withdrawals.findPayment(anyLong())).thenReturn(null);
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_REVIEW"));
    when(withdrawals.findHead(anyLong())).thenReturn(head());
    when(withdrawals.lockById(anyLong())).thenReturn(ID);
    when(withdrawals.insert(any())).thenReturn(1);
    when(kols.listByIdsIncludingDeleted(anyList())).thenReturn(List.of(kol(5L)));
    when(accountNames.listByIds(anyList())).thenReturn(List.of());
    when(withdrawals.findIdByRequest(anyLong(), any(UUID.class))).thenReturn(null);
    when(withdrawals.count(any())).thenReturn(0L);
    when(withdrawals.page(any())).thenReturn(List.of());
    when(withdrawals.withdraw(any())).thenReturn(1);
    when(withdrawals.insertPayment(any())).thenReturn(1);
  }

  // ------------------------------------------------------------------ conditions

  @Test
  void shouldShowThresholdOccupationAndAvailability_toSignedInCreator() {
    when(wallets.findBalance(KOL)).thenReturn(new BigDecimal("300.00"));
    when(withdrawals.sumActiveOccupied(KOL)).thenReturn(new BigDecimal("120.00"));
    // available = 300.00 - 120.00 = 180.00 < minimum 200.00 → not eligible
    var conditions = service.conditions(COMPANY, KOL);
    assertThat(conditions.available()).isEqualTo("180.00");
    assertThat(conditions.occupied()).isEqualTo("120.00");
    assertThat(conditions.minimum()).isEqualTo("200.00");
    assertThat(conditions.canApply()).isFalse();
  }

  // ------------------------------------------------------------------ apply

  @Test
  void shouldAllocateFifoAndSnapshotRemainder_whenApplicationIsValid() {
    var detail = service.apply(kol(), apply("400.00", null));
    assertThat(detail.applicationNo()).isEqualTo("W20261025000007");
    ArgumentCaptor<WithdrawalWrite.Insert> insert =
        ArgumentCaptor.forClass(WithdrawalWrite.Insert.class);
    verify(withdrawals).insert(insert.capture());
    assertThat(insert.getValue().amount()).isEqualByComparingTo("400.00");
    assertThat(insert.getValue().accountCipher()).startsWith("v1:");
    assertThat(insert.getValue().accountMasked()).isNotEqualTo("creator@example.com");
    List<WithdrawalWrite.Allocation> allocations = allocations();
    // item1 released 350.00: occupy 350.00, remaining snapshot 0.00; item2 released 150.00:
    // occupy 50.00, remaining snapshot 100.00; total 400.00 == amount
    assertThat(allocations).hasSize(2);
    assertThat(allocations.get(0).orderItemId()).isEqualTo(ITEM_1);
    assertThat(allocations.get(0).occupied()).isEqualByComparingTo("350.00");
    assertThat(allocations.get(0).remainingSnapshot()).isEqualByComparingTo("0.00");
    assertThat(allocations.get(0).sourceReleased()).isEqualByComparingTo("350.00");
    assertThat(allocations.get(0).orderNo()).isEqualTo("WD1001");
    assertThat(allocations.get(1).orderItemId()).isEqualTo(ITEM_2);
    assertThat(allocations.get(1).occupied()).isEqualByComparingTo("50.00");
    assertThat(allocations.get(1).remainingSnapshot()).isEqualByComparingTo("100.00");
    verify(withdrawals).insertHistory(historyAction("SUBMITTED"));
    verify(withdrawals).insertOutbox(outboxEvent("SUBMITTED"));
  }

  @Test
  void shouldAllocateOnlyTheLeftover_theFundingSourceReports() {
    // The funding source already nets occupations out: item1 leftover 250.00 (350.00 released
    // minus 100.00 occupied), item2 150.00. Application 300.00 takes 250.00 from item1 (remaining
    // snapshot 0.00) and 50.00 from item2 (remaining snapshot 100.00).
    when(funding.listReleasedRows(KOL))
        .thenReturn(
            List.of(
                new FundingRow(ITEM_1, 0L, new BigDecimal("250.00")),
                new FundingRow(ITEM_2, 0L, new BigDecimal("150.00"))));
    service.apply(kol(), apply("300.00", null));
    List<WithdrawalWrite.Allocation> allocations = allocations();
    assertThat(allocations.get(0).occupied()).isEqualByComparingTo("250.00");
    assertThat(allocations.get(0).remainingSnapshot()).isEqualByComparingTo("0.00");
    assertThat(allocations.get(0).sourceReleased()).isEqualByComparingTo("250.00");
    assertThat(allocations.get(1).occupied()).isEqualByComparingTo("50.00");
    assertThat(allocations.get(1).remainingSnapshot()).isEqualByComparingTo("100.00");
  }

  @Test
  void shouldRejectBelowMinimum_beforeTouchingAnything() {
    assertThatThrownBy(() -> service.apply(kol(), apply("199.99", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_BELOW_MINIMUM));
    verify(withdrawals, never()).insert(any());
  }

  @Test
  void shouldRejectAboveAvailableBalance() {
    // wallet 500.00, occupied 150.00 → available 350.00; funding 500.00 covers 400.00, money does
    // not
    when(withdrawals.sumActiveOccupied(KOL)).thenReturn(new BigDecimal("150.00"));
    assertThatThrownBy(() -> service.apply(kol(), apply("400.00", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_INSUFFICIENT_AVAILABLE));
  }

  @Test
  void shouldRejectWhenFundingRowsAreShort() {
    // wallet 500.00 but released rows total only 350.00 → 400.00 cannot be allocated
    when(funding.listReleasedRows(KOL))
        .thenReturn(List.of(new FundingRow(ITEM_1, 0L, new BigDecimal("350.00"))));
    assertThatThrownBy(() -> service.apply(kol(), apply("400.00", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_INSUFFICIENT_AVAILABLE));
  }

  @Test
  void shouldReturnExistingApplication_whenIdempotencyKeyRepeats() {
    when(withdrawals.findIdByRequest(eq(KOL), any(UUID.class))).thenReturn(ID);
    when(withdrawals.findRow(anyLong()))
        .thenReturn(row("PENDING_REVIEW", KOL, KOL, applyFingerprint("400.00")));
    var detail = service.apply(kol(), apply("400.00", null));
    assertThat(detail.id()).isEqualTo(ID);
    verify(withdrawals, never()).insert(any());
  }

  @Test
  void shouldConflict_whenIdempotencyKeyRepeatsWithDifferentAmount() {
    when(withdrawals.findIdByRequest(eq(KOL), any(UUID.class))).thenReturn(ID);
    assertThatThrownBy(() -> service.apply(kol(), apply("300.00", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_REQUEST_CONFLICT));
  }

  @Test
  void shouldRejectFrozenCreator() {
    when(freezes.latest(KOL)).thenReturn(freeze(true));
    assertThatThrownBy(() -> service.apply(kol(), apply("400.00", null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_KOL_FROZEN));
  }

  @Test
  void shouldRejectPreviousApplicationUnlessRejectedOwnOne() {
    when(withdrawals.findRow(55L)).thenReturn(row("PENDING_REVIEW"));
    assertThatThrownBy(() -> service.apply(kol(), apply("400.00", 55L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_NOT_RESUBMITTABLE));
    when(withdrawals.findRow(55L)).thenReturn(rejectedRow(55L));
    service.apply(kol(), apply("400.00", 55L));
    verify(withdrawals).insert(any());
  }

  @Test
  void shouldSnapshotApprovedPayoutAccount_whenReferencedById() {
    var account = approvedAccount();
    when(payoutAccounts.selectById(33L)).thenReturn(account);
    service.apply(
        kol(), new WithdrawalService.ApplyCommand(UUID_TEXT, "400.00", null, 33L, null, "note"));
    ArgumentCaptor<WithdrawalWrite.Insert> insert =
        ArgumentCaptor.forClass(WithdrawalWrite.Insert.class);
    verify(withdrawals).insert(insert.capture());
    assertThat(insert.getValue().accountId()).isEqualTo(33L);
    assertThat(insert.getValue().accountType()).isEqualTo("PAYPAL");
    assertThat(insert.getValue().accountCipher()).isEqualTo("v1:cipher");
    assertThat(insert.getValue().accountCurrency()).isEqualTo("USD");
    assertThat(insert.getValue().accountNetwork()).isNull();
  }

  @Test
  void shouldRejectForeignOrUnapprovedPayoutAccount() {
    var account = approvedAccount();
    account.setKolId(999L);
    when(payoutAccounts.selectById(33L)).thenReturn(account);
    assertThatThrownBy(
            () ->
                service.apply(
                    kol(),
                    new WithdrawalService.ApplyCommand(UUID_TEXT, "400.00", null, 33L, null, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  // ------------------------------------------------------------------ withdraw

  @Test
  void shouldWithdrawOwnPendingApplicationOnly() {
    var detail = service.withdraw(kol(), ID);
    assertThat(detail.id()).isEqualTo(ID);
    verify(withdrawals).withdraw(any());
    verify(withdrawals).insertHistory(historyAction("WITHDRAWN"));
  }

  @Test
  void shouldHideOtherCreatorsApplication() {
    when(withdrawals.findHead(anyLong()))
        .thenReturn(
            new WithdrawalRow.Head(
                ID,
                999L,
                "W20261025000007",
                new BigDecimal("400.00"),
                "PENDING_REVIEW",
                999L,
                NOW));
    assertThatThrownBy(() -> service.withdraw(kol(), ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  @Test
  void shouldRefuseWithdrawAfterReview() {
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_PAYMENT"));
    when(withdrawals.withdraw(any())).thenReturn(0);
    assertThatThrownBy(() -> service.withdraw(kol(), ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_STATE_CONFLICT));
  }

  // ------------------------------------------------------------------ review

  @Test
  void shouldApprovePendingReview_withWithdrawalReviewPermission() {
    when(withdrawals.decide(any())).thenReturn(1);
    doReturn(detail("PENDING_PAYMENT")).when(service).detail(any(), anyLong());
    assertThat(service.review(reviewer("withdrawal:review"), ID, "APPROVE", "ok").status())
        .isEqualTo("PENDING_PAYMENT");
    ArgumentCaptor<WithdrawalWrite.Decide> decide =
        ArgumentCaptor.forClass(WithdrawalWrite.Decide.class);
    verify(withdrawals).decide(decide.capture());
    assertThat(decide.getValue().current()).isEqualTo("PENDING_REVIEW");
    verify(withdrawals).insertHistory(historyAction("APPROVED"));
    verify(withdrawals).insertOutbox(outboxEvent("APPROVED"));
  }

  @Test
  void shouldRejectWithReasonOnly() {
    when(withdrawals.decide(any())).thenReturn(1);
    doReturn(detail("REJECTED")).when(service).detail(any(), anyLong());
    service.review(reviewer("withdrawal:review"), ID, "REJECT", "wrong account");
    assertThatThrownBy(() -> service.review(reviewer("withdrawal:review"), ID, "REJECT", " "))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    verify(withdrawals).insertHistory(historyAction("REJECTED"));
  }

  @Test
  void shouldForbidReviewerFromReviewingOwnSubmission() {
    // actor 6 reviews an application whose actor_id is also 6 (guard mirrors the prepay rule)
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_REVIEW", KOL, 6L));
    assertThatThrownBy(() -> service.review(reviewer("withdrawal:review"), ID, "APPROVE", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  @Test
  void shouldForbidReviewWithoutPermission() {
    assertThatThrownBy(() -> service.review(reviewer("commission:view"), ID, "APPROVE", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  @Test
  void shouldRejectBeforePaymentOnlyWithPayPermission() {
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_PAYMENT"));
    when(withdrawals.decide(any())).thenReturn(1);
    doReturn(detail("REJECTED")).when(service).detail(any(), anyLong());
    assertThatThrownBy(() -> service.review(reviewer("withdrawal:review"), ID, "REJECT", "no"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    service.review(reviewer("withdrawal:pay"), ID, "REJECT", "refund dispute");
    verify(withdrawals).insertHistory(historyAction("REJECTED"));
  }

  @Test
  void shouldRefuseIllegalTransitions() {
    // approving an already approved task, or reviewing a finished one
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_PAYMENT"));
    assertThatThrownBy(() -> service.review(reviewer("withdrawal:pay"), ID, "APPROVE", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_STATE_CONFLICT));
    when(withdrawals.findRow(ID)).thenReturn(row("PAID"));
    assertThatThrownBy(() -> service.review(reviewer("withdrawal:review"), ID, "REJECT", "x"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_STATE_CONFLICT));
  }

  @Test
  void shouldHideForeignCreatorFromOwnScopedReviewer() {
    // BD 5 reviews a creator now owned by BD 9: after a transfer the old BD must see nothing
    // (07.05)
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(9L)));
    assertThatThrownBy(() -> service.review(user("withdrawal:review"), ID, "APPROVE", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  // ------------------------------------------------------------------ payment

  @Test
  void shouldRegisterPaymentOnce_andDebitTheWalletLedger() {
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_PAYMENT"));
    when(withdrawals.sumAllocated(ID)).thenReturn(new BigDecimal("400.00"));
    org.mockito.Mockito.doNothing()
        .when(files)
        .claimVoucherFile(anyLong(), anyLong(), anyLong(), anyLong());
    when(withdrawals.markPaid(any())).thenReturn(1);
    when(withdrawals.findPaymentFingerprint(any(UUID.class))).thenReturn(null);
    doReturn(detail("PAID")).when(service).detail(any(), anyLong());
    var result = service.pay(reviewer("withdrawal:pay"), ID, payment("400.00", "400.00", "CNY"));
    assertThat(result.status()).isEqualTo("PAID");
    // wallet leaves exactly the application amount at the payment time
    verify(wallet)
        .postWithdrawalDebit(
            COMPANY, KOL, ID, new BigDecimal("400.00"), Instant.parse("2026-10-25T04:00:00Z"), 6L);
    verify(withdrawals).markPaid(any());
    verify(withdrawals).insertHistory(historyAction("PAID"));
    verify(withdrawals).insertOutbox(outboxEvent("PAID"));
  }

  @Test
  void shouldRefusePaymentOutsidePaymentState() {
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_REVIEW"));
    assertThatThrownBy(
            () -> service.pay(reviewer("withdrawal:pay"), ID, payment("400.00", "400.00", "CNY")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_STATE_CONFLICT));
  }

  @Test
  void shouldRefuseSecondSuccessfulPayment() {
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_PAYMENT"));
    when(withdrawals.sumAllocated(ID)).thenReturn(new BigDecimal("400.00"));
    when(withdrawals.findPayment(ID)).thenReturn(paymentRow());
    assertThatThrownBy(
            () -> service.pay(reviewer("withdrawal:pay"), ID, payment("400.00", "400.00", "CNY")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_PAYMENT_CONFLICT));
  }

  @Test
  void shouldReplaySamePaymentRequest_andConflictOnDifferentOne() {
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_PAYMENT"));
    when(withdrawals.findPaymentFingerprint(any(UUID.class)))
        .thenReturn(paymentFingerprint("400.00", "400.00", "CNY"));
    when(withdrawals.sumAllocated(ID)).thenReturn(new BigDecimal("400.00"));
    org.mockito.Mockito.doNothing()
        .when(files)
        .claimVoucherFile(anyLong(), anyLong(), anyLong(), anyLong());
    when(withdrawals.markPaid(any())).thenReturn(1);
    doReturn(detail("PAID")).when(service).detail(any(), anyLong());
    service.pay(reviewer("withdrawal:pay"), ID, payment("400.00", "400.00", "CNY"));
    // the real fingerprint of the second request differs → 409, nothing written
    assertThatThrownBy(
            () -> service.pay(reviewer("withdrawal:pay"), ID, payment("400.00", "380.00", "USD")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_PAYMENT_CONFLICT));
  }

  @Test
  void shouldRequireConversionNoteForForeignCurrencyPayment() {
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_PAYMENT"));
    var foreignWithoutNote =
        new WithdrawalService.PaymentCommand(
            UUID_TEXT,
            "400.00",
            "56.00",
            "USD",
            "2026-10-25T12:00:00+08:00",
            "bank",
            null,
            null,
            VOUCHER_ID);
    assertThatThrownBy(() -> service.pay(reviewer("withdrawal:pay"), ID, foreignWithoutNote))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  @Test
  void shouldRequireFinanceNoteWhenActualDiffers() {
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_PAYMENT"));
    when(withdrawals.sumAllocated(ID)).thenReturn(new BigDecimal("400.00"));
    assertThatThrownBy(
            () ->
                service.pay(
                    reviewer("withdrawal:pay"),
                    ID,
                    new WithdrawalService.PaymentCommand(
                        UUID_TEXT,
                        "400.00",
                        "390.00",
                        "CNY",
                        "2026-10-25T12:00:00+08:00",
                        "bank",
                        null,
                        null,
                        VOUCHER_ID)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  // ------------------------------------------------------------------ masking & scope

  @Test
  void shouldMaskPayoutAndHideFinanceNotes_forNonFinanceViewers() {
    var detail = service.detail(reviewer("commission:view"), ID);
    assertThat(detail.account().account()).isNotEqualTo("creator@example.com");
    assertThat(detail.account().account()).contains("*");
    assertThat(detail.financeNote()).isNull();
  }

  @Test
  void shouldRevealFullPayoutAndAudit_forPermittedReaders() {
    var recipient = service.recipient(reviewer("withdrawal:pay"), ID);
    assertThat(recipient.account()).isEqualTo("creator@example.com");
    assertThat(recipient.holder()).isEqualTo("Creator Name");
    verify(audit).recordTenantOperation(anyLong(), anyLong(), any(), any(), any(), any());
    assertThatThrownBy(() -> service.recipient(reviewer("withdrawal:review"), ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  @Test
  void shouldGiveCreatorMaskedSnapshot_withoutFinanceNotes() {
    var detail = service.kolDetail(COMPANY, KOL, ID);
    assertThat(detail.account().account()).isEqualTo("cr***@example.com");
    assertThat(detail.financeNote()).isNull();
    when(withdrawals.findRow(ID)).thenReturn(row("PENDING_REVIEW", 999L));
    assertThatThrownBy(() -> service.kolDetail(COMPANY, KOL, ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  @Test
  void shouldLimitListToOwnCreators_forOwnScopedViewers() {
    when(kols.getByCode("KOL-test")).thenReturn(kol(9L));
    when(kols.listByBdUserId(5L)).thenReturn(List.of(kol(5L)));
    when(withdrawals.count(any())).thenReturn(0L);
    when(withdrawals.page(any())).thenReturn(List.of());
    when(withdrawals.withdraw(any())).thenReturn(1);
    when(withdrawals.insertPayment(any())).thenReturn(1);
    // the requested creator belongs to BD 9, the viewer is BD 5 → empty page
    var page =
        service.page(
            user("withdrawal:review"),
            new WithdrawalService.Filter(null, null, "KOL-test", null, null, 1, 20));
    assertThat(page.total()).isZero();
  }

  @Test
  void shouldRefuseExportWithoutDataExport() {
    assertThatThrownBy(
            () ->
                service.export(
                    user("withdrawal:review"),
                    new WithdrawalService.Filter(null, null, null, null, null, 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  @Test
  void shouldExportCsvWithMaskedAccountOnly() {
    when(withdrawals.page(any())).thenReturn(List.of(head()));
    when(withdrawals.count(any())).thenReturn(1L);
    var csv =
        service.export(
            reviewer("data:export", "withdrawal:review"),
            new WithdrawalService.Filter(null, null, null, null, null, 1, 20));
    var text = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
    assertThat(text).contains("W20261025000007").contains("PENDING_REVIEW");
    assertThat(text).doesNotContain("creator@example.com");
  }

  // ------------------------------------------------------------------ freeze

  @Test
  void shouldFreezeWithReason_andRefuseDoubleFreeze() {
    service.freeze(reviewer("commission:freeze"), KOL, " dispute ");
    verify(freezes).insert(any());
    when(freezes.latest(KOL)).thenReturn(freeze(true));
    assertThatThrownBy(() -> service.freeze(reviewer("commission:freeze"), KOL, "again"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.WITHDRAWAL_FROZEN_ALREADY));
    assertThatThrownBy(() -> service.freeze(reviewer("commission:freeze"), KOL, " "))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  @Test
  void shouldResumeOnlyWhileFrozen() {
    when(freezes.lockActive(KOL)).thenReturn(66L);
    service.resume(reviewer("commission:freeze"), KOL, "resolved");
    verify(freezes).resume(any());
    when(freezes.lockActive(KOL)).thenReturn(null);
    assertThatThrownBy(() -> service.resume(reviewer("commission:freeze"), KOL, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_NOT_FROZEN));
  }

  // ------------------------------------------------------------------ finance tasks

  @Test
  void shouldMergeWithdrawalsAndPrepays_onTheUnifiedList() {
    when(withdrawals.countWithdrawalTasks(any())).thenReturn(2L);
    when(withdrawals.countPrepayTasks(any())).thenReturn(1L);
    when(withdrawals.pageWithdrawalTasks(any()))
        .thenReturn(
            List.of(
                new FinanceTaskRow(
                    1L,
                    KOL,
                    "W1",
                    "COMMISSION",
                    "PENDING_REVIEW",
                    new BigDecimal("100.00"),
                    NOW.minusSeconds(60)),
                new FinanceTaskRow(
                    2L,
                    KOL,
                    "W2",
                    "COMMISSION",
                    "PAID",
                    new BigDecimal("50.00"),
                    NOW.minusSeconds(600))));
    when(withdrawals.pagePrepayTasks(any()))
        .thenReturn(
            List.of(
                new FinanceTaskRow(
                    3L, KOL, "PP1", "PREPAY", "APPROVED", new BigDecimal("70.00"), NOW)));
    var page =
        service.financeTasks(
            reviewer("commission:view"),
            new WithdrawalService.FinanceFilter(null, null, null, null, 1, 2));
    assertThat(page.total()).isEqualTo(3L);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).applicationNo()).isEqualTo("PP1");
    assertThat(page.items().get(1).applicationNo()).isEqualTo("W1");
    // the raw status passes straight through to both branches
    ArgumentCaptor<FinanceTaskQuery> query = ArgumentCaptor.forClass(FinanceTaskQuery.class);
    verify(withdrawals).pageWithdrawalTasks(query.capture());
    assertThat(query.getValue().status()).isNull();
    var filtered =
        service.financeTasks(
            reviewer("commission:view"),
            new WithdrawalService.FinanceFilter(null, null, null, "PENDING_PAYMENT", 1, 20));
    verify(withdrawals, times(2)).pageWithdrawalTasks(query.capture());
    assertThat(query.getValue().status()).isEqualTo("PENDING_PAYMENT");
    assertThat(filtered).isNotNull();
  }

  @Test
  void shouldRefuseFinanceTasksWithoutAnyFinancePermission() {
    assertThatThrownBy(
            () ->
                service.financeTasks(
                    user("order:view"),
                    new WithdrawalService.FinanceFilter(null, null, null, null, 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  // ------------------------------------------------------------------ helpers

  private List<WithdrawalWrite.Allocation> allocations() {
    ArgumentCaptor<WithdrawalWrite.Allocation> captor =
        ArgumentCaptor.forClass(WithdrawalWrite.Allocation.class);
    verify(withdrawals, org.mockito.Mockito.atLeastOnce()).insertAllocation(captor.capture());
    return captor.getAllValues();
  }

  private static WithdrawalWrite.History historyAction(String action) {
    return org.mockito.ArgumentMatchers.argThat(row -> row.action().equals(action));
  }

  private static WithdrawalWrite.Outbox outboxEvent(String type) {
    return org.mockito.ArgumentMatchers.argThat(row -> row.eventType().equals(type));
  }

  /** Mirrors the service's canonical apply fingerprint (amount, refs and payout input). */
  private static String applyFingerprint(String amount) {
    String canonical =
        UUID_TEXT
            + "|"
            + new BigDecimal(amount).setScale(2).toPlainString()
            + "|||PAYPAL|creator@example.com|Creator Name|USD|||";
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static WithdrawalService.ApplyCommand apply(String amount, Long previousId) {
    return new WithdrawalService.ApplyCommand(
        UUID_TEXT,
        amount,
        previousId,
        null,
        new WithdrawalService.AccountInput(
            "PAYPAL", "creator@example.com", "Creator Name", "USD", null, null),
        null);
  }

  private static WithdrawalService.PaymentCommand payment(
      String amountCny, String actualAmount, String actualCurrency) {
    return new WithdrawalService.PaymentCommand(
        UUID_TEXT,
        amountCny,
        actualAmount,
        actualCurrency,
        "2026-10-25T12:00:00+08:00",
        "bank",
        null,
        "rate 7.1",
        VOUCHER_ID);
  }

  /** Mirrors the service's canonical payment fingerprint for the standard payment fixture. */
  private static String paymentFingerprint(
      String amountCny, String actualAmount, String actualCurrency) {
    String canonical =
        ID
            + "|"
            + UUID_TEXT
            + "|"
            + new BigDecimal(amountCny).setScale(2).toPlainString()
            + "|"
            + new BigDecimal(actualAmount).setScale(2).toPlainString()
            + "|"
            + actualCurrency
            + "|2026-10-25T04:00:00Z|bank||rate 7.1|"
            + VOUCHER_ID;
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
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

  private static AccountDTO user(String... permissions) {
    return accountUser(5L, Role.STAFF, DataScope.OWN, COMPANY, permissions);
  }

  private static AccountDTO reviewer(String... permissions) {
    return accountUser(6L, Role.STAFF, DataScope.ALL, COMPANY, permissions);
  }

  private static AccountDTO accountUser(
      Long id, Role role, DataScope scope, Long company, String... permissions) {
    return new AccountDTO(
        id,
        "t63_user",
        null,
        "Test",
        "t63@example.invalid",
        role,
        scope,
        1,
        null,
        NOW,
        0,
        company,
        "Test",
        true,
        Set.of(permissions),
        null);
  }

  private static WithdrawalRow row(String status) {
    return row(status, KOL, KOL);
  }

  private static WithdrawalRow row(String status, long kolId) {
    return row(status, kolId, KOL);
  }

  private static WithdrawalRow row(String status, long kolId, long actorId) {
    return row(status, kolId, actorId, "f".repeat(64));
  }

  private static WithdrawalRow row(String status, long kolId, long actorId, String fingerprint) {
    return new WithdrawalRow(
        ID,
        kolId,
        "W20261025000007",
        UUID.fromString(UUID_TEXT),
        fingerprint,
        null,
        new BigDecimal("400.00"),
        status,
        false,
        null,
        null,
        "PAYPAL",
        CIPHER_TEXT,
        "cr***@example.com",
        "USD",
        null,
        "Creator Name",
        null,
        null,
        null,
        null,
        null,
        null,
        actorId,
        NOW);
  }

  private static WithdrawalRow rejectedRow(long id) {
    return new WithdrawalRow(
        id,
        KOL,
        "CW20261025000006",
        UUID.randomUUID(),
        "f".repeat(64),
        null,
        new BigDecimal("400.00"),
        "REJECTED",
        false,
        null,
        null,
        "PAYPAL",
        CIPHER_TEXT,
        "cr***@example.com",
        "USD",
        null,
        "Creator Name",
        null,
        null,
        null,
        "wrong account",
        null,
        null,
        KOL,
        NOW);
  }

  private static WithdrawalRow.Head head() {
    return new WithdrawalRow.Head(
        ID, KOL, "W20261025000007", new BigDecimal("400.00"), "PENDING_REVIEW", KOL, NOW);
  }

  private static WithdrawalService.Detail detail(String status) {
    return new WithdrawalService.Detail(
        ID,
        "W20261025000007",
        KOL,
        "KOL-test",
        "Test creator",
        null,
        null,
        "COMMISSION",
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

  private static WithdrawalRows.PaymentRow paymentRow() {
    return new WithdrawalRows.PaymentRow(
        new BigDecimal("400.00"),
        new BigDecimal("400.00"),
        "CNY",
        NOW,
        "bank",
        null,
        null,
        88L,
        6L,
        "Test",
        "PAYMENT");
  }

  private static WithdrawalRows.FreezeRow freeze(boolean active) {
    return new WithdrawalRows.FreezeRow(
        66L, KOL, "dispute", 6L, NOW, null, null, active ? null : NOW);
  }

  private static PayoutAccountDO approvedAccount() {
    var account = new PayoutAccountDO();
    account.setId(33L);
    account.setTenantId(COMPANY);
    account.setKolId(KOL);
    account.setAccountType(PayoutAccountType.PAYPAL);
    account.setAccountCipher("v1:cipher");
    account.setAccountMasked("cr***@example.com");
    account.setHolderName("Creator Name");
    account.setCurrency("USD");
    account.setStatus(PayoutAccountStatus.APPROVED);
    return account;
  }

  /** Real ciphertext of "creator@example.com" under the test key. */
  private static final String CIPHER_TEXT = STATIC_CIPHER.encrypt("creator@example.com");
}
