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
import com.tcrm.admin.commission.model.FinanceTaskRow;
import com.tcrm.admin.commission.model.WithdrawalRow;
import com.tcrm.admin.commission.model.WithdrawalRows;
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

/**
 * Input validation, snapshot resolution and read-path branches of {@link WithdrawalService} that
 * the workflow tests do not reach (task 63 coverage gate).
 */
class WithdrawalServiceBranchTest {
  private static final long COMPANY = 11L;
  private static final long KOL = 22L;
  private static final long ID = 77L;
  private static final long ITEM_1 = 101L;
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
            configs,
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(configs.getInt(any())).thenReturn(200);
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(5L)));
    when(kols.lockKol(KOL)).thenReturn(true);
    when(kols.listByIdsIncludingDeleted(anyList())).thenReturn(List.of(kol(5L)));
    when(accountNames.listByIds(anyList())).thenReturn(List.of());
    when(wallets.lockBalance(KOL)).thenReturn(new BigDecimal("500.00"));
    when(withdrawals.sumActiveOccupied(KOL)).thenReturn(BigDecimal.ZERO);
    when(funding.frozenCny(KOL)).thenReturn(new BigDecimal("0.00"));
    when(funding.listReleasedRows(KOL))
        .thenReturn(List.of(new FundingRow(ITEM_1, new BigDecimal("350.00"))));
    when(orderItems.listByIds(anyList()))
        .thenReturn(
            List.of(
                new OrderItemInfoDTO(ITEM_1, 11L, "WD1001", "w1", "s1", "Hat", "Red", 7L, KOL)));
    when(products.listProducts(anyList())).thenReturn(List.of());
    when(withdrawals.sumOccupiedByItem(anyLong(), anyList())).thenReturn(List.of());
    when(withdrawals.nextSequence(anyLong(), any(), any())).thenReturn(7);
    when(withdrawals.insert(any())).thenReturn(1);
    when(withdrawals.findIdByRequest(anyLong(), any(UUID.class))).thenReturn(null);
    when(withdrawals.countAllocations(anyLong())).thenReturn(1L);
    when(withdrawals.timeline(anyLong())).thenReturn(List.of());
    when(withdrawals.findPayment(anyLong())).thenReturn(null);
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_REVIEW"));
    when(withdrawals.lockById(anyLong())).thenReturn(ID);
    when(withdrawals.count(any())).thenReturn(0L);
    when(withdrawals.page(any())).thenReturn(List.of());
    when(freezes.latest(KOL)).thenReturn(null);
  }

  // ------------------------------------------------------------------ apply input validation

  @Test
  void shouldRejectMalformedApplicationInputs() {
    for (String bad : new String[] {null, "abc", "0", "-5", "1.234", "1,2"}) {
      assertThatThrownBy(() -> service.apply(kol(), apply(bad, "creator@example.com")))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    }
    for (String bad : new String[] {null, "", "not-a-uuid", UUID_TEXT + "0"}) {
      assertThatThrownBy(
              () ->
                  service.apply(
                      kol(),
                      new WithdrawalService.ApplyCommand(
                          bad,
                          "400.00",
                          null,
                          null,
                          new WithdrawalService.AccountInput(
                              "PAYPAL", "creator@example.com", "Holder", "USD", null, null),
                          null)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    }
    // an inline account or a referenced account id must be provided
    assertThatThrownBy(
            () ->
                service.apply(
                    kol(),
                    new WithdrawalService.ApplyCommand(
                        UUID_TEXT, "400.00", null, null, null, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  @Test
  void shouldResolveUsdtSnapshots_andRejectBadOnes() {
    var applied =
        service.apply(
            kol(),
            new WithdrawalService.ApplyCommand(
                UUID_TEXT,
                "250.00",
                null,
                null,
                new WithdrawalService.AccountInput(
                    "USDT", "TQ5xnPW9tZ3kA7qL9aZ", "Holder", null, "TRC20", 42L),
                null));
    assertThat(applied.status()).isEqualTo("PENDING_REVIEW");
    // bad network, non-alphanumeric address, currency on USDT
    for (WithdrawalService.AccountInput bad :
        List.of(
            new WithdrawalService.AccountInput(
                "USDT", "TQ5xnPW9tZ3kA7qL9aZ", "Holder", null, "NOPE", null),
            new WithdrawalService.AccountInput(
                "USDT", "bad address!", "Holder", null, "TRC20", null),
            new WithdrawalService.AccountInput(
                "USDT", "TQ5xnPW9tZ3kA7qL9aZ", "Holder", "USD", "TRC20", null),
            new WithdrawalService.AccountInput(
                "PAYPAL", "not-an-email", "Holder", "USD", null, null),
            new WithdrawalService.AccountInput(
                "PAYPAL", "creator@example.com", "Holder", "USD", "TRC20", null),
            new WithdrawalService.AccountInput(
                "PAYPAL", "creator@example.com", "Holder", "USD", null, 9L),
            new WithdrawalService.AccountInput(
                "WIRE", "creator@example.com", "Holder", "USD", null, null),
            new WithdrawalService.AccountInput(
                "USDT", "TQ5xnPW9tZ3kA7qL9aZ", " ", null, "TRC20", null))) {
      assertThatThrownBy(
              () ->
                  service.apply(
                      kol(),
                      new WithdrawalService.ApplyCommand(
                          UUID.randomUUID().toString(), "250.00", null, null, bad, null)))
          .as("input %s", bad)
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    }
  }

  @Test
  void shouldTreatMissingCreatorOrWalletRow_asNotFoundOrZero() {
    assertThatThrownBy(() -> service.apply(null, apply("400.00", "creator@example.com")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    // no wallet row yet: conditions still answer with zero instead of failing
    when(wallets.findBalance(KOL)).thenReturn(null);
    var conditions = service.conditions(COMPANY, KOL);
    assertThat(conditions.walletBalanceCny()).isEqualTo("0.00");
    assertThat(conditions.frozenCny()).isEqualTo("0.00");
    assertThat(conditions.eligible()).isFalse();
  }

  // ------------------------------------------------------------------ reads

  @Test
  void shouldMapKolRowsWithRejectReasonAndPayment() {
    when(withdrawals.count(any())).thenReturn(1L);
    when(withdrawals.page(any()))
        .thenReturn(
            List.of(
                new WithdrawalRow.Head(
                    ID, KOL, "CW1", new BigDecimal("250.00"), "REJECTED", KOL, NOW)));
    when(withdrawals.findRow(anyLong())).thenReturn(row("REJECTED"));
    when(withdrawals.findPayment(anyLong()))
        .thenReturn(
            new WithdrawalRows.PaymentRow(
                new BigDecimal("250.00"),
                new BigDecimal("35.00"),
                "USD",
                NOW,
                "PAYPAL",
                null,
                "rate 7.1",
                88L,
                6L,
                "Test",
                "PAYMENT"));
    var page = service.pageKol(COMPANY, KOL, 1, 20);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().getFirst().rejectReason()).isEqualTo("wrong account");
    assertThat(page.items().getFirst().actualCurrency()).isEqualTo("USD");
    assertThat(page.items().getFirst().account().account()).isEqualTo("creator@example.com");
    assertThatThrownBy(() -> service.pageKol(COMPANY, KOL, 0, 20))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  @Test
  void shouldFilterOccupationsByScopeAndInput() {
    var finance = staff(6L, DataScope.ALL, Set.of("commission:view"));
    assertThatThrownBy(
            () -> service.occupations(staff(7L, DataScope.ALL, Set.of("kol:view")), List.of(1L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    assertThatThrownBy(() -> service.occupations(finance, List.of()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    // unknown order items answer empty
    when(orderItems.listByIds(anyList())).thenReturn(List.of());
    assertThat(service.occupations(finance, List.of(ITEM_1))).isEmpty();
    // an OWN viewer only sees occupations of their own creators' items
    when(orderItems.listByIds(anyList()))
        .thenReturn(
            List.of(
                new OrderItemInfoDTO(ITEM_1, 11L, "WD1001", "w1", "s1", "Hat", "Red", 7L, KOL)));
    var own = staff(5L, DataScope.OWN, Set.of("order:view"));
    assertThat(service.occupations(own, List.of(ITEM_1))).isEmpty();
    var rows =
        List.of(
            new WithdrawalMapper.OccupationRow(
                ITEM_1,
                ID,
                "CW1",
                "PAID",
                new BigDecimal("250.00"),
                new BigDecimal("300.00"),
                new BigDecimal("50.00"),
                NOW,
                NOW));
    when(withdrawals.listOccupationsByOrderItems(anyList())).thenReturn(rows);
    assertThat(service.occupations(finance, List.of(ITEM_1)).getFirst().paidAt()).isEqualTo(NOW);
  }

  @Test
  void shouldRefuseVoucherDownloadsWithoutAPaidTask() {
    var finance = staff(6L, DataScope.ALL, Set.of("commission:view"));
    when(withdrawals.findPayment(anyLong())).thenReturn(null);
    assertThatThrownBy(() -> service.voucher(finance, ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
    when(withdrawals.findPayment(anyLong()))
        .thenReturn(
            new WithdrawalRows.PaymentRow(
                new BigDecimal("250.00"),
                new BigDecimal("250.00"),
                "CNY",
                NOW,
                "BANK",
                null,
                null,
                88L,
                6L,
                "Test",
                "PAYMENT"));
    when(files.attachedFile(88L))
        .thenReturn(
            new BizFileService.AttachedFile(
                com.tcrm.admin.commission.model.FileBizType.PREPAY_VOUCHER,
                ID,
                new BizFileService.FileDTO(88L, "v.png", "image/png", 1L, new byte[] {1})));
    assertThatThrownBy(() -> service.voucher(finance, ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
    // the KOL side also refuses anything that is not their own paid task
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_PAYMENT"));
    assertThatThrownBy(() -> service.kolVoucher(COMPANY, KOL, ID))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  // ------------------------------------------------------------------ finance tasks

  @Test
  void shouldDriveTheUnifiedListWithTypeAndStatusFilters() {
    var viewer = staff(6L, DataScope.ALL, Set.of("commission:view"));
    when(withdrawals.countPrepayTasks(any())).thenReturn(1L);
    when(withdrawals.pagePrepayTasks(any()))
        .thenReturn(
            List.of(
                new FinanceTaskRow(
                    3L,
                    KOL,
                    "PP1",
                    "PREPAY",
                    "WAITING_DETAILS",
                    "PENDING_REVIEW",
                    new BigDecimal("70.00"),
                    NOW)));
    // type=PREPAY skips the withdrawal branch entirely
    var page =
        service.financeTasks(
            viewer, new WithdrawalService.FinanceFilter(null, null, "PREPAY", null, 1, 20));
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().kolCode()).isEqualTo("KOL-test");
    assertThat(page.items().getFirst().status()).isEqualTo("PENDING_REVIEW");
    assertThatThrownBy(
            () ->
                service.financeTasks(
                    viewer, new WithdrawalService.FinanceFilter(null, null, null, "PAIDX", 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    assertThatThrownBy(
            () ->
                service.financeTasks(
                    viewer, new WithdrawalService.FinanceFilter(null, null, "WIRE", null, 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    // an unknown creator code answers an empty page without querying
    when(kols.getByCode("KOL-none")).thenReturn(null);
    assertThat(
            service
                .financeTasks(
                    viewer,
                    new WithdrawalService.FinanceFilter(null, "KOL-none", null, null, 1, 20))
                .total())
        .isZero();
  }

  @Test
  void shouldRefuseFinanceTasksForPlatformAdmins() {
    var platform =
        new AccountDTO(
            9L,
            "p",
            null,
            "P",
            null,
            Role.PLATFORM_ADMIN,
            DataScope.ALL,
            1,
            null,
            null,
            0,
            COMPANY,
            "T",
            true,
            Set.of("commission:view"),
            null);
    assertThatThrownBy(
            () ->
                service.financeTasks(
                    platform, new WithdrawalService.FinanceFilter(null, null, null, null, 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  // ------------------------------------------------------------------ review/pay/freeze guards

  @Test
  void shouldRefusePaymentWithMalformedInput() {
    var payer = staff(6L, DataScope.ALL, Set.of("prepay:pay"));
    when(withdrawals.findRow(anyLong())).thenReturn(row("PENDING_PAYMENT"));
    when(withdrawals.findPaymentFingerprint(any(UUID.class))).thenReturn(null);
    // amount different from the application
    assertThatThrownBy(
            () ->
                service.pay(
                    payer,
                    ID,
                    new WithdrawalService.PaymentCommand(
                        UUID_TEXT,
                        "300.00",
                        "300.00",
                        "CNY",
                        "2026-10-25T12:00:00+08:00",
                        "bank",
                        null,
                        null),
                    voucher()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    // bad paidAt / blank method / bad currency / missing voucher
    assertThatThrownBy(
            () ->
                service.pay(
                    payer,
                    ID,
                    new WithdrawalService.PaymentCommand(
                        UUID_TEXT, "400.00", "400.00", "CNY", "25-10-2026", "bank", null, null),
                    voucher()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    assertThatThrownBy(
            () ->
                service.pay(
                    payer,
                    ID,
                    new WithdrawalService.PaymentCommand(
                        UUID_TEXT,
                        "400.00",
                        "400.00",
                        "usd",
                        "2026-10-25T12:00:00+08:00",
                        "bank",
                        null,
                        null),
                    voucher()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    assertThatThrownBy(
            () ->
                service.pay(
                    payer,
                    ID,
                    new WithdrawalService.PaymentCommand(
                        UUID_TEXT,
                        "400.00",
                        "400.00",
                        "CNY",
                        "2026-10-25T12:00:00+08:00",
                        " ",
                        null,
                        null),
                    voucher()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    assertThatThrownBy(
            () ->
                service.pay(
                    payer,
                    ID,
                    new WithdrawalService.PaymentCommand(
                        UUID_TEXT,
                        "400.00",
                        "400.00",
                        "CNY",
                        "2026-10-25T12:00:00+08:00",
                        "bank",
                        null,
                        null),
                    null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  @Test
  void shouldRefuseReviewsForPlatformAdminsAndMissingScope() {
    var platform =
        new AccountDTO(
            9L,
            "p",
            null,
            "P",
            null,
            Role.PLATFORM_ADMIN,
            DataScope.ALL,
            1,
            null,
            null,
            0,
            COMPANY,
            "T",
            true,
            Set.of("withdrawal:review"),
            null);
    assertThatThrownBy(() -> service.review(platform, ID, "APPROVE", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    var noTenant =
        new AccountDTO(
            6L,
            "s",
            null,
            "S",
            null,
            Role.STAFF,
            DataScope.ALL,
            1,
            null,
            null,
            0,
            null,
            "T",
            true,
            Set.of("withdrawal:review"),
            null);
    assertThatThrownBy(() -> service.review(noTenant, ID, "APPROVE", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    // unknown creator answers 404 before any state check
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of());
    assertThatThrownBy(
            () ->
                service.review(
                    staff(6L, DataScope.ALL, Set.of("withdrawal:review")), ID, "APPROVE", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  @Test
  void shouldValidateFreezeGuards() {
    var freezer = staff(9L, DataScope.ALL, Set.of("commission:freeze"));
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of());
    assertThatThrownBy(() -> service.freeze(freezer, KOL, "reason"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of(kol(5L)));
    assertThatThrownBy(() -> service.freeze(freezer, KOL, "x".repeat(501)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    assertThatThrownBy(() -> service.resume(freezer, KOL, "x".repeat(501)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
    assertThatThrownBy(
            () -> service.freeze(staff(8L, DataScope.ALL, Set.of("commission:view")), KOL, "r"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
  }

  @Test
  void shouldRefuseExportsBeyondTheRowLimit() {
    var exporter = staff(6L, DataScope.ALL, Set.of("data:export", "withdrawal:review"));
    when(withdrawals.count(any())).thenReturn(100001L);
    assertThatThrownBy(
            () -> service.export(exporter, new WithdrawalService.Filter(null, null, null, 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
  }

  // ------------------------------------------------------------------ helpers

  private static WithdrawalService.ApplyCommand apply(String amount, String email) {
    return new WithdrawalService.ApplyCommand(
        UUID_TEXT,
        amount,
        null,
        null,
        new WithdrawalService.AccountInput("PAYPAL", email, "Holder", "USD", null, null),
        null);
  }

  private static WithdrawalService.UploadedFile voucher() {
    byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    return new WithdrawalService.UploadedFile("receipt.png", png);
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
    return new WithdrawalRow(
        ID,
        KOL,
        "CW1",
        UUID.fromString(UUID_TEXT),
        "f".repeat(64),
        null,
        new BigDecimal("400.00"),
        status,
        null,
        "PAYPAL",
        new SecretCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]))
            .encrypt("creator@example.com"),
        "cr***@example.com",
        "USD",
        null,
        "Creator Name",
        null,
        null,
        "wrong account",
        null,
        null,
        null,
        KOL,
        NOW);
  }
}
