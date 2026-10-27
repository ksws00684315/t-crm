package com.tcrm.admin.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.commission.service.CommissionWalletService;
import com.tcrm.admin.commission.service.WithdrawalOutboxDelivery;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real Postgres coverage of the withdrawal workflow (task 63): the full 申请 → 审核 → 付款 chain on the
 * shared wallet, FIFO allocations with snapshots, creator freeze, BD handover, company isolation,
 * and the notification outbox. The funding source is the contract stub until task 62's settlement
 * engine merges (task prompt: 在测试中用桩替代).
 */
@Tag("docker")
@Testcontainers
@SpringBootTest(properties = "crm.link.click-consumer.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WithdrawalIT {
  private static final long TENANT_ID = 1L;
  private static final String PREFIX = "t63_";

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.11")
          .withDatabaseName("tcrm")
          .withUsername("tcrm")
          .withPassword(UUID.randomUUID().toString());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.password", () -> "");
    registry.add("crm.bootstrap.username", () -> "bootstrap");
    registry.add("crm.bootstrap.password", () -> UUID.randomUUID().toString());
    registry.add("crm.link.base-url", () -> "https://t.example.test");
    registry.add("crm.kol-portal.base-url", () -> "https://kol.example.test");
    WeidianTestProperties.register(registry);
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private WithdrawalService withdrawals;
  @Autowired private CommissionWalletService walletService;
  @Autowired private WithdrawalOutboxDelivery outbox;
  @Autowired private SecretCipher cipher;
  private Long kolId;

  @AfterEach
  void removeTaskRows() {
    if (kolId != null) {
      cleanupKol(TENANT_ID, kolId);
    }
    // The outbox test commits its own transactions; drop anything left over.
    jdbc.update(
        "DELETE FROM crm_notification WHERE type LIKE 'WITHDRAWAL_%' AND tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_commission_withdrawal_outbox WHERE tenant_id = ?", TENANT_ID);
  }

  private void cleanupKol(long tenantId, long kol) {
    jdbc.update(
        "DELETE FROM crm_commission_item_state WHERE tenant_id = ? AND kol_id = ?", tenantId, kol);
    jdbc.update(
        "DELETE FROM crm_commission_withdrawal_outbox WHERE tenant_id = ? AND withdrawal_id IN"
            + " (SELECT id FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?)",
        tenantId,
        tenantId,
        kol);
    jdbc.update(
        "DELETE FROM crm_commission_withdrawal_allocation WHERE tenant_id = ? AND withdrawal_id IN"
            + " (SELECT id FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?)",
        tenantId,
        tenantId,
        kol);
    jdbc.update(
        "DELETE FROM crm_commission_withdrawal_history WHERE tenant_id = ? AND withdrawal_id IN"
            + " (SELECT id FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?)",
        tenantId,
        tenantId,
        kol);
    jdbc.update(
        "DELETE FROM crm_commission_withdrawal_payment WHERE tenant_id = ? AND withdrawal_id IN"
            + " (SELECT id FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?)",
        tenantId,
        tenantId,
        kol);
    jdbc.update(
        "DELETE FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?", tenantId, kol);
    jdbc.update(
        "DELETE FROM crm_commission_withdrawal_freeze WHERE tenant_id = ? AND kol_id = ?",
        tenantId,
        kol);
    jdbc.update(
        "DELETE FROM crm_commission_recovery WHERE tenant_id = ? AND kol_id = ?", tenantId, kol);
    jdbc.update(
        "DELETE FROM crm_commission_ledger WHERE tenant_id = ? AND kol_id = ?", tenantId, kol);
    jdbc.update(
        "DELETE FROM crm_commission_wallet WHERE tenant_id = ? AND kol_id = ?", tenantId, kol);
    jdbc.update("DELETE FROM crm_kol WHERE tenant_id = ? AND id = ?", tenantId, kol);
  }

  @Test
  @Transactional
  void shouldCompleteApplyReviewPayment_andBookTheWallet() {
    try (var ignored = TenantScope.openCompany(TENANT_ID)) {
      long operator = operatorId();
      long reviewerId = IdWorker.getId();
      kolId = createKol(operator);
      long[] items = createReleasedItems(kolId, "350.00", "150.00");
      releaseItems(kolId, new long[] {items[0], items[1]}, new String[] {"350.00", "150.00"});
      // wallet 500.00 (credit 500.00 hand set through the production credit service)
      walletService.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              TENANT_ID,
              kolId,
              items[0],
              "t63:credit:" + kolId,
              new BigDecimal("500.00"),
              Instant.now()));

      var conditions = withdrawals.conditions(TENANT_ID, kolId);
      assertThat(conditions.available()).isEqualTo("500.00");
      assertThat(conditions.canApply()).isTrue();

      var applied =
          withdrawals.apply(
              kol(kolId),
              new WithdrawalService.ApplyCommand(
                  UUID.randomUUID().toString(),
                  "400.00",
                  null,
                  null,
                  new WithdrawalService.AccountInput(
                      "PAYPAL", PREFIX + "pay@example.invalid", "T63 Creator", "USD", null, null),
                  "t63 first"));
      assertThat(applied.status()).isEqualTo("PENDING_REVIEW");
      assertThat(applied.applicationNo()).startsWith("W");
      // FIFO: one order holds both items, 400.00 occupied with 0.00 left at the order level; the
      // row-level detail keeps item1 350.00/0.00 and item2 50.00/100.00
      var orders = withdrawals.orders(superAdmin(operator), applied.id(), 1, 10);
      assertThat(orders.total()).isEqualTo(1);
      assertThat(orders.items().getFirst().occupied()).isEqualTo("400.00");
      // order-level remainder keeps both rows' leftovers: (350-350) + (150-50) = 100.00
      assertThat(orders.items().getFirst().remainingSnapshot()).isEqualTo("100.00");
      assertThat(orders.items().getFirst().products()).hasSize(2);
      var detailRows = withdrawals.detail(superAdmin(operator), applied.id()).allocations();
      assertThat(detailRows.get(0).occupiedCny()).isEqualTo("350.00");
      assertThat(detailRows.get(0).remainingSnapshotCny()).isEqualTo("0.00");
      assertThat(detailRows.get(1).occupiedCny()).isEqualTo("50.00");
      assertThat(detailRows.get(1).remainingSnapshotCny()).isEqualTo("100.00");
      // occupation now holds wallet money: available 500.00 - 400.00 = 100.00
      assertThat(withdrawals.conditions(TENANT_ID, kolId).available()).isEqualTo("100.00");
      // a second application clears the minimum but exceeds the available 100.00
      assertThatThrownBy(
              () ->
                  withdrawals.apply(
                      kol(kolId),
                      new WithdrawalService.ApplyCommand(
                          UUID.randomUUID().toString(),
                          "250.00",
                          null,
                          null,
                          new WithdrawalService.AccountInput(
                              "USDT", "TQ5xnPW9tZ3kA7qL9aZ", "T63 Creator", null, "TRC20", null),
                          null)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e ->
                  assertThat(e.getErrorCode())
                      .isEqualTo(ErrorCode.WITHDRAWAL_INSUFFICIENT_AVAILABLE));

      var reviewed = withdrawals.review(superAdmin(reviewerId), applied.id(), "APPROVE", null);
      assertThat(reviewed.status()).isEqualTo("PENDING_PAYMENT");
      // the reviewer never reviews their own submission (the applicant is the creator, so a
      // colliding admin id must still be refused)
      assertThatThrownBy(() -> withdrawals.review(superAdmin(kolId), applied.id(), "REJECT", "x"))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));

      long voucherFileId =
          withdrawals.uploadVoucher(superAdmin(operator), PREFIX + "receipt.png", voucherPng());
      var paid =
          withdrawals.pay(
              superAdmin(operator),
              applied.id(),
              new WithdrawalService.PaymentCommand(
                  UUID.randomUUID().toString(),
                  "400.00",
                  "56.00",
                  "USD",
                  "2026-10-25T12:00:00+08:00",
                  "PAYPAL",
                  null,
                  "t63 rate 7.14",
                  voucherFileId));
      assertThat(paid.status()).isEqualTo("PAID");
      assertThat(paid.payment().actualCurrency()).isEqualTo("USD");
      // wallet 500.00 - 400.00 = 100.00; one negative COMMISSION_WITHDRAWAL ledger row
      BigDecimal balance =
          jdbc.queryForObject(
              "SELECT balance_cny FROM crm_commission_wallet WHERE tenant_id = ? AND kol_id = ?",
              BigDecimal.class,
              TENANT_ID,
              kolId);
      assertThat(balance).isEqualByComparingTo("100.00");
      Map<String, Object> ledger =
          jdbc.queryForMap(
              "SELECT amount_cny, entry_type, source_key FROM crm_commission_ledger"
                  + " WHERE tenant_id = ? AND kol_id = ? AND entry_type = 'COMMISSION_WITHDRAWAL'",
              TENANT_ID,
              kolId);
      assertThat((BigDecimal) ledger.get("amount_cny")).isEqualByComparingTo("-400.00");
      assertThat(ledger.get("source_key")).isEqualTo("WITHDRAWAL:" + applied.id());
      // a second, differently-keyed successful payment cannot be registered
      assertThatThrownBy(
              () ->
                  withdrawals.pay(
                      superAdmin(operator),
                      applied.id(),
                      new WithdrawalService.PaymentCommand(
                          UUID.randomUUID().toString(),
                          "400.00",
                          "56.00",
                          "USD",
                          "2026-10-25T12:00:00+08:00",
                          "PAYPAL",
                          null,
                          "t63 rate 7.14",
                          voucherFileId)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_PAYMENT_CONFLICT));
      // the payout snapshot stays encrypted and the reveal endpoint decrypts it
      String stored =
          jdbc.queryForObject(
              "SELECT account_cipher FROM crm_commission_withdrawal WHERE id = ?",
              String.class,
              applied.id());
      assertThat(stored).startsWith("v1:").doesNotContain(PREFIX + "pay@example.invalid");
      assertThat(withdrawals.recipient(superAdmin(operator), applied.id()).account())
          .isEqualTo(PREFIX + "pay@example.invalid");
      // the order chain occupation query carries the payment time once paid (09.04)
      var chainViewer = staff(5002L, DataScope.ALL, Set.of("order:view"));
      var occupations = withdrawals.occupations(chainViewer, List.of(items[0], items[1]));
      assertThat(occupations).hasSize(2);
      assertThat(occupations.getFirst().applicationNo()).isEqualTo(applied.applicationNo());
      assertThat(occupations.getFirst().status()).isEqualTo("PAID");
      assertThat(occupations.getFirst().paidAt()).isEqualTo(Instant.parse("2026-10-25T04:00:00Z"));
      assertThat(occupations.getFirst().sourceReleasedCny()).isEqualTo("350.00");
      // the detail masks the account and hides the finance note from non-finance viewers
      var viewer = staff(5001L, DataScope.ALL, Set.of("commission:view"));
      assertThat(withdrawals.detail(viewer, applied.id()).account().account())
          .doesNotContain(PREFIX + "pay@example.invalid");
      assertThat(withdrawals.detail(viewer, applied.id()).financeNote()).isNull();
    }
  }

  /**
   * The export writes an audit row at the end; with no outer transaction (how production calls it)
   * a readOnly service transaction rejects that INSERT with SQLState 25006 and the endpoint answers
   * 500. Reproduced on a live stack during task 73's browser validation. The application row is
   * seeded straight through SQL and the test runs without @Transactional so nothing masks the
   * read-only flag.
   */
  @Test
  void shouldAuditTheExport_withoutAReadOnlyTransaction() {
    long operator = operatorId();
    long kol = createKol(operator);
    long withdrawalId = 7300000000000031L;
    try (var ignored = TenantScope.openCompany(TENANT_ID)) {
      jdbc.update(
          "INSERT INTO crm_commission_withdrawal (id, tenant_id, kol_id, application_no,"
              + " request_id, request_fingerprint, amount_cny, status, paused, account_type,"
              + " account_cipher, account_masked, account_currency, account_holder, actor_id,"
              + " create_time, update_time)"
              + " VALUES (?, ?, ?, 'W20261073RO01', gen_random_uuid(), ?, 300.00,"
              + " 'PENDING_REVIEW', FALSE, 'PAYPAL', 'v1:t73cipher', 'a***@example.test', 'USD',"
              + " 'T73 RO', ?, now(), now())",
          withdrawalId,
          TENANT_ID,
          kol,
          "d".repeat(64),
          operator);
      byte[] csv =
          withdrawals.export(
              superAdmin(operator),
              new WithdrawalService.Filter(null, kol, null, null, null, 1, 100));
      assertThat(new String(csv, java.nio.charset.StandardCharsets.UTF_8))
          .contains("W20261073RO01");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_oper_log WHERE module = 'Withdrawal'"
                      + " AND action = 'Export tasks' AND user_id = ?",
                  Integer.class,
                  operator))
          .isEqualTo(1);
    } finally {
      jdbc.update(
          "DELETE FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?",
          TENANT_ID,
          kol);
      cleanupKol(TENANT_ID, kol);
      jdbc.update(
          "DELETE FROM sys_oper_log WHERE module = 'Withdrawal' AND action = 'Export tasks'"
              + " AND user_id = ?",
          operator);
    }
  }

  @Test
  @Transactional
  void shouldExportOnlyTheRowsMatchingTheCreatorFilters() {
    try (var ignored = TenantScope.openCompany(TENANT_ID)) {
      long operator = operatorId();
      long otherBd = IdWorker.getId();
      kolId = createKol(operator);
      long kolB = createKol(otherBd);
      long[] itemsA = createReleasedItems(kolId, "500.00");
      releaseItems(kolId, itemsA, new String[] {"500.00"});
      walletService.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              TENANT_ID,
              kolId,
              itemsA[0],
              "t73:credit:a",
              new BigDecimal("500.00"),
              Instant.now()));
      long[] itemsB = createReleasedItems(kolB, "500.00");
      releaseItems(kolB, itemsB, new String[] {"500.00"});
      walletService.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              TENANT_ID, kolB, itemsB[0], "t73:credit:b", new BigDecimal("500.00"), Instant.now()));
      var applicationA = applyFresh(kolId, "300.00");
      var applicationB = applyFresh(kolB, "300.00");
      String kolCodeA =
          jdbc.queryForObject("SELECT kol_code FROM crm_kol WHERE id = ?", String.class, kolId);

      var exporter = superAdmin(operator);
      String unfiltered =
          new String(
              withdrawals.export(
                  exporter, new WithdrawalService.Filter(null, null, null, null, null, 1, 100)),
              java.nio.charset.StandardCharsets.UTF_8);
      assertThat(unfiltered)
          .contains(applicationA.applicationNo())
          .contains(applicationB.applicationNo());

      // #132: the export used to drop kolId, kolKeyword and bdUserId, so every filter below
      // exported the whole list instead of the matching rows.
      String byKolId =
          new String(
              withdrawals.export(
                  exporter, new WithdrawalService.Filter(null, kolId, null, null, null, 1, 100)),
              java.nio.charset.StandardCharsets.UTF_8);
      assertThat(byKolId)
          .contains(applicationA.applicationNo())
          .doesNotContain(applicationB.applicationNo());

      String byKeyword =
          new String(
              withdrawals.export(
                  exporter, new WithdrawalService.Filter(null, null, kolCodeA, null, null, 1, 100)),
              java.nio.charset.StandardCharsets.UTF_8);
      assertThat(byKeyword)
          .contains(applicationA.applicationNo())
          .doesNotContain(applicationB.applicationNo());

      String byBd =
          new String(
              withdrawals.export(
                  exporter, new WithdrawalService.Filter(null, null, null, operator, null, 1, 100)),
              java.nio.charset.StandardCharsets.UTF_8);
      assertThat(byBd)
          .contains(applicationA.applicationNo())
          .doesNotContain(applicationB.applicationNo());
    }
  }

  @Test
  @Transactional
  void shouldWithdrawRejectAndResubmit_withReasonFlow() {
    try (var ignored = TenantScope.openCompany(TENANT_ID)) {
      long operator = operatorId();
      kolId = createKol(operator);
      long[] items = createReleasedItems(kolId, "500.00");
      releaseItems(kolId, new long[] {items[0]}, new String[] {"500.00"});
      walletService.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              TENANT_ID,
              kolId,
              items[0],
              "t63:credit2:" + kolId,
              new BigDecimal("500.00"),
              Instant.now()));
      var applied = applyFresh(kolId, "250.00");
      // rejecting without a reason is refused
      assertThatThrownBy(
              () -> withdrawals.review(superAdmin(IdWorker.getId()), applied.id(), "REJECT", " "))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED));
      var rejected =
          withdrawals.review(superAdmin(IdWorker.getId()), applied.id(), "REJECT", "t63 wrong");
      assertThat(rejected.status()).isEqualTo("REJECTED");
      // rejected money is released: available 500.00 again
      assertThat(withdrawals.conditions(TENANT_ID, kolId).available()).isEqualTo("500.00");
      // a rejected application cannot be withdrawn either
      assertThatThrownBy(() -> withdrawals.withdraw(kol(kolId), applied.id()))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_STATE_CONFLICT));
      // resubmission references the rejected one and re-occupies the funding
      var resubmitted =
          withdrawals.apply(
              kol(kolId),
              new WithdrawalService.ApplyCommand(
                  UUID.randomUUID().toString(),
                  "250.00",
                  applied.id(),
                  null,
                  new WithdrawalService.AccountInput(
                      "PAYPAL", PREFIX + "pay2@example.invalid", "T63 Creator", "USD", null, null),
                  "t63 fixed"));
      assertThat(resubmitted.previousId()).isEqualTo(applied.id());
      assertThat(withdrawals.conditions(TENANT_ID, kolId).available()).isEqualTo("250.00");
      // a creator withdrawal before the review releases the money again
      var open =
          withdrawals.apply(
              kol(kolId),
              new WithdrawalService.ApplyCommand(
                  UUID.randomUUID().toString(),
                  "250.00",
                  null,
                  null,
                  new WithdrawalService.AccountInput(
                      "PAYPAL", PREFIX + "pay3@example.invalid", "T63 Creator", "USD", null, null),
                  null));
      assertThat(withdrawals.withdraw(kol(kolId), open.id()).status()).isEqualTo("WITHDRAWN");
      assertThat(withdrawals.conditions(TENANT_ID, kolId).available()).isEqualTo("250.00");
    }
  }

  @Test
  @Transactional
  void shouldFreezeNewApplicationsOnly() {
    try (var ignored = TenantScope.openCompany(TENANT_ID)) {
      long operator = operatorId();
      kolId = createKol(operator);
      long[] items = createReleasedItems(kolId, "500.00");
      releaseItems(kolId, new long[] {items[0]}, new String[] {"500.00"});
      walletService.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              TENANT_ID,
              kolId,
              items[0],
              "t63:credit3:" + kolId,
              new BigDecimal("500.00"),
              Instant.now()));
      var inFlight = applyFresh(kolId, "250.00");
      withdrawals.freeze(superAdmin(operator), kolId, "t63 dispute");
      assertThatThrownBy(() -> applyFresh(kolId, "250.00"))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_KOL_FROZEN));
      // in-flight applications are untouched by the freeze
      var reviewed =
          withdrawals.review(superAdmin(IdWorker.getId()), inFlight.id(), "APPROVE", null);
      assertThat(reviewed.status()).isEqualTo("PENDING_PAYMENT");
      // double freeze is refused, resume unlocks new applications
      assertThatThrownBy(() -> withdrawals.freeze(superAdmin(operator), kolId, "again"))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WITHDRAWAL_FROZEN_ALREADY));
      withdrawals.resume(superAdmin(operator), kolId, "t63 resolved");
      assertThat(applyFresh(kolId, "250.00").status()).isEqualTo("PENDING_REVIEW");
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void shouldMoveOpenTodosWithTheCreator_butKeepHistory() {
    long bdA = IdWorker.getId();
    long bdB = IdWorker.getId();
    kolId = createKol(bdA);
    try {
      long[] items = createReleasedItems(kolId, "300.00");
      releaseItems(kolId, new long[] {items[0]}, new String[] {"300.00"});
      walletService.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              TENANT_ID,
              kolId,
              items[0],
              "t63:credit4:" + kolId,
              new BigDecimal("300.00"),
              Instant.now()));
      var applied = applyFresh(kolId, "250.00");
      var reviewerA = staff(bdA, DataScope.OWN, Set.of("withdrawal:review"));
      var reviewerB = staff(bdB, DataScope.OWN, Set.of("withdrawal:review"));
      // the current BD sees the todo (07.05)
      assertThat(
              withdrawals
                  .page(
                      reviewerA, new WithdrawalService.Filter(null, null, null, null, null, 1, 20))
                  .total())
          .isEqualTo(1);
      // after the transfer the open todo belongs to the new BD only
      jdbc.update(
          "UPDATE crm_kol SET bd_user_id = ? WHERE tenant_id = ? AND id = ?",
          bdB,
          TENANT_ID,
          kolId);
      assertThat(
              withdrawals
                  .page(
                      reviewerA, new WithdrawalService.Filter(null, null, null, null, null, 1, 20))
                  .total())
          .isZero();
      assertThat(
              withdrawals
                  .page(
                      reviewerB, new WithdrawalService.Filter(null, null, null, null, null, 1, 20))
                  .total())
          .isEqualTo(1);
      assertThatThrownBy(() -> withdrawals.detail(reviewerA, applied.id()))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
      // the new BD approves; history keeps the submitter record regardless of the transfer
      var reviewed = withdrawals.review(reviewerB, applied.id(), "APPROVE", null);
      assertThat(reviewed.history().getFirst().action()).isEqualTo("SUBMITTED");
      assertThat(reviewed.history().getFirst().actorId()).isEqualTo(kolId);
    } finally {
      if (kolId != null) {
        cleanupKol(TENANT_ID, kolId);
        kolId = null;
      }
    }
  }

  @Test
  @Transactional
  void shouldIsolateCompanies() {
    try (var ignored = TenantScope.openCompany(TENANT_ID)) {
      long operator = operatorId();
      kolId = createKol(operator);
      long[] items = createReleasedItems(kolId, "300.00");
      releaseItems(kolId, new long[] {items[0]}, new String[] {"300.00"});
      walletService.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              TENANT_ID,
              kolId,
              items[0],
              "t63:credit5:" + kolId,
              new BigDecimal("300.00"),
              Instant.now()));
      var applied = applyFresh(kolId, "250.00");
      // a second company's creator applies within its own scope; company 1 must not see it
      long otherKol = IdWorker.getId();
      createKolInTenant(2L, otherKol);
      long otherItem = createReleasedItemsInTenant(2L, otherKol);
      releaseItems(2L, otherKol, new long[] {otherItem}, new String[] {"300.00"});
      walletService.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              2L,
              otherKol,
              otherItem,
              "t63:credit6:" + otherKol,
              new BigDecimal("300.00"),
              Instant.now()));
      // the other company's released rows exist on its own tenant (isolation of the funding read)
      Integer otherStates =
          jdbc.queryForObject(
              "SELECT count(*) FROM crm_commission_item_state WHERE tenant_id = 2 AND kol_id = ?",
              Integer.class,
              otherKol);
      assertThat(otherStates).as("tenant-2 item state seeded").isEqualTo(1);
      var other =
          withdrawals.apply(
              new KolAccountDTO(
                  otherKol,
                  "KOL-T63B",
                  "T63 Other",
                  PREFIX + "other@example.invalid",
                  "US",
                  null,
                  1,
                  1,
                  0,
                  null,
                  2L,
                  "Other",
                  true),
              new WithdrawalService.ApplyCommand(
                  UUID.randomUUID().toString(),
                  "250.00",
                  null,
                  null,
                  new WithdrawalService.AccountInput(
                      "PAYPAL",
                      PREFIX + "otherpay@example.invalid",
                      "T63 Other",
                      "USD",
                      null,
                      null),
                  null));
      assertThat(other.id()).isNotEqualTo(applied.id());
      // company 1's list never sees the other company's application
      assertThat(
              withdrawals
                  .page(
                      superAdmin(operator),
                      new WithdrawalService.Filter(null, null, null, null, null, 1, 20))
                  .items()
                  .stream()
                  .map(WithdrawalService.AdminRow::id))
          .doesNotContain(other.id());
      assertThatThrownBy(() -> withdrawals.detail(superAdmin(operator), other.id()))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
      cleanupKol(2L, otherKol);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void shouldDeliverOutboxNotifications_toCurrentBdAndSuperAdmins() {
    long operator = operatorId();
    kolId = createKol(operator);
    try {
      long[] items = createReleasedItems(kolId, "300.00");
      releaseItems(kolId, new long[] {items[0]}, new String[] {"300.00"});
      TenantScope.runCompany(
          TENANT_ID,
          () ->
              walletService.postCommissionCredit(
                  new CommissionWalletService.CreditCommand(
                      TENANT_ID,
                      kolId,
                      items[0],
                      "t63:credit7:" + kolId,
                      new BigDecimal("300.00"),
                      Instant.now())));
      var applied =
          TenantScope.callCompany(
              TENANT_ID,
              () ->
                  withdrawals.apply(
                      kol(kolId),
                      new WithdrawalService.ApplyCommand(
                          UUID.randomUUID().toString(),
                          "250.00",
                          null,
                          null,
                          new WithdrawalService.AccountInput(
                              "PAYPAL",
                              PREFIX + "pay7@example.invalid",
                              "T63 Creator",
                              "USD",
                              null,
                              null),
                          null)));
      // three events so far: SUBMITTED (+ APPROVED/REJECTED on review, PAID on payment)
      outbox.deliverOne();
      outbox.deliverOne();
      Integer submitted =
          jdbc.queryForObject(
              "SELECT count(*) FROM crm_notification WHERE tenant_id = ? AND type = ?",
              Integer.class,
              TENANT_ID,
              "WITHDRAWAL_SUBMITTED");
      assertThat(submitted).isGreaterThanOrEqualTo(1);
      var paid =
          TenantScope.callCompany(
              TENANT_ID,
              () -> {
                withdrawals.review(superAdmin(IdWorker.getId()), applied.id(), "APPROVE", null);
                long outboxVoucherId =
                    withdrawals.uploadVoucher(
                        superAdmin(operator), PREFIX + "receipt.png", voucherPng());
                return withdrawals.pay(
                    superAdmin(operator),
                    applied.id(),
                    new WithdrawalService.PaymentCommand(
                        UUID.randomUUID().toString(),
                        "250.00",
                        "250.00",
                        "CNY",
                        "2026-10-25T12:00:00+08:00",
                        "BANK",
                        null,
                        null,
                        outboxVoucherId));
              });
      assertThat(paid.status()).isEqualTo("PAID");
      outbox.deliverOne();
      outbox.deliverOne();
      Integer toBd =
          jdbc.queryForObject(
              "SELECT count(*) FROM crm_notification WHERE tenant_id = ? AND type = ?"
                  + " AND user_id = ?",
              Integer.class,
              TENANT_ID,
              "WITHDRAWAL_PAID",
              operator);
      assertThat(toBd).isEqualTo(1);
    } finally {
      if (kolId != null) {
        cleanupKol(TENANT_ID, kolId);
        kolId = null;
      }
    }
  }

  // ------------------------------------------------------------------ helpers

  private WithdrawalService.Detail applyFresh(long kol, String amount) {
    return withdrawals.apply(
        kol(kol),
        new WithdrawalService.ApplyCommand(
            UUID.randomUUID().toString(),
            amount,
            null,
            null,
            new WithdrawalService.AccountInput(
                "PAYPAL", PREFIX + kol + "@example.invalid", "T63 Creator", "USD", null, null),
            null));
  }

  private static byte[] voucherPng() {
    return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 't', '6', '3'};
  }

  private long operatorId() {
    return jdbc.queryForObject(
        "SELECT id FROM sys_user WHERE username = ?", Long.class, "bootstrap");
  }

  private KolAccountDTO kol(long id) {
    return new KolAccountDTO(
        id,
        "KOL-T63-" + id,
        "T63 Creator",
        PREFIX + id + "@example.invalid",
        "US",
        null,
        1,
        1,
        0,
        null,
        TENANT_ID,
        "T63",
        true);
  }

  private long createKol(long bdUserId) {
    long id = IdWorker.getId();
    createKolRow(TENANT_ID, id, bdUserId);
    return id;
  }

  private void createKolInTenant(long tenantId, long id) {
    createKolRow(tenantId, id, null);
  }

  /**
   * Inserts verified, released commission item states for the creator so the real funding source
   * (ItemStateWithdrawalFundingSource over crm_commission_item_state) serves them in FIFO order.
   */
  private void releaseItems(long kol, long[] itemIds, String[] released) {
    releaseItems(TENANT_ID, kol, itemIds, released);
  }

  private void releaseItems(long tenantId, long kol, long[] itemIds, String[] released) {
    Instant now = Instant.now();
    for (int index = 0; index < itemIds.length; index++) {
      jdbc.update(
          "INSERT INTO crm_commission_item_state (id, tenant_id, order_id, order_item_id, kol_id,"
              + " source_key, settlement_state, settlement_time, after_sales, verified,"
              + " accrued_commission_cny, released_commission_cny, release_basis, release_at,"
              + " create_time, update_time)"
              + " VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, FALSE, TRUE, ?, ?, 'CONFIRMED', ?, ?,"
              + " ?)",
          IdWorker.getId(),
          tenantId,
          11L,
          itemIds[index],
          kol,
          "t63:item:" + itemIds[index],
          Timestamp.from(now),
          new BigDecimal(released[index]),
          new BigDecimal(released[index]),
          Timestamp.from(now),
          Timestamp.from(now),
          Timestamp.from(now));
    }
  }

  private void createKolRow(long tenantId, long id, Long bdUserId) {
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO crm_kol (id, tenant_id, kol_code, name, email, country, social_accounts,"
            + " status, bd_user_id, create_time, update_time, is_deleted)"
            + " VALUES (?, ?, ?, ?, ?, 'US', '[]'::jsonb, 1, ?, ?, ?, FALSE)",
        id,
        tenantId,
        "KOL-TXA" + Long.toString(id).substring(Long.toString(id).length() - 6),
        PREFIX + "creator",
        PREFIX + id + "@example.invalid",
        bdUserId,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  /** Creates an order with one item per amount and returns the item ids. */
  private long[] createReleasedItems(long kol, String... amounts) {
    return createReleasedItemsInTenant(TENANT_ID, kol, amounts);
  }

  private long[] createReleasedItemsInTenant(long tenantId, long kol, String... amounts) {
    long orderId = IdWorker.getId();
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO crm_order (id, tenant_id, shop_id, weidian_order_id, kol_id,"
            + " attribution_status, order_status, raw_status, order_time, pay_time,"
            + " total_amount, pay_amount, freight, discount, source, last_sync_time,"
            + " create_time, update_time)"
            + " VALUES (?, ?, 10, ?, ?, 'ATTRIBUTED', 'PAID', 'paid', ?, ?, 0, 0, 0, 0, 1, ?,"
            + " ?, ?)",
        orderId,
        tenantId,
        "WD-T63-" + orderId,
        kol,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now));
    long[] items = new long[amounts.length];
    for (int index = 0; index < amounts.length; index++) {
      items[index] = IdWorker.getId();
      jdbc.update(
          "INSERT INTO crm_order_item (id, tenant_id, order_id, sub_order_id, weidian_item_id,"
              + " sku_id, item_name, sku_name, quantity, pay_amount, refund_amount,"
              + " attribution_type, kol_id, product_id, create_time, update_time)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 0, 1, ?, NULL, ?, ?)",
          items[index],
          tenantId,
          orderId,
          "sub-t63-" + items[index],
          "w-t63-" + items[index],
          "sku-t63",
          PREFIX + "Hat",
          "Red",
          new BigDecimal(amounts[index]),
          kol,
          Timestamp.from(now),
          Timestamp.from(now));
    }
    return items;
  }

  private long createReleasedItemsInTenant(long tenantId, long kol) {
    return createReleasedItemsInTenant(tenantId, kol, "300.00")[0];
  }

  private static AccountDTO superAdmin(long id) {
    return new AccountDTO(
        id,
        PREFIX + "admin",
        null,
        "T63 Admin",
        PREFIX + id + "@example.invalid",
        Role.SUPER_ADMIN,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        TENANT_ID,
        "T63",
        true,
        Set.of(),
        null);
  }

  private static AccountDTO staff(long id, DataScope scope, Set<String> permissions) {
    return new AccountDTO(
        id,
        PREFIX + "staff" + id,
        null,
        "T63 Staff",
        PREFIX + id + "@example.invalid",
        Role.STAFF,
        scope,
        1,
        null,
        null,
        0,
        TENANT_ID,
        "T63",
        true,
        permissions,
        null);
  }
}
