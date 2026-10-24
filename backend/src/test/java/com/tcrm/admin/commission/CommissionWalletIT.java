package com.tcrm.admin.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.commission.service.CommissionWalletService;
import com.tcrm.admin.commission.service.PrepayService;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real Postgres coverage of the production commission credit service and prepay FIFO recovery. */
@Tag("docker")
@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommissionWalletIT {
  private static final long TENANT_ID = 1L;
  private static final String PREFIX = "t47_wallet_";

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
    registry.add("crm.link.click-consumer.enabled", () -> "false");
    WeidianTestProperties.register(registry);
  }

  private final JdbcTemplate jdbc;
  private final CommissionWalletService wallets;
  private final PrepayService prepays;
  private final SecretCipher cipher;
  private Long kolId;

  @Autowired
  CommissionWalletIT(
      JdbcTemplate jdbc,
      CommissionWalletService wallets,
      PrepayService prepays,
      SecretCipher cipher) {
    this.jdbc = jdbc;
    this.wallets = wallets;
    this.prepays = prepays;
    this.cipher = cipher;
  }

  @AfterEach
  void removeTaskRows() {
    if (kolId == null) {
      return;
    }
    jdbc.update(
        "DELETE FROM crm_commission_recovery WHERE tenant_id = ? AND kol_id = ?", TENANT_ID, kolId);
    jdbc.update(
        "DELETE FROM crm_commission_ledger WHERE tenant_id = ? AND kol_id = ?", TENANT_ID, kolId);
    jdbc.update(
        "DELETE FROM crm_commission_wallet WHERE tenant_id = ? AND kol_id = ?", TENANT_ID, kolId);
    jdbc.update("DELETE FROM crm_kol WHERE tenant_id = ? AND id = ?", TENANT_ID, kolId);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @Transactional
  void shouldCompleteRealPrepayWorkflowAndKeepPaymentAndDeductionIdempotent(boolean manual) {
    try (var ignored = TenantScope.openCompany(TENANT_ID)) {
      // This test uses a local creator ID and the test transaction rolls every row back.
      long creator = createKol();
      long operator =
          jdbc.queryForObject(
              "SELECT id FROM sys_user WHERE username = ?", Long.class, "bootstrap");
      // Applicant and reviewer must differ: service rejects self-review (AUTH_FORBIDDEN).
      AccountDTO applicant = superAdmin(operator);
      AccountDTO reviewer = superAdmin(IdWorker.getId());
      var application =
          new PrepayService.PrepayApplication(UUID.randomUUID().toString(), creator, "300.00");
      var pending = prepays.apply(applicant, application);
      assertThat(pending.status()).isEqualTo("WAITING_DETAILS");
      assertThat(pending.deductFromCommission()).isNull();
      assertThat(prepays.apply(applicant, application).id()).isEqualTo(pending.id());
      assertThatThrownBy(() -> prepays.approval(reviewer, pending.id(), "APPROVE", null))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PREPAY_STATE_CONFLICT));

      long payout = IdWorker.getId();
      jdbc.update(
          "INSERT INTO crm_payout_account (id, tenant_id, kol_id, account_type, account_cipher, account_masked, holder_name, currency, status, reviewed_by, reviewed_time, last_submitted_at, create_time, update_time) VALUES (?, ?, ?, 'PAYPAL', ?, ?, ?, 'USD', 'APPROVED', ?, now(), now(), now(), now())",
          payout,
          TENANT_ID,
          creator,
          cipher.encrypt("t47_recipient@example.invalid"),
          "t4***@example.invalid",
          "Test recipient",
          operator);
      String recipient = "银行转账\n收款人 张三\n账号 123456789\nCNY\n" + "资".repeat(969);
      var completed =
          prepays.saveApplicationDetails(
              applicant,
              pending.id(),
              new PrepayService.ApplicationDetails(
                  "t47 business advance",
                  LocalDate.of(2026, 10, 23),
                  manual ? null : payout,
                  true,
                  manual ? recipient : null),
              List.of());
      assertThat(completed.status()).isEqualTo("PENDING_OPERATIONS_REVIEW");
      assertThat(prepays.approval(reviewer, pending.id(), "APPROVE", null).status())
          .isEqualTo("PENDING_FINANCE_REVIEW");
      assertThat(prepays.approval(reviewer, pending.id(), "APPROVE", null).status())
          .isEqualTo("APPROVED");
      assertThatThrownBy(
              () ->
                  prepays.saveApplicationDetails(
                      applicant,
                      pending.id(),
                      new PrepayService.ApplicationDetails(
                          "changed", LocalDate.of(2026, 10, 23), null, false, "new"),
                      List.of()))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PREPAY_STATE_CONFLICT));
      if (manual) {
        assertThat(completed.payoutAccountId()).isNull();
        assertThat(completed.payoutAccountSnapshot().type()).isEqualTo("MANUAL");
        String encrypted =
            jdbc.queryForObject(
                "SELECT payout_account_cipher FROM crm_prepay WHERE tenant_id=? AND id=?",
                String.class,
                TENANT_ID,
                pending.id());
        assertThat(encrypted).startsWith("v1:").doesNotContain(recipient);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(recipient);
      }
      assertThat(prepays.recipient(reviewer, pending.id()).account())
          .isEqualTo(manual ? recipient : "t47_recipient@example.invalid");
      var payment =
          new PrepayService.PaymentCommand(
              UUID.randomUUID().toString(), "300.00", "2026-10-22T12:00:00+08:00", "BANK", null);
      var voucher =
          new PrepayService.UploadedFile(
              "t47_receipt.pdf",
              "%PDF-1.7\nt47 test receipt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var paid = prepays.pay(reviewer, pending.id(), payment, voucher);
      assertThat(paid.status()).isEqualTo("PAID");
      assertThat(paid.payment().operatorName()).isEqualTo("Test");
      assertThat(paid.payment().operatorRole()).isEqualTo("PAYMENT");
      assertThat(paid.timeline())
          .extracting(PrepayService.Timeline::operatorRole)
          .containsExactly(
              "BUSINESS", "BUSINESS", "OPERATIONS_REVIEW", "FINANCE_REVIEW", "PAYMENT");
      assertThat(paid.timeline())
          .extracting(PrepayService.Timeline::operatorName)
          .containsOnly("Test");
      assertThat(prepays.summaries(reviewer, List.of(creator)).getFirst().paidAmountCny())
          .isEqualTo("300.00");
      assertThat(
              prepays
                  .apply(
                      applicant,
                      new PrepayService.PrepayApplication(
                          UUID.randomUUID().toString(), creator, "50"))
                  .status())
          .isEqualTo("WAITING_DETAILS");
      assertThat(paid.walletBalanceCny()).isEqualTo("-300.00");
      assertThat(prepays.pay(reviewer, pending.id(), payment, voucher).payment().voucherFileId())
          .isEqualTo(paid.payment().voucherFileId());
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_prepay_payment WHERE tenant_id = ? AND prepay_id = ?",
                  Long.class,
                  TENANT_ID,
                  pending.id()))
          .isEqualTo(1L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_commission_ledger WHERE tenant_id = ? AND source_type = 'PREPAY' AND source_id = ?",
                  Long.class,
                  TENANT_ID,
                  pending.id()))
          .isEqualTo(1L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_prepay_outbox WHERE tenant_id = ? AND prepay_id = ?",
                  Long.class,
                  TENANT_ID,
                  pending.id()))
          .isEqualTo(3L);
      assertThat(prepays.kolFile(creator, paid.payment().voucherFileId()).fileName())
          .isEqualTo("t47_receipt.pdf");
      assertThatThrownBy(() -> prepays.kolFile(creator + 1, paid.payment().voucherFileId()))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
    }
  }

  @Test
  void shouldRecoverPrepayBalanceFromProductionCommissionCreditsInFifoOrder() {
    long firstPrepayId = IdWorker.getId();
    Instant occurredAt = Instant.parse("2026-10-22T04:00:00Z");
    try (TenantScope.Scope ignored = TenantScope.openCompany(TENANT_ID)) {
      kolId = createKol();
      wallets.postPrepayDeduction(
          TENANT_ID, kolId, firstPrepayId, new BigDecimal("300.00"), occurredAt, 1L);
      wallets.postPrepayDeduction(
          TENANT_ID, kolId, firstPrepayId, new BigDecimal("300.00"), occurredAt, 1L);
      assertBalance("-300.00");

      wallets.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              TENANT_ID,
              kolId,
              IdWorker.getId(),
              PREFIX + "credit_100",
              new BigDecimal("100.00"),
              occurredAt.plusSeconds(1)));
      assertBalance("-200.00");
      assertAllocation(firstPrepayId, "100.00");

      wallets.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              TENANT_ID,
              kolId,
              IdWorker.getId(),
              PREFIX + "credit_450",
              new BigDecimal("450.00"),
              occurredAt.plusSeconds(2)));
      assertBalance("250.00");
      assertAllocation(firstPrepayId, "300.00");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_commission_ledger WHERE tenant_id = ? AND source_id = ? AND source_type = 'PREPAY' AND entry_type = 'PREPAY_DEDUCT'",
                  Long.class,
                  TENANT_ID,
                  firstPrepayId))
          .isEqualTo(1L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_commission_ledger WHERE tenant_id = ? AND kol_id = ? AND entry_type = 'COMMISSION_CREDIT'",
                  Long.class,
                  TENANT_ID,
                  kolId))
          .isEqualTo(2L);
    }
  }

  private static AccountDTO superAdmin(long id) {
    return new AccountDTO(
        id,
        "bootstrap",
        null,
        "Test",
        null,
        Role.SUPER_ADMIN,
        DataScope.ALL,
        1,
        null,
        Instant.now(),
        0,
        TENANT_ID,
        "Test",
        true);
  }

  private long createKol() {
    long id = IdWorker.getId();
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO crm_kol (id, tenant_id, kol_code, name, email, country, social_accounts, status, create_time, update_time, is_deleted) VALUES (?, ?, ?, ?, ?, 'US', '[]'::jsonb, 1, ?, ?, FALSE)",
        id,
        TENANT_ID,
        "KOL-AAA" + Long.toString(id).substring(Long.toString(id).length() - 8),
        PREFIX + "creator",
        PREFIX + id + "@example.invalid",
        Timestamp.from(now),
        Timestamp.from(now));
    return id;
  }

  private void assertBalance(String expected) {
    assertThat(wallets.currentBalance(TENANT_ID, kolId)).isEqualByComparingTo(expected);
  }

  private void assertAllocation(long prepayId, String expected) {
    BigDecimal recovered =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(r.amount_cny), 0) FROM crm_commission_recovery r JOIN crm_commission_ledger l ON l.tenant_id = r.tenant_id AND l.id = r.deduction_ledger_id WHERE r.tenant_id = ? AND l.source_id = ?",
            BigDecimal.class,
            TENANT_ID,
            prepayId);
    assertThat(recovered).isEqualByComparingTo(expected);
  }
}
