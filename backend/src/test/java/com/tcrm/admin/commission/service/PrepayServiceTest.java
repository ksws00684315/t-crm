package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.PayoutAccountMapper;
import com.tcrm.admin.commission.mapper.PrepayMapper;
import com.tcrm.admin.commission.model.FileBizType;
import com.tcrm.admin.commission.model.PayoutAccountDO;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.model.PrepayApplicationColumns;
import com.tcrm.admin.commission.model.PrepayDeductionRow;
import com.tcrm.admin.commission.model.PrepayHead;
import com.tcrm.admin.commission.model.PrepayHistoryRow;
import com.tcrm.admin.commission.model.PrepayPageQuery;
import com.tcrm.admin.commission.model.PrepayPaymentRow;
import com.tcrm.admin.commission.model.PrepayRecipientRow;
import com.tcrm.admin.commission.model.PrepayWrite;
import com.tcrm.admin.commission.model.UsdtNetwork;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.AccountNameApi;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.dao.EmptyResultDataAccessException;

/** State transitions, authorization and money invariants; SQL execution is covered by the ITs. */
class PrepayServiceTest {
  private static final long COMPANY = 11L;
  private static final long ID = 77L;
  private static final long KOL = 22L;
  private static final Instant NOW = Instant.parse("2026-10-21T16:01:02.123456Z");
  private static final String UUID_TEXT = "7565e681-dfd4-48bb-9f83-82e66cbb04ad";
  private final KolApi kols = mock(KolApi.class);
  private final PayoutAccountMapper accounts = mock(PayoutAccountMapper.class);
  private final BizFileService files = mock(BizFileService.class);
  private final SecretCipher cipher = mock(SecretCipher.class);
  private final CommissionWalletService wallet = mock(CommissionWalletService.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final AccountNameApi accountNames = mock(AccountNameApi.class);
  private final List<Write> writes = new ArrayList<>();
  private final List<String> reads = new ArrayList<>();
  private final List<PageCall> pageCalls = new ArrayList<>();
  private final List<Rows> fixtures = new ArrayList<>();
  private final Map<String, Object> scalars = new HashMap<>();
  private int changed = 1;
  private final PrepayMapper prepays = mock(PrepayMapper.class, this::answer);
  private PrepayService service;

  @BeforeEach
  void prepare() {
    service =
        spy(
            new PrepayService(
                prepays,
                kols,
                accountNames,
                accounts,
                files,
                cipher,
                wallet,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC)));
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(5L)));
    when(kols.lockKol(KOL)).thenReturn(true);
    when(wallet.currentBalance(COMPANY, KOL)).thenReturn(new BigDecimal("-200.00"));
    when(files.prepayAttachments(ID)).thenReturn(List.of());
    rows(
        sql -> sql.contains("JOIN crm_kol") && !sql.contains("request_id"), row("WAITING_DETAILS"));
  }

  @Test
  void shouldCreateOnePendingTaskAndOutbox_whenOnlyAmountIsSubmitted() {
    var expected = detail("WAITING_DETAILS", null);
    doReturn(expected).when(service).detail(any(), anyLong());
    var result = service.apply(user("prepay:apply"), application("300"));
    assertThat(result).isEqualTo(expected);
    Write insert = write("INSERT INTO crm_prepay (");
    assertThat(insert.args())
        .contains(COMPANY, KOL, new BigDecimal("300.00"), UUID.fromString(UUID_TEXT));
    assertThat(insert.args()).contains("PP20261022000001");
    assertThat(insert.sql()).doesNotContain("deduct_from_commission", "payout_account_id");
    assertThat(write("INSERT INTO crm_prepay_history").args()).contains("SUBMITTED");
    assertThat(writes).noneMatch(write -> write.sql().contains("outbox"));
  }

  @Test
  void shouldReturnExistingTaskWithoutWriting_whenApplicationIsRetried() {
    rows(sql -> sql.contains("request_id = ?"), row("WAITING_DETAILS"));
    doReturn(detail("WAITING_DETAILS", null)).when(service).detail(any(), anyLong());
    assertThat(service.apply(user("prepay:apply"), application("300.00")).id()).isEqualTo(ID);
    assertThat(writes).isEmpty();
    verify(kols, never()).lockKol(anyLong());
  }

  @ParameterizedTest
  @ValueSource(strings = {"301", "299.99"})
  void shouldRejectChangedAmount_whenRequestIdIsReused(String amount) {
    rows(sql -> sql.contains("request_id = ?"), row("WAITING_DETAILS"));
    fails(
        () -> service.apply(user("prepay:apply"), application(amount)),
        ErrorCode.PREPAY_REQUEST_CONFLICT);
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldRejectChangedKol_whenRequestIdIsReused() {
    rows(sql -> sql.contains("request_id = ?"), row("WAITING_DETAILS"));
    fails(
        () ->
            service.apply(
                user("prepay:apply"), new PrepayService.PrepayApplication(UUID_TEXT, 23L, "300")),
        ErrorCode.PREPAY_REQUEST_CONFLICT);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"0", "-1", "01", "1.001", "1e2", "1000000000000", " 1", "NaN"})
  void shouldRejectInvalidMoneyBeforeWriting_whenApplicationAmountIsInvalid(String amount) {
    fails(
        () -> service.apply(user("prepay:apply"), application(amount)),
        ErrorCode.COMMON_VALIDATION_FAILED);
    assertThat(writes).isEmpty();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"invalid", "1-1-1-1-1"})
  void shouldRejectNonCanonicalRequestId_whenApplying(String requestId) {
    fails(
        () ->
            service.apply(
                user("prepay:apply"), new PrepayService.PrepayApplication(requestId, KOL, "300")),
        ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @ParameterizedTest
  @MethodSource("invalidKolIds")
  void shouldRejectInvalidKolId_whenApplying(Long kolId) {
    fails(
        () ->
            service.apply(
                user("prepay:apply"), new PrepayService.PrepayApplication(UUID_TEXT, kolId, "300")),
        ErrorCode.COMMON_VALIDATION_FAILED);
  }

  static Stream<Arguments> invalidKolIds() {
    return Stream.of(Arguments.of((Long) null), Arguments.of(0L), Arguments.of(-1L));
  }

  @Test
  void shouldRejectMissingOrUnassignedKol_whenApplying() {
    when(kols.lockKol(KOL)).thenReturn(false);
    fails(
        () -> service.apply(user("prepay:apply"), application("300")), ErrorCode.COMMON_NOT_FOUND);
    when(kols.lockKol(KOL)).thenReturn(true);
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of());
    fails(
        () -> service.apply(user("prepay:apply"), application("300")), ErrorCode.COMMON_NOT_FOUND);
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(99L)));
    fails(
        () -> service.apply(user("prepay:apply"), application("300")), ErrorCode.COMMON_NOT_FOUND);
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldRejectExhaustedDailySequence_whenApplying() {
    scalars.put("crm_prepay_sequence", null);
    fails(() -> service.apply(user("prepay:apply"), application("300")), ErrorCode.COMMON_CONFLICT);
  }

  @Test
  void shouldRejectLostInsertRaceWithoutExistingTask_whenApplying() {
    changed = 0;
    fails(
        () -> service.apply(user("prepay:apply"), application("300")),
        ErrorCode.PREPAY_STATE_CONFLICT);
  }

  @Test
  void shouldSaveExplicitFalseAndFreezeApprovedAccount_whenFinanceCompletesDetails() {
    when(accounts.selectById(33L)).thenReturn(account());
    doReturn(detail("WAITING_DETAILS", false)).when(service).detail(any(), anyLong());
    service.saveApplicationDetails(
        user("prepay:apply"),
        ID,
        details(false),
        List.of(new PrepayService.UploadedFile("proof.pdf", new byte[] {1})));
    Write update = write("UPDATE crm_prepay SET reason");
    assertThat(update.args())
        .contains("Business advance", false, 33L, "encrypted", "al***@example.test", COMPANY, ID);
    verify(files)
        .uploadPrepayAdmin(
            COMPANY, 5L, ID, FileBizType.PREPAY_ATTACHMENT, "proof.pdf", new byte[] {1});
    assertThat(write("INSERT INTO crm_prepay_history").args()).contains("DETAILS_COMPLETED");
    assertThat(write("INSERT INTO crm_prepay_outbox").args()).contains("SUBMITTED");
  }

  @ParameterizedTest
  @MethodSource("incompleteDetails")
  void shouldRejectIncompleteFinanceDetails_whenSaving(PrepayService.ApplicationDetails details) {
    fails(
        () -> service.saveApplicationDetails(user("prepay:apply"), ID, details, List.of()),
        ErrorCode.COMMON_VALIDATION_FAILED);
    assertThat(writes).isEmpty();
  }

  static Stream<Arguments> incompleteDetails() {
    LocalDate date = LocalDate.of(2026, 10, 23);
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of(new PrepayService.ApplicationDetails(null, date, 33L, true)),
        Arguments.of(new PrepayService.ApplicationDetails(" ", date, 33L, true)),
        Arguments.of(new PrepayService.ApplicationDetails("x".repeat(1001), date, 33L, true)),
        Arguments.of(new PrepayService.ApplicationDetails("reason", null, 33L, true)),
        Arguments.of(new PrepayService.ApplicationDetails("reason", date, null, true)),
        Arguments.of(new PrepayService.ApplicationDetails("reason", date, 33L, null)));
  }

  @Test
  void shouldRejectNonPendingTaskOrTooManyAttachments_whenCompletingDetails() {
    scalars.put("SELECT status", "APPROVED");
    fails(
        () -> service.saveApplicationDetails(user("prepay:apply"), ID, details(true), null),
        ErrorCode.PREPAY_STATE_CONFLICT);
    scalars.put("SELECT status", "WAITING_DETAILS");
    when(accounts.selectById(33L)).thenReturn(account());
    when(files.prepayAttachments(ID))
        .thenReturn(
            java.util.Collections.nCopies(
                5, new BizFileService.FileMeta(1L, "file", "image/png", 1L)));
    fails(
        () ->
            service.saveApplicationDetails(
                user("prepay:apply"),
                ID,
                details(true),
                List.of(new PrepayService.UploadedFile("extra.png", new byte[] {1}))),
        ErrorCode.COMMON_VALIDATION_FAILED);
    assertThat(writes).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 6})
  void shouldEnforceFiveNewAttachments_whenCompletingDetails(int count) {
    when(accounts.selectById(33L)).thenReturn(account());
    var attachments =
        java.util.Collections.nCopies(
            count, new PrepayService.UploadedFile("proof.pdf", new byte[] {1}));
    if (count == 6) {
      fails(
          () ->
              service.saveApplicationDetails(user("prepay:apply"), ID, details(false), attachments),
          ErrorCode.COMMON_VALIDATION_FAILED);
      assertThat(writes).isEmpty();
    } else {
      doReturn(detail("WAITING_DETAILS", false)).when(service).detail(any(), anyLong());
      service.saveApplicationDetails(user("prepay:apply"), ID, details(false), attachments);
      verify(files, org.mockito.Mockito.times(5))
          .uploadPrepayAdmin(
              COMPANY, 5L, ID, FileBizType.PREPAY_ATTACHMENT, "proof.pdf", new byte[] {1});
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "tenant", "nullTenant", "kol", "pending"})
  void shouldRejectUnreviewedOrForeignAccount_whenCompletingDetails(String condition) {
    var account = account();
    if (condition.equals("tenant")) {
      account.setTenantId(99L);
    }
    if (condition.equals("nullTenant")) {
      account.setTenantId(null);
    }
    if (condition.equals("kol")) {
      account.setKolId(99L);
    }
    if (condition.equals("pending")) {
      account.setStatus(PayoutAccountStatus.PENDING);
    }
    when(accounts.selectById(33L)).thenReturn(condition.equals("missing") ? null : account);
    fails(
        () -> service.saveApplicationDetails(user("prepay:apply"), ID, details(true), null),
        ErrorCode.COMMON_NOT_FOUND);
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldApproveOnlyCompleteDetails_whenReviewerApproves() {
    doReturn(detail("PENDING_OPERATIONS_REVIEW", true)).when(service).detail(any(), anyLong());
    service.approval(user("prepay:review"), ID, "APPROVE", null);
    assertThat(write("UPDATE crm_prepay SET status").args()).contains("PENDING_FINANCE_REVIEW");
    assertThat(writes).noneMatch(write -> write.sql().contains("outbox"));
  }

  @Test
  void shouldRequireFinanceReviewPermissionForSecondStage() {
    doReturn(detail("PENDING_FINANCE_REVIEW", true)).when(service).detail(any(), anyLong());
    service.approval(user("prepay:finance-review"), ID, "APPROVE", null);
    assertThat(write("UPDATE crm_prepay SET status").args()).contains("APPROVED");
    assertThat(write("INSERT INTO crm_prepay_outbox").args()).contains("APPROVED");
    doReturn(detail("PENDING_FINANCE_REVIEW", true)).when(service).detail(any(), anyLong());
    fails(
        () -> service.approval(user("prepay:review"), ID, "APPROVE", null),
        ErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  void shouldRejectAnotherActiveApplicationForTheSameKol() {
    rows(sql -> sql.contains("status IN ('WAITING_DETAILS'"), row("WAITING_DETAILS"));
    fails(
        () -> service.apply(user("prepay:apply"), application("300")),
        ErrorCode.PREPAY_STATE_CONFLICT);
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldRequireFinanceDetailsBeforeApproval_whenApplicationHasOnlyAmount() {
    doReturn(detail("PENDING_OPERATIONS_REVIEW", null)).when(service).detail(any(), anyLong());
    fails(
        () -> service.approval(user("prepay:review"), ID, "APPROVE", null),
        ErrorCode.PREPAY_DETAILS_REQUIRED);
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldRejectWithTrimmedReasonWithoutRequiringDetails_whenReviewerRejects() {
    doReturn(detail("PENDING_OPERATIONS_REVIEW", null)).when(service).detail(any(), anyLong());
    service.approval(user("prepay:review"), ID, "REJECT", "  Needs correction  ");
    assertThat(write("UPDATE crm_prepay SET status").args())
        .contains("REJECTED", "Needs correction");
    assertThat(write("INSERT INTO crm_prepay_outbox").args()).contains("REJECTED");
  }

  @Test
  void shouldRejectInvalidDecisionsOrMissingReasons_whenReviewing() {
    doReturn(detail("PENDING_OPERATIONS_REVIEW", true)).when(service).detail(any(), anyLong());
    fails(
        () -> service.approval(user("prepay:review"), ID, "OTHER", null),
        ErrorCode.COMMON_VALIDATION_FAILED);
    fails(
        () -> service.approval(user("prepay:review"), ID, "REJECT", null),
        ErrorCode.COMMON_VALIDATION_FAILED);
    fails(
        () -> service.approval(user("prepay:review"), ID, "REJECT", " "),
        ErrorCode.COMMON_VALIDATION_FAILED);
    doReturn(detail("PAID", true)).when(service).detail(any(), anyLong());
    fails(
        () -> service.approval(user("prepay:review"), ID, "APPROVE", null),
        ErrorCode.PREPAY_STATE_CONFLICT);
  }

  @Test
  void shouldRejectStateCompareAndSetFailure_whenApproving() {
    doReturn(detail("PENDING_OPERATIONS_REVIEW", true)).when(service).detail(any(), anyLong());
    changed = 0;
    fails(
        () -> service.approval(user("prepay:review"), ID, "APPROVE", null),
        ErrorCode.PREPAY_STATE_CONFLICT);
    assertThat(writes).hasSize(1);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "  No longer needed  "})
  void shouldCancelOwnPendingApplication_whenApplicantStillOwnsKol(String reason) {
    doReturn(detail("WAITING_DETAILS", null)).when(service).detail(any(), anyLong());
    service.cancel(user("prepay:apply"), ID, reason);
    assertThat(write("UPDATE crm_prepay SET status").sql()).contains("status = 'CANCELLED'");
    assertThat(write("INSERT INTO crm_prepay_history").args()).contains("CANCELLED");
    assertThat(writes).noneMatch(w -> w.sql().contains("outbox"));
  }

  @Test
  void shouldRejectCancellationByDifferentApplicantOrAfterApproval() {
    doReturn(detail("APPROVED", true)).when(service).detail(any(), anyLong());
    fails(() -> service.cancel(user("prepay:apply"), ID, null), ErrorCode.PREPAY_STATE_CONFLICT);
    doReturn(detail("PENDING_OPERATIONS_REVIEW", true)).when(service).detail(any(), anyLong());
    fails(
        () ->
            service.cancel(
                accountUser(6L, Role.STAFF, DataScope.OWN, COMPANY, "prepay:apply"), ID, null),
        ErrorCode.PREPAY_STATE_CONFLICT);
    changed = 0;
    fails(() -> service.cancel(user("prepay:apply"), ID, null), ErrorCode.PREPAY_STATE_CONFLICT);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldRecordOnePaymentAndDeductOnlyWhenExplicit_whenPaymentIsRegistered(boolean deduct) {
    doReturn(detail("APPROVED", deduct)).when(service).detail(any(), anyLong());
    when(files.uploadPrepayAdmin(
            COMPANY, 5L, ID, FileBizType.PREPAY_VOUCHER, "receipt.pdf", new byte[] {1, 2}))
        .thenReturn(88L);
    service.pay(user("prepay:pay"), ID, payment("300", null), voucher());
    assertThat(write("INSERT INTO crm_prepay_payment").args())
        .contains(new BigDecimal("300.00"), 88L, COMPANY, ID);
    assertThat(write("UPDATE crm_prepay SET status").sql()).contains("status = 'PAID'");
    if (deduct) {
      verify(wallet)
          .postPrepayDeduction(
              COMPANY,
              KOL,
              ID,
              new BigDecimal("300.00"),
              Instant.parse("2026-10-22T04:00:00Z"),
              5L);
    } else {
      verify(wallet, never())
          .postPrepayDeduction(anyLong(), anyLong(), anyLong(), any(), any(), anyLong());
    }
    assertThat(write("INSERT INTO crm_prepay_outbox").args()).contains("PAID");
  }

  @Test
  void shouldAcceptDifferentPaymentAmountOnlyWithReason() {
    doReturn(detail("APPROVED", false)).when(service).detail(any(), anyLong());
    fails(
        () -> service.pay(user("prepay:pay"), ID, payment("299", null), voucher()),
        ErrorCode.COMMON_VALIDATION_FAILED);
    fails(
        () -> service.pay(user("prepay:pay"), ID, payment("299", " "), voucher()),
        ErrorCode.COMMON_VALIDATION_FAILED);
    service.pay(user("prepay:pay"), ID, payment("299", "Fee agreed"), voucher());
    assertThat(write("INSERT INTO crm_prepay_payment").args())
        .contains(new BigDecimal("299.00"), "Fee agreed");
  }

  @Test
  void shouldRejectUnapprovedOrIncompletePayment() {
    doReturn(detail("WAITING_DETAILS", true)).when(service).detail(any(), anyLong());
    fails(
        () -> service.pay(user("prepay:pay"), ID, payment("300", null), voucher()),
        ErrorCode.PREPAY_STATE_CONFLICT);
    doReturn(detail("APPROVED", null)).when(service).detail(any(), anyLong());
    fails(
        () -> service.pay(user("prepay:pay"), ID, payment("300", null), voucher()),
        ErrorCode.PREPAY_DETAILS_REQUIRED);
    doReturn(detail("APPROVED", true)).when(service).detail(any(), anyLong());
    changed = 0;
    fails(
        () -> service.pay(user("prepay:pay"), ID, payment("300", null), voucher()),
        ErrorCode.PREPAY_STATE_CONFLICT);
    verify(wallet, never())
        .postPrepayDeduction(anyLong(), anyLong(), anyLong(), any(), any(), anyLong());
  }

  @Test
  void shouldRetryIdenticalPaymentWithoutWritingAndRejectChangedVoucher() {
    doReturn(detail("APPROVED", true)).when(service).detail(any(), anyLong());
    service.pay(user("prepay:pay"), ID, payment("300", null), voucher());
    String fingerprint = (String) write("INSERT INTO crm_prepay_payment").args().get(4);
    fixtures.addFirst(
        new Rows(
            sql -> sql.contains("request_fingerprint FROM"), List.of(Map.of("1", fingerprint))));
    writes.clear();
    service.pay(user("prepay:pay"), ID, payment("300.00", null), voucher());
    assertThat(writes).isEmpty();
    fails(
        () ->
            service.pay(
                user("prepay:pay"),
                ID,
                payment("300", null),
                new PrepayService.UploadedFile("receipt.pdf", new byte[] {9})),
        ErrorCode.PREPAY_PAYMENT_CONFLICT);
  }

  @ParameterizedTest
  @MethodSource("invalidPayments")
  void shouldRejectMalformedPaymentBeforeWriting(
      PrepayService.PaymentCommand command, PrepayService.UploadedFile file) {
    fails(
        () -> service.pay(user("prepay:pay"), ID, command, file),
        ErrorCode.COMMON_VALIDATION_FAILED);
    assertThat(writes).isEmpty();
  }

  static Stream<Arguments> invalidPayments() {
    return Stream.of(
        Arguments.of(
            new PrepayService.PaymentCommand(UUID_TEXT, "300", "bad-time", "BANK", null),
            voucher()),
        Arguments.of(
            new PrepayService.PaymentCommand(UUID_TEXT, "300", "2026-10-22T04:00:00", "BANK", null),
            voucher()),
        Arguments.of(
            new PrepayService.PaymentCommand(UUID_TEXT, "300", "2026-10-22T04:00:00Z", null, null),
            voucher()),
        Arguments.of(
            new PrepayService.PaymentCommand(UUID_TEXT, "300", "2026-10-22T04:00:00Z", " ", null),
            voucher()),
        Arguments.of(
            new PrepayService.PaymentCommand(
                UUID_TEXT, "300", "2026-10-22T04:00:00Z", "x".repeat(101), null),
            voucher()),
        Arguments.of(payment("300", "x".repeat(1001)), voucher()),
        Arguments.of(payment("300", null), null),
        Arguments.of(payment("300", null), new PrepayService.UploadedFile("receipt.pdf", null)));
  }

  @Test
  void shouldReturnOriginalPaymentAfterWaitingForConcurrentDuplicate() {
    doReturn(detail("APPROVED", true)).when(service).detail(any(), anyLong());
    service.pay(user("prepay:pay"), ID, payment("300", null), voucher());
    String fingerprint = (String) write("INSERT INTO crm_prepay_payment").args().get(4);
    writes.clear();
    doReturn(detail("PAID", true)).when(service).detail(any(), anyLong());
    var fingerprintRows = new ArrayList<Map<String, Object>>();
    var fingerprintReads = new java.util.concurrent.atomic.AtomicInteger();
    fixtures.addFirst(
        new Rows(
            sql -> {
              if (!sql.contains("request_fingerprint FROM")) {
                return false;
              }
              // The competing transaction commits while this request waits for the prepay row lock.
              if (fingerprintReads.incrementAndGet() == 2) {
                fingerprintRows.add(Map.of("1", fingerprint));
              }
              return true;
            },
            fingerprintRows));

    assertThat(service.pay(user("prepay:pay"), ID, payment("300", null), voucher()).status())
        .isEqualTo("PAID");
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldRejectOverlongRejectionReasonBeforeDatabaseWrite() {
    doReturn(detail("PENDING_OPERATIONS_REVIEW", true)).when(service).detail(any(), anyLong());
    fails(
        () -> service.approval(user("prepay:review"), ID, "REJECT", "x".repeat(1001)),
        ErrorCode.COMMON_VALIDATION_FAILED);
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldRejectOverlongCancellationReasonBeforeDatabaseWrite() {
    doReturn(detail("WAITING_DETAILS", true)).when(service).detail(any(), anyLong());
    fails(
        () -> service.cancel(user("prepay:apply"), ID, "x".repeat(1001)),
        ErrorCode.COMMON_VALIDATION_FAILED);
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldEncryptManualRecipientAndSnapshotBusinessIdentityWithoutAnAccountArchive() {
    String recipient = "银行转账\n张三\n账号123456789\nCNY";
    when(cipher.encrypt(recipient)).thenReturn("v1:manual-encrypted");
    doReturn(detail("PENDING_OPERATIONS_REVIEW", false)).when(service).detail(any(), anyLong());
    service.saveApplicationDetails(
        user("prepay:apply"),
        ID,
        new PrepayService.ApplicationDetails(
            "reason", LocalDate.of(2026, 10, 23), null, false, recipient),
        List.of());
    assertThat(write("UPDATE crm_prepay SET reason").args())
        .contains("MANUAL", "v1:manual-encrypted", false)
        .doesNotContain(recipient);
    verify(accounts, never()).selectById(anyLong());
    assertThat(write("INSERT INTO crm_prepay_history").args()).contains("BUSINESS", "Test");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\n"})
  void shouldRejectMissingManualRecipient(String recipient) {
    fails(
        () ->
            service.saveApplicationDetails(
                user("prepay:apply"),
                ID,
                new PrepayService.ApplicationDetails(
                    "reason", LocalDate.of(2026, 10, 23), null, false, recipient),
                List.of()),
        ErrorCode.COMMON_VALIDATION_FAILED);
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldRejectConflictingRecipientInputsAndOverlongText() {
    for (var request :
        List.of(
            new PrepayService.ApplicationDetails(
                "reason", LocalDate.of(2026, 10, 23), 33L, true, "manual"),
            new PrepayService.ApplicationDetails(
                "reason", LocalDate.of(2026, 10, 23), null, true, "x".repeat(1001)))) {
      fails(
          () -> service.saveApplicationDetails(user("prepay:apply"), ID, request, List.of()),
          ErrorCode.COMMON_VALIDATION_FAILED);
    }
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldEnforceOwnScopeBeforeDecryptingRecipient() {
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(999L)));
    fails(() -> service.recipient(user("commission:view"), ID), ErrorCode.COMMON_NOT_FOUND);
    verify(cipher, never()).decrypt(any());
    verify(audit, never()).recordTenantOperation(anyLong(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void shouldAuditAndDecryptOnlyFrozenRecipient_whenPayerViewsAccount() {
    rows(
        sql -> sql.contains("SELECT payout_account_type"),
        Map.of(
            "payout_account_type",
            "PAYPAL",
            "payout_currency",
            "CNY",
            "payout_holder_name",
            "Test recipient",
            "payout_account_cipher",
            "encrypted",
            "prepay_no",
            "PP-test"));
    when(cipher.decrypt("encrypted")).thenReturn("test@example.invalid");
    assertThat(service.recipient(user("prepay:pay"), ID).account())
        .isEqualTo("test@example.invalid");
    verify(audit)
        .recordTenantOperation(
            COMPANY,
            5L,
            "Prepay",
            "View recipient",
            "/admin-api/commission/prepays/77/recipient",
            "{\"prepayNo\":\"PP-test\"}");
  }

  @Test
  void shouldReturnNotFoundInsteadOfDatabaseError_whenRecipientIsMissingOrForeign() {
    fails(() -> service.recipient(user("prepay:pay"), ID), ErrorCode.COMMON_NOT_FOUND);
    verify(cipher, never()).decrypt(any());
  }

  @Test
  void shouldProjectCompletePaidDetailWithRecoveryAndMaskedAccount() {
    fixtures.clear();
    rows(sql -> sql.contains("JOIN crm_kol"), row("PAID"));
    rows(sql -> sql.startsWith("SELECT reason"), appValues(true));
    rows(
        sql -> sql.contains("action IN"),
        Map.of("action", "APPROVED", "operator_id", 6L, "create_time", Timestamp.from(NOW)));
    rows(
        sql -> sql.contains("FROM crm_prepay_history"),
        Map.of("action", "PAID", "operator_id", 7L, "create_time", Timestamp.from(NOW)));
    rows(
        sql -> sql.startsWith("SELECT pay_amount_cny"),
        Map.of(
            "pay_amount_cny",
            new BigDecimal("300.00"),
            "pay_time",
            Timestamp.from(NOW),
            "pay_method",
            "BANK",
            "voucher_file_id",
            88L,
            "operator_id",
            7L));
    rows(
        sql -> sql.startsWith("SELECT p.pay_amount_cny AS posted"),
        Map.of("posted", new BigDecimal("300.00"), "ledger_id", 99L));
    var result = service.detail(user("prepay:pay"), ID);
    assertThat(result.payoutAccountSnapshot().maskedAccount()).isEqualTo("al***@example.test");
    assertThat(result.approval().decision()).isEqualTo("APPROVE");
    assertThat(result.payment().voucherFileId()).isEqualTo(88L);
    // 300 posted - 100 recovered = 200 still outstanding.
    assertThat(result.deduction())
        .isEqualTo(new PrepayService.Deduction("300.00", "100.00", "200.00"));
    assertThat(result.walletBalanceCny()).isEqualTo("-200.00");
    assertThat(result.timeline())
        .extracting(PrepayService.Timeline::action)
        .containsExactly("PAID");
  }

  @Test
  void shouldReturnNullOptionalDetails_whenPendingAndFinanceHasNotCompletedFields() {
    rows(sql -> sql.startsWith("SELECT reason"), Map.of());
    var result = service.detail(user("commission:view"), ID);
    assertThat(result.payoutAccountSnapshot()).isNull();
    assertThat(result.expectedPayDate()).isNull();
    assertThat(result.approval()).isNull();
    assertThat(result.payment()).isNull();
    assertThat(result.deduction()).isNull();
    assertThat(result.financeTaskId()).isEqualTo(ID);
  }

  @Test
  void shouldMapRejectedApprovalAndSkipDeductionForNonDeductingPaidTask() {
    fixtures.clear();
    rows(sql -> sql.contains("JOIN crm_kol"), row("PAID"));
    rows(sql -> sql.startsWith("SELECT reason"), appValues(false));
    rows(
        sql -> sql.contains("action IN"),
        Map.of(
            "action",
            "REJECTED",
            "operator_id",
            6L,
            "create_time",
            Timestamp.from(NOW),
            "reason",
            "Correction"));
    var result = service.detail(user("prepay:review"), ID);
    assertThat(result.approval().decision()).isEqualTo("REJECT");
    assertThat(result.deduction()).isNull();
  }

  @Test
  void shouldHideMissingTaskAndKolOutsideCurrentScope() {
    fixtures.clear();
    fails(() -> service.detail(user("commission:view"), ID), ErrorCode.COMMON_NOT_FOUND);
    fails(() -> service.cancel(user("prepay:apply"), ID, null), ErrorCode.COMMON_NOT_FOUND);
    rows(sql -> sql.contains("JOIN crm_kol"), row("WAITING_DETAILS"));
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(99L)));
    fails(() -> service.detail(user("commission:view"), ID), ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldListFinanceTasksAcrossBdScopeButBindCompanyAndFilters() {
    when(kols.getByCode("KOL-test")).thenReturn(kol(5L));
    var result =
        service.page(
            user("prepay:pay"), new PrepayService.Filter(" PP-test ", " KOL-test ", "PAID", 2, 10));
    assertThat(result.page()).isEqualTo(2);
    assertThat(result.items()).hasSize(1);
    assertThat(pageCalls)
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.company()).isEqualTo(COMPANY);
              assertThat(call.query().prepayNo()).isEqualTo("PP-test");
              assertThat(call.query().kolId()).isEqualTo(KOL);
              assertThat(call.query().status()).isEqualTo("PAID");
              assertThat(call.query().kolIds()).isNull();
              assertThat(call.query().limit()).isEqualTo(10);
              assertThat(call.query().offset()).isEqualTo(10L);
            });
    verify(kols, never()).listByBdUserId(anyLong());
  }

  @Test
  void shouldReturnEmptyPage_whenKolCodeCaseDiffersFromStoredCode() {
    when(kols.getByCode("kol-test")).thenReturn(kol(5L));
    var result =
        service.page(user("prepay:pay"), new PrepayService.Filter(null, "kol-test", null, 1, 20));
    assertThat(result.total()).isZero();
    assertThat(result.items()).isEmpty();
    assertThat(pageCalls).isEmpty();
  }

  @Test
  void shouldRestrictBusinessListToCurrentKolAssignmentsAndHandleEmptyScope() {
    var filter = new PrepayService.Filter(null, null, null, 1, 20);
    assertThat(service.page(user("commission:view"), filter).total()).isZero();
    when(kols.listByBdUserId(5L)).thenReturn(List.of(kol(5L), kol(5L)));
    assertThat(service.page(user("commission:view"), filter).items()).hasSize(1);
    assertThat(reads).anyMatch(sql -> sql.contains("p.kol_id IN (?)"));
    scalars.put("SELECT count(*)", null);
    assertThat(
            service
                .page(
                    accountUser(5L, Role.STAFF, DataScope.ALL, COMPANY, "commission:view"),
                    new PrepayService.Filter(" ", " ", " ", 1, 20))
                .total())
        .isZero();
  }

  @ParameterizedTest
  @MethodSource("badFilters")
  void shouldRejectInvalidPageOrStatus(PrepayService.Filter filter) {
    fails(() -> service.page(user("prepay:review"), filter), ErrorCode.COMMON_VALIDATION_FAILED);
  }

  static Stream<Arguments> badFilters() {
    return Stream.of(
        Arguments.of(new PrepayService.Filter(null, null, null, 0, 20)),
        Arguments.of(new PrepayService.Filter(null, null, null, 1, 0)),
        Arguments.of(new PrepayService.Filter(null, null, null, 1, 101)),
        Arguments.of(new PrepayService.Filter(null, null, "UNKNOWN", 1, 20)));
  }

  @Test
  void shouldExportEscapedMaskedFieldsAndAuditBothPages() {
    var unsafeRow =
        new PrepayService.PrepayRow(
            ID,
            "PP-test",
            "PREPAY",
            ID,
            KOL,
            "KOL-test",
            " =SUM(1,2)\"",
            5L,
            "Business operator",
            "300.00",
            "CNY",
            "PAID",
            NOW);
    doReturn(new PageResult<>(List.of(unsafeRow), 101, 1, 100)).when(service).page(any(), any());
    rows(
        sql -> sql.startsWith("SELECT pay_amount_cny"),
        Map.of(
            "pay_amount_cny",
            new BigDecimal("300"),
            "pay_time",
            Timestamp.from(NOW),
            "pay_method",
            "BANK"));
    rows(sql -> sql.startsWith("SELECT payout_account_masked"), Map.of("1", "al***@example.test"));
    String csv =
        new String(
            service.export(
                user("data:export", "prepay:pay"),
                new PrepayService.Filter(null, null, null, 1, 20)),
            StandardCharsets.UTF_8);
    assertThat(csv)
        .startsWith("\uFEFF")
        .contains("\"' =SUM(1,2)\"\"\"", "al***@example.test", "300.00");
    verify(service)
        .page(
            any(),
            org.mockito.ArgumentMatchers.eq(new PrepayService.Filter(null, null, null, 2, 100)));
    verify(audit)
        .recordTenantOperation(
            COMPANY,
            5L,
            "Prepay",
            "Export tasks",
            "/admin-api/commission/prepays/export",
            "{\"rowCount\":101}");
  }

  @Test
  void shouldLimitExportSizeAndSupportEmptyOptionalPaymentFields() {
    var filter = new PrepayService.Filter(null, null, null, 1, 20);
    doReturn(new PageResult<>(List.of(), 100001, 1, 100)).when(service).page(any(), any());
    fails(
        () -> service.export(user("data:export", "commission:view"), filter),
        ErrorCode.COMMON_VALIDATION_FAILED);
    doReturn(new PageResult<>(List.of(prepayRow("WAITING_DETAILS")), 1, 1, 100))
        .when(service)
        .page(any(), any());
    assertThat(
            new String(
                service.export(user("data:export", "commission:view"), filter),
                StandardCharsets.UTF_8))
        .contains("WAITING_DETAILS", "\"\",\"\",\"\",\"\"");
  }

  @Test
  void shouldShowOnlyApprovedPayoutOptionsAndUseFinanceScope() {
    var paypal = account();
    var usdt = account();
    usdt.setAccountType(PayoutAccountType.USDT);
    usdt.setUsdtNetwork(UsdtNetwork.TRC20);
    when(accounts.selectList(any())).thenReturn(List.of(paypal, usdt));
    assertThat(service.payoutOptions(user("prepay:apply"), KOL))
        .extracting(PrepayService.PayoutOption::network)
        .containsExactly(null, "TRC20");
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(99L)));
    fails(() -> service.payoutOptions(user("prepay:apply"), KOL), ErrorCode.COMMON_NOT_FOUND);
    assertThat(service.payoutOptions(user("prepay:review"), KOL)).hasSize(2);
  }

  @Test
  void shouldAuthorizeAdminFilesAgainstCurrentTaskAndBd() {
    var file = file();
    when(files.attachedFile(88L))
        .thenReturn(new BizFileService.AttachedFile(FileBizType.PREPAY_ATTACHMENT, ID, file));
    assertThat(service.adminFile(user("commission:view"), 88L)).isEqualTo(file);
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(99L)));
    fails(() -> service.adminFile(user("commission:view"), 88L), ErrorCode.COMMON_NOT_FOUND);
    assertThat(service.adminFile(user("prepay:pay"), 88L)).isEqualTo(file);
    fixtures.clear();
    fails(() -> service.adminFile(user("prepay:pay"), 88L), ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldDenyUnrelatedOrUnattachedFilesToBothRoles() {
    when(files.attachedFile(88L))
        .thenReturn(new BizFileService.AttachedFile(FileBizType.PAYOUT_QR, ID, file()));
    fails(() -> service.adminFile(user("prepay:review"), 88L), ErrorCode.COMMON_NOT_FOUND);
    fails(() -> service.kolFile(KOL, 88L), ErrorCode.COMMON_NOT_FOUND);
    when(files.attachedFile(88L))
        .thenReturn(new BizFileService.AttachedFile(FileBizType.PREPAY_VOUCHER, null, file()));
    fails(() -> service.adminFile(user("prepay:review"), 88L), ErrorCode.COMMON_NOT_FOUND);
    fails(() -> service.kolFile(KOL, 88L), ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldAllowOnlyOwnPaidVoucherToKol() {
    when(files.attachedFile(88L))
        .thenReturn(new BizFileService.AttachedFile(FileBizType.PREPAY_VOUCHER, ID, file()));
    try (var ignored = TenantScope.openCompany(COMPANY)) {
      fails(() -> service.kolFile(KOL, 88L), ErrorCode.COMMON_NOT_FOUND);
      rows(sql -> sql.startsWith("SELECT p.kol_id"), Map.of("1", KOL));
      assertThat(service.kolFile(KOL, 88L).id()).isEqualTo(88L);
      fails(() -> service.kolFile(99L, 88L), ErrorCode.COMMON_NOT_FOUND);
    }
  }

  @Test
  void shouldEnforceDistinctPermissionsInService() {
    var noAccess = user();
    fails(() -> service.apply(noAccess, application("300")), ErrorCode.AUTH_FORBIDDEN);
    fails(
        () -> service.approval(user("prepay:pay"), ID, "APPROVE", null), ErrorCode.AUTH_FORBIDDEN);
    fails(
        () -> service.pay(user("prepay:review"), ID, payment("300", null), voucher()),
        ErrorCode.AUTH_FORBIDDEN);
    fails(() -> service.recipient(noAccess, ID), ErrorCode.AUTH_FORBIDDEN);
    fails(() -> service.cancel(noAccess, ID, null), ErrorCode.AUTH_FORBIDDEN);
    fails(
        () -> service.saveApplicationDetails(noAccess, ID, details(true), null),
        ErrorCode.AUTH_FORBIDDEN);
    fails(() -> service.payoutOptions(noAccess, KOL), ErrorCode.AUTH_FORBIDDEN);
    fails(() -> service.adminFile(noAccess, 88L), ErrorCode.AUTH_FORBIDDEN);
    fails(
        () -> service.page(noAccess, new PrepayService.Filter(null, null, null, 1, 20)),
        ErrorCode.AUTH_FORBIDDEN);
    fails(
        () -> service.export(user("prepay:pay"), new PrepayService.Filter(null, null, null, 1, 20)),
        ErrorCode.AUTH_FORBIDDEN);
    fails(
        () ->
            service.export(user("data:export"), new PrepayService.Filter(null, null, null, 1, 20)),
        ErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  void shouldRejectPlatformAndCompanylessAccountsAndAllowCompanySuperAdmin() {
    fails(
        () ->
            service.page(
                accountUser(5L, Role.PLATFORM_ADMIN, DataScope.ALL, COMPANY),
                new PrepayService.Filter(null, null, null, 1, 20)),
        ErrorCode.AUTH_FORBIDDEN);
    fails(
        () -> service.detail(accountUser(5L, Role.STAFF, DataScope.ALL, null), ID),
        ErrorCode.AUTH_FORBIDDEN);
    fails(() -> service.detail(null, ID), ErrorCode.AUTH_FORBIDDEN);
    doReturn(new PageResult<>(List.of(), 0, 1, 100)).when(service).page(any(), any());
    assertThat(
            service.export(
                accountUser(5L, Role.SUPER_ADMIN, DataScope.ALL, COMPANY),
                new PrepayService.Filter(null, null, null, 1, 20)))
        .isNotEmpty();
  }

  private static AccountDTO user(String... permissions) {
    return accountUser(5L, Role.STAFF, DataScope.OWN, COMPANY, permissions);
  }

  private static AccountDTO accountUser(
      Long id, Role role, DataScope scope, Long company, String... permissions) {
    return new AccountDTO(
        id,
        "t47_user",
        null,
        "Test",
        "test@example.invalid",
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

  private static KolRefDTO kol(Long bd) {
    return new KolRefDTO(KOL, "KOL-test", "Test creator", true, bd);
  }

  private static PrepayService.PrepayApplication application(String amount) {
    return new PrepayService.PrepayApplication(UUID_TEXT, KOL, amount);
  }

  private static PrepayService.ApplicationDetails details(boolean deduct) {
    return new PrepayService.ApplicationDetails(
        "  Business advance  ", LocalDate.of(2026, 10, 23), 33L, deduct);
  }

  private static PrepayService.PaymentCommand payment(String amount, String remark) {
    return new PrepayService.PaymentCommand(
        UUID_TEXT, amount, "2026-10-22T12:00:00+08:00", " BANK ", remark);
  }

  private static PrepayService.UploadedFile voucher() {
    return new PrepayService.UploadedFile("receipt.pdf", new byte[] {1, 2});
  }

  private static BizFileService.FileDTO file() {
    return new BizFileService.FileDTO(88L, "receipt.pdf", "application/pdf", 1L, new byte[] {1});
  }

  private static PayoutAccountDO account() {
    var value = new PayoutAccountDO();
    value.setId(33L);
    value.setTenantId(COMPANY);
    value.setKolId(KOL);
    value.setAccountType(PayoutAccountType.PAYPAL);
    value.setCurrency("CNY");
    value.setAccountCipher("encrypted");
    value.setAccountMasked("al***@example.test");
    value.setHolderName("Test recipient");
    value.setStatus(PayoutAccountStatus.APPROVED);
    return value;
  }

  private static PrepayService.PrepayRow prepayRow(String status) {
    return new PrepayService.PrepayRow(
        ID,
        "PP-test",
        "PREPAY",
        ID,
        KOL,
        "KOL-test",
        "Test creator",
        5L,
        "Business operator",
        "300.00",
        "CNY",
        status,
        NOW);
  }

  private static PrepayService.PrepayDetail detail(String status, Boolean deduct) {
    return new PrepayService.PrepayDetail(
        ID,
        "PP-test",
        "PREPAY",
        ID,
        KOL,
        "KOL-test",
        "Test creator",
        5L,
        "Business operator",
        "300.00",
        "CNY",
        status,
        NOW,
        deduct == null ? null : "Business advance",
        deduct == null ? null : LocalDate.of(2026, 10, 23),
        deduct,
        deduct == null ? null : 33L,
        deduct == null
            ? null
            : new PrepayService.PayoutOption(
                33L, "PAYPAL", "CNY", null, "al***@example.test", "Test recipient"),
        List.of(),
        null,
        null,
        List.of(),
        null,
        "0.00");
  }

  private static Map<String, Object> row(String status) {
    return Map.of(
        "id",
        ID,
        "prepay_no",
        "PP-test",
        "kol_id",
        KOL,
        "kol_code",
        "KOL-test",
        "kol_name",
        "Test creator",
        "applicant_id",
        5L,
        "amount_cny",
        new BigDecimal("300.00"),
        "status",
        status,
        "create_time",
        Timestamp.from(NOW));
  }

  private static Map<String, Object> appValues(Boolean deduct) {
    return Map.of(
        "reason",
        "Business advance",
        "expected_pay_date",
        java.sql.Date.valueOf("2026-10-23"),
        "deduct_from_commission",
        deduct,
        "payout_account_id",
        33L,
        "payout_account_type",
        "PAYPAL",
        "payout_currency",
        "CNY",
        "payout_holder_name",
        "Test recipient",
        "payout_account_masked",
        "al***@example.test");
  }

  private void rows(Predicate<String> sql, Map<String, Object> data) {
    fixtures.add(new Rows(sql, List.of(data)));
  }

  private Write write(String prefix) {
    return writes.stream().filter(w -> w.sql().startsWith(prefix)).findFirst().orElseThrow();
  }

  private static void fails(Runnable action, ErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
  }

  private Object answer(InvocationOnMock invocation) {
    return switch (invocation.getMethod().getName()) {
      case "findByRequestId" -> headOrNull(dataFor(REQUEST_SQL));
      case "findById" -> headOrNull(dataFor(ROW_SQL));
      case "findActiveId", "findLatestActiveId" -> idOrNull(dataFor(ACTIVE_SQL));
      case "statusOf" -> scalar("SELECT status FROM crm_prepay WHERE id = ?", "WAITING_DETAILS");
      case "count" -> scalar("SELECT count(*) FROM crm_prepay", 1L);
      case "nextSequence" -> scalar("INSERT INTO crm_prepay_sequence (id, sequence_date)", 1);
      case "page" -> page(invocation.getArgument(0));
      case "loadApplication" -> application(dataFor(APPLICATION_SQL));
      case "latestApproval" -> historyOrNull(dataFor(APPROVAL_SQL));
      case "timeline" -> dataFor(TIMELINE_SQL).stream().map(this::history).toList();
      case "findPayment" -> paymentOrNull(dataFor(PAYMENT_SQL));
      case "findDeduction" -> deductionOrNull(dataFor(DEDUCTION_SQL));
      case "sumRecovered" -> new BigDecimal("100.00");
      case "findRecipient" -> recipientOrNull(dataFor(RECIPIENT_SQL));
      case "maskedAccount" -> textOrNull(dataFor(MASKED_SQL));
      case "paidKolId" -> idOrNull(dataFor(KOL_FILE_SQL), "1");
      case "findFingerprint" -> textOrNull(dataFor(FINGERPRINT_SQL));
      case "lockById" -> 1L;
      case "insertWaiting" -> insertWaiting(invocation.getArgument(0));
      case "completeDetails" -> completeDetails(invocation.getArgument(0));
      case "decide" -> decide(invocation.getArgument(0));
      case "cancel" -> cancel(invocation.getArgument(0));
      case "markPaid" -> markPaid(invocation.getArgument(0));
      case "insertHistory" -> historyWrite(invocation.getArgument(0));
      case "insertOutbox" -> outboxWrite(invocation.getArgument(0));
      case "insertPayment" -> paymentWrite(invocation.getArgument(0));
      default -> defaultValue(invocation);
    };
  }

  private List<PrepayHead> page(PrepayPageQuery query) {
    pageCalls.add(new PageCall(TenantScope.companyId(), query));
    if (query.kolIds() != null) {
      reads.add("SELECT id FROM crm_prepay WHERE p.kol_id IN (?)");
    }
    return dataFor(ROW_SQL).stream().map(this::head).toList();
  }

  private int insertWaiting(PrepayWrite.Insert row) {
    writes.add(
        new Write(
            "INSERT INTO crm_prepay (id, tenant_id, kol_id, prepay_no, request_id, applicant_id, amount_cny, status, create_by, create_time, update_by, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, 'WAITING_DETAILS', ?, ?, ?, ?) ON CONFLICT (tenant_id, request_id) DO NOTHING",
            Arrays.asList(
                row.id(),
                company(),
                row.kolId(),
                row.prepayNo(),
                row.requestId(),
                row.applicantId(),
                row.amount(),
                row.actorId(),
                row.now(),
                row.actorId(),
                row.now())));
    return changed;
  }

  private int completeDetails(PrepayWrite.Details row) {
    writes.add(
        new Write(
            "UPDATE crm_prepay SET reason = ?, expected_pay_date = ?, deduct_from_commission = ?, payout_account_id = ?, payout_account_type = ?, payout_currency = ?, payout_network = ?, payout_holder_name = ?, payout_account_cipher = ?, payout_account_masked = ?, status = 'PENDING_OPERATIONS_REVIEW', update_by = ?, update_time = ? WHERE id = ? AND status = 'WAITING_DETAILS'",
            Arrays.asList(
                row.reason(),
                row.expectedPayDate(),
                row.deductFromCommission(),
                row.payoutAccountId(),
                row.payoutAccountType(),
                row.payoutCurrency(),
                row.payoutNetwork(),
                row.payoutHolderName(),
                row.payoutAccountCipher(),
                row.payoutAccountMasked(),
                row.actorId(),
                row.now(),
                company(),
                row.id())));
    return 1;
  }

  private int decide(PrepayWrite.Decision row) {
    writes.add(
        new Write(
            "UPDATE crm_prepay SET status = ?, approved_by = CASE WHEN ? = 'APPROVED' THEN ? ELSE approved_by END, approved_time = CASE WHEN ? = 'APPROVED' THEN ? ELSE approved_time END, approval_reason = ?, update_by = ?, update_time = ? WHERE id = ? AND status = ?",
            Arrays.asList(
                row.next(),
                row.next(),
                row.actorId(),
                row.next(),
                row.now(),
                row.reason(),
                row.actorId(),
                row.now(),
                company(),
                row.id(),
                row.current())));
    return changed;
  }

  private int cancel(PrepayWrite.Cancel row) {
    writes.add(
        new Write(
            "UPDATE crm_prepay SET status = 'CANCELLED', cancelled_by = ?, cancelled_time = ?, cancel_reason = ?, update_by = ?, update_time = ? WHERE id = ? AND status = 'WAITING_DETAILS'",
            Arrays.asList(
                row.actorId(), row.now(), row.reason(), row.actorId(), row.now(), row.id())));
    return changed;
  }

  private int markPaid(PrepayWrite.Paid row) {
    writes.add(
        new Write(
            "UPDATE crm_prepay SET status = 'PAID', update_by = ?, update_time = ? WHERE id = ? AND status = 'APPROVED'",
            Arrays.asList(row.actorId(), row.now(), row.id())));
    return changed;
  }

  private int historyWrite(PrepayWrite.History row) {
    writes.add(
        new Write(
            "INSERT INTO crm_prepay_history (id, tenant_id, prepay_id, action, operator_id, reason, create_time, operator_name, operator_role) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Arrays.asList(
                row.id(),
                company(),
                row.prepayId(),
                row.action(),
                row.operatorId(),
                row.reason(),
                row.createTime(),
                row.operatorName(),
                row.operatorRole())));
    return 1;
  }

  private int outboxWrite(PrepayWrite.Outbox row) {
    writes.add(
        new Write(
            "INSERT INTO crm_prepay_outbox (id, tenant_id, prepay_id, event_type, payload, attempts, next_attempt_at, create_time) VALUES (?, ?, ?, ?, CAST(? AS jsonb), 0, ?, ?)",
            Arrays.asList(
                row.id(),
                company(),
                row.prepayId(),
                row.eventType(),
                row.payload(),
                row.nextAttemptAt(),
                row.createTime())));
    return 1;
  }

  private int paymentWrite(PrepayWrite.Payment row) {
    writes.add(
        new Write(
            "INSERT INTO crm_prepay_payment (id, tenant_id, prepay_id, request_id, request_fingerprint, pay_amount_cny, pay_time, pay_method, pay_remark, voucher_file_id, operator_id, create_time, operator_name, operator_role) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Arrays.asList(
                row.id(),
                company(),
                row.prepayId(),
                row.requestId(),
                row.fingerprint(),
                row.amount(),
                row.payTime(),
                row.method(),
                row.remark(),
                row.voucherFileId(),
                row.operatorId(),
                row.createTime(),
                row.operatorName(),
                row.operatorRole())));
    return 1;
  }

  private List<Map<String, Object>> dataFor(String sql) {
    for (Rows fixture : fixtures) {
      if (fixture.sql().test(sql)) {
        return fixture.data();
      }
    }
    return List.of();
  }

  private Object scalar(String sql, Object fallback) {
    for (var entry : scalars.entrySet()) {
      if (sql.contains(entry.getKey())) {
        return entry.getValue();
      }
    }
    return fallback;
  }

  private PrepayHead headOrNull(List<Map<String, Object>> data) {
    return data.isEmpty() ? null : head(data.getFirst());
  }

  private PrepayHead head(Map<String, Object> row) {
    return new PrepayHead(
        longOrNull(row.get("id")),
        (String) row.get("prepay_no"),
        longOrNull(row.get("kol_id")),
        longOrNull(row.get("applicant_id")),
        (BigDecimal) row.get("amount_cny"),
        (String) row.get("status"),
        instant(row.get("create_time")));
  }

  private PrepayApplicationColumns application(List<Map<String, Object>> data) {
    if (data.isEmpty()) {
      throw new EmptyResultDataAccessException(1);
    }
    Map<String, Object> row = data.getFirst();
    return new PrepayApplicationColumns(
        longOrNull(row.get("id")),
        (String) row.get("reason"),
        localDate(row.get("expected_pay_date")),
        (Boolean) row.get("deduct_from_commission"),
        longOrNull(row.get("payout_account_id")),
        (String) row.get("payout_account_type"),
        (String) row.get("payout_currency"),
        (String) row.get("payout_network"),
        (String) row.get("payout_holder_name"),
        (String) row.get("payout_account_masked"));
  }

  private PrepayHistoryRow historyOrNull(List<Map<String, Object>> data) {
    return data.isEmpty() ? null : history(data.getFirst());
  }

  private PrepayHistoryRow history(Map<String, Object> row) {
    Long operatorId = longOrNull(row.get("operator_id"));
    return new PrepayHistoryRow(
        (String) row.get("action"),
        operatorId == null ? 0L : operatorId,
        (String) row.get("operator_name"),
        (String) row.get("operator_role"),
        instant(row.get("create_time")),
        (String) row.get("reason"));
  }

  private PrepayPaymentRow paymentOrNull(List<Map<String, Object>> data) {
    if (data.isEmpty()) {
      return null;
    }
    Map<String, Object> row = data.getFirst();
    return new PrepayPaymentRow(
        (BigDecimal) row.get("pay_amount_cny"),
        instant(row.get("pay_time")),
        (String) row.get("pay_method"),
        (String) row.get("pay_remark"),
        longOrNull(row.get("voucher_file_id")),
        longOrNull(row.get("operator_id")),
        (String) row.get("operator_name"),
        (String) row.get("operator_role"));
  }

  private PrepayDeductionRow deductionOrNull(List<Map<String, Object>> data) {
    if (data.isEmpty()) {
      return null;
    }
    Map<String, Object> row = data.getFirst();
    return new PrepayDeductionRow(
        (BigDecimal) row.get("posted"), ((Number) row.get("ledger_id")).longValue());
  }

  private PrepayRecipientRow recipientOrNull(List<Map<String, Object>> data) {
    if (data.isEmpty()) {
      return null;
    }
    Map<String, Object> row = data.getFirst();
    return new PrepayRecipientRow(
        (String) row.get("payout_account_type"),
        (String) row.get("payout_currency"),
        (String) row.get("payout_network"),
        (String) row.get("payout_holder_name"),
        (String) row.get("payout_account_cipher"),
        (String) row.get("prepay_no"));
  }

  private static String textOrNull(List<Map<String, Object>> data) {
    if (data.isEmpty()) {
      return null;
    }
    Object value = data.getFirst().get("1");
    return value == null ? null : value.toString();
  }

  private static Long idOrNull(List<Map<String, Object>> data) {
    return idOrNull(data, "id");
  }

  private static Long idOrNull(List<Map<String, Object>> data, String key) {
    if (data.isEmpty() || data.getFirst().get(key) == null) {
      return null;
    }
    return ((Number) data.getFirst().get(key)).longValue();
  }

  private static Long longOrNull(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static Instant instant(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    return (Instant) value;
  }

  private static java.time.LocalDate localDate(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate();
    }
    return (java.time.LocalDate) value;
  }

  private static Object defaultValue(InvocationOnMock invocation) {
    Class<?> type = invocation.getMethod().getReturnType();
    if (type == int.class || type == Integer.class) {
      return 0;
    }
    if (type == long.class || type == Long.class) {
      return 0L;
    }
    if (type == boolean.class || type == Boolean.class) {
      return false;
    }
    if (java.util.List.class.isAssignableFrom(type)) {
      return List.of();
    }
    return null;
  }

  private static long company() {
    Long id = TenantScope.companyId();
    if (id == null) {
      throw new AssertionError("company scope was not opened");
    }
    return id;
  }

  private record Write(String sql, List<Object> args) {}

  private record Rows(Predicate<String> sql, List<Map<String, Object>> data) {}

  private record PageCall(Long company, PrepayPageQuery query) {}

  private static final String ROW_SQL =
      "SELECT p.id, p.prepay_no, p.kol_id, k.kol_code, k.name AS kol_name, p.applicant_id, applicant.nickname AS applicant_name, p.amount_cny, p.status, p.create_time FROM crm_prepay p JOIN crm_kol k ON k.id = p.kol_id AND k.tenant_id = p.tenant_id WHERE p.id = ?";
  private static final String REQUEST_SQL =
      "SELECT p.id, p.prepay_no, p.kol_id, k.kol_code, k.name AS kol_name, p.applicant_id, applicant.nickname AS applicant_name, p.amount_cny, p.status, p.create_time FROM crm_prepay p JOIN crm_kol k ON k.id = p.kol_id AND k.tenant_id = p.tenant_id WHERE p.request_id = ?";
  private static final String ACTIVE_SQL =
      "SELECT id FROM crm_prepay WHERE status IN ('WAITING_DETAILS', 'PENDING_OPERATIONS_REVIEW', 'PENDING_FINANCE_REVIEW', 'APPROVED') LIMIT 1";
  private static final String APPLICATION_SQL =
      "SELECT reason, expected_pay_date, deduct_from_commission FROM crm_prepay";
  private static final String APPROVAL_SQL =
      "SELECT action, operator_id, operator_name, operator_role, create_time, reason FROM crm_prepay_history WHERE action IN ('OPERATIONS_APPROVED', 'FINANCE_APPROVED', 'APPROVED', 'REJECTED')";
  private static final String TIMELINE_SQL =
      "SELECT action, operator_id, operator_name, operator_role, create_time, reason FROM crm_prepay_history WHERE prepay_id = ? ORDER BY create_time, id";
  private static final String PAYMENT_SQL =
      "SELECT pay_amount_cny, pay_time, pay_method, pay_remark FROM crm_prepay_payment";
  private static final String DEDUCTION_SQL =
      "SELECT p.pay_amount_cny AS posted, l.id AS ledger_id FROM crm_prepay_payment p";
  private static final String RECIPIENT_SQL =
      "SELECT payout_account_type, payout_currency, payout_network, payout_holder_name, payout_account_cipher, prepay_no FROM crm_prepay";
  private static final String MASKED_SQL =
      "SELECT payout_account_masked FROM crm_prepay WHERE id = ?";
  private static final String KOL_FILE_SQL =
      "SELECT p.kol_id FROM crm_prepay p JOIN crm_prepay_payment payment";
  private static final String FINGERPRINT_SQL =
      "SELECT request_fingerprint FROM crm_prepay_payment WHERE request_id = ?";
}
