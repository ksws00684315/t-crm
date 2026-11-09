package com.tcrm.admin.commission;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.commission.service.CommissionWalletService;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Task 141: clawing commission back must reverse the FIFO prepay recovery the clawed credit did, by
 * appending negative recovery rows (LIFO) instead of touching existing ones.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
class CommissionRecoveryReversalIT {
  private static final long TENANT_ID = 1L;
  private static final long OTHER_TENANT_ID = 2L;
  private static final String PREFIX = "t141_";
  private static final Instant TIME = Instant.parse("2026-11-09T04:00:00Z");

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
  private long kolId;

  @Autowired
  CommissionRecoveryReversalIT(JdbcTemplate jdbc, CommissionWalletService wallets) {
    this.jdbc = jdbc;
    this.wallets = wallets;
  }

  @AfterEach
  void removeTaskRows() {
    jdbc.update("DELETE FROM crm_commission_recovery WHERE tenant_id IN (1, 2) AND kol_id >= ?", 0);
    jdbc.update("DELETE FROM crm_commission_recovery WHERE tenant_id IN (1, 2)");
    jdbc.update("DELETE FROM crm_commission_ledger WHERE tenant_id IN (1, 2)");
    jdbc.update("DELETE FROM crm_commission_wallet WHERE tenant_id IN (1, 2)");
    jdbc.update("DELETE FROM crm_kol WHERE tenant_id IN (1, 2)");
  }

  /** Deduct 100, credit 100 (fully recovered), claw the credit back: recovery must return to 0. */
  @Test
  void shouldReverseRecoveryWhenClawingBackAndRecoverAgainOnNextCredit() {
    try (TenantScope.Scope ignored = TenantScope.openCompany(TENANT_ID)) {
      kolId = createKol(TENANT_ID);
      long prepayId = IdWorker.getId();
      long sourceId = IdWorker.getId();
      deduct(prepayId, "100");
      credit("credit_100", sourceId, "100");
      assertThat(recoveredSum()).isEqualByComparingTo("100");
      assertThat(pendingDeduction()).isEqualByComparingTo("0");

      clawback("claw_100", sourceId, "100");
      assertThat(balance()).isEqualByComparingTo("-100");
      assertThat(recoveredSum())
          .as("已抵扣 must drop back to 0 after the clawback")
          .isEqualByComparingTo("0");
      assertThat(pendingDeduction())
          .as("待抵扣 must return to 100 after the clawback")
          .isEqualByComparingTo("100");
      assertThat(reversalCount()).isEqualTo(1);
      assertThat(reversalsPointAtEntryType()).isEqualTo("COMMISSION_ADJUSTMENT");

      credit("credit_100_again", sourceId, "100");
      assertThat(balance()).isEqualByComparingTo("0");
      assertThat(recoveredSum())
          .as("已抵扣 must be 100 again after the row is credited once more")
          .isEqualByComparingTo("100");
      assertThat(pendingDeduction()).isEqualByComparingTo("0");
    }
  }

  /** Two prepays A=60 B=40, one credit of 100, claw back 30: LIFO reverses B, A keeps 60. */
  @Test
  void shouldReverseLatestRecoveryFirstAcrossTwoPrepays() {
    try (TenantScope.Scope ignored = TenantScope.openCompany(TENANT_ID)) {
      kolId = createKol(TENANT_ID);
      long prepayA = IdWorker.getId();
      long prepayB = IdWorker.getId();
      long sourceId = IdWorker.getId();
      deduct(prepayA, "60");
      deduct(prepayB, "40");
      credit("credit_100", sourceId, "100");
      assertThat(recoveredOf(prepayA)).isEqualByComparingTo("60");
      assertThat(recoveredOf(prepayB)).isEqualByComparingTo("40");

      clawback("claw_30", sourceId, "30");
      assertThat(balance()).isEqualByComparingTo("-30");
      assertThat(recoveredOf(prepayA)).as("FIFO 首个预付不被撤回").isEqualByComparingTo("60");
      assertThat(recoveredOf(prepayB)).as("LIFO 撤回最后冲抵的预付").isEqualByComparingTo("10");
      assertThat(pendingDeduction()).isEqualByComparingTo("30");
    }
  }

  /** Only 20 of a 100 credit recovered prepay debt; a full 100 clawback reverts just the 20. */
  @Test
  void shouldReverseOnlyTheRecoveredPartWhenClawbackExceedsRecovery() {
    try (TenantScope.Scope ignored = TenantScope.openCompany(TENANT_ID)) {
      kolId = createKol(TENANT_ID);
      long prepayId = IdWorker.getId();
      long sourceId = IdWorker.getId();
      deduct(prepayId, "20");
      credit("credit_100", sourceId, "100");
      assertThat(recoveredSum()).isEqualByComparingTo("20");

      clawback("claw_100", sourceId, "100");
      assertThat(balance()).isEqualByComparingTo("-20");
      assertThat(recoveredSum()).isEqualByComparingTo("0");
      assertThat(pendingDeduction()).isEqualByComparingTo("20");
    }
  }

  /** No prepay deduction exists, so the clawback has nothing to reverse. */
  @Test
  void shouldWriteNoReversal_whenNothingWasRecovered() {
    try (TenantScope.Scope ignored = TenantScope.openCompany(TENANT_ID)) {
      kolId = createKol(TENANT_ID);
      long sourceId = IdWorker.getId();
      credit("credit_100", sourceId, "100");
      clawback("claw_100", sourceId, "100");
      assertThat(recoveredSum()).isEqualByComparingTo("0");
      assertThat(reversalCount()).isEqualTo(0);
    }
  }

  /** Replaying the same clawback sourceKey must not append a second reversal. */
  @Test
  void shouldWriteTheReversalExactlyOnce_whenClawbackReplays() {
    try (TenantScope.Scope ignored = TenantScope.openCompany(TENANT_ID)) {
      kolId = createKol(TENANT_ID);
      long prepayId = IdWorker.getId();
      long sourceId = IdWorker.getId();
      deduct(prepayId, "100");
      credit("credit_100", sourceId, "100");
      clawback("claw_100", sourceId, "100");
      clawback("claw_100", sourceId, "100");
      assertThat(recoveredSum()).isEqualByComparingTo("0");
      assertThat(reversalCount()).isEqualTo(1);
    }
  }

  /**
   * A second, differently-keyed clawback of the same source row must not reverse again: the net
   * recovery of the row is zero once the first clawback's reversal is counted (negative rows join
   * through their COMMISSION_ADJUSTMENT ledger, which is also source_type COMMISSION).
   */
  @Test
  void shouldNotReverseAgain_whenTheSameRowIsClawedBackTwiceWithoutRecredit() {
    try (TenantScope.Scope ignored = TenantScope.openCompany(TENANT_ID)) {
      kolId = createKol(TENANT_ID);
      long prepayId = IdWorker.getId();
      long sourceId = IdWorker.getId();
      deduct(prepayId, "100");
      credit("credit_100", sourceId, "100");
      clawback("claw_refund", sourceId, "60");
      assertThat(recoveredSum()).isEqualByComparingTo("40");
      assertThat(pendingDeduction()).isEqualByComparingTo("60");
      clawback("claw_correction", sourceId, "40");
      assertThat(recoveredSum()).isEqualByComparingTo("0");
      assertThat(pendingDeduction()).isEqualByComparingTo("100");
      assertThat(reversalCount()).isEqualTo(2);
      // Nothing left to reverse: a third clawback appends no reversal.
      clawback("claw_extra", sourceId, "40");
      assertThat(recoveredSum()).isEqualByComparingTo("0");
      assertThat(reversalCount()).isEqualTo(2);
    }
  }

  /**
   * Two consecutive partial clawbacks of one source row across two prepays: the first clawback of
   * 30 reverses the LIFO tail (B), the second of 40 continues through B's remaining net (10) and
   * then A (30), keeping the LIFO order across separate clawback transactions.
   */
  @Test
  void shouldKeepLifoOrderAcrossTwoConsecutivePartialClawbacks() {
    try (TenantScope.Scope ignored = TenantScope.openCompany(TENANT_ID)) {
      kolId = createKol(TENANT_ID);
      long prepayA = IdWorker.getId();
      long prepayB = IdWorker.getId();
      long sourceId = IdWorker.getId();
      deduct(prepayA, "60");
      deduct(prepayB, "40");
      credit("credit_100", sourceId, "100");

      clawback("claw_30", sourceId, "30");
      assertThat(recoveredOf(prepayA)).isEqualByComparingTo("60");
      assertThat(recoveredOf(prepayB)).isEqualByComparingTo("10");

      clawback("claw_40", sourceId, "40");
      assertThat(balance()).isEqualByComparingTo("-70");
      assertThat(recoveredOf(prepayA)).isEqualByComparingTo("30");
      assertThat(recoveredOf(prepayB)).isEqualByComparingTo("0");
      assertThat(pendingDeduction()).isEqualByComparingTo("70");
      // Each clawback has its own adjustment ledger: claw_30 reversed B once, claw_40 reversed B
      // and A once — three negative rows in total.
      assertThat(reversalCount()).isEqualTo(3);
    }
  }

  /** Clawing A's row back reverses A's recovery only; B stays untouched. */
  @Test
  void shouldReverseOnlyTheClawedKolRecovery_whenAttributionMovesOut() {
    try (TenantScope.Scope ignored = TenantScope.openCompany(TENANT_ID)) {
      long kolA = createKol(TENANT_ID);
      long kolB = createKol(TENANT_ID);
      long prepayA = IdWorker.getId();
      long prepayB = IdWorker.getId();
      long sourceId = IdWorker.getId();
      deduct(prepayA, "60", kolA);
      deduct(prepayB, "40", kolB);
      credit("credit_a100", sourceId, "100", kolA);
      credit("credit_b100", sourceId, "100", kolB);
      clawback("claw_a100", sourceId, "100", kolA);
      assertThat(recoveredOf(prepayA, kolA)).isEqualByComparingTo("0");
      assertThat(balanceOf(kolA)).isEqualByComparingTo("-60");
      assertThat(recoveredOf(prepayB, kolB)).isEqualByComparingTo("40");
      assertThat(balanceOf(kolB)).isEqualByComparingTo("60");
    }
  }

  /** The reversal is scoped to one company; the other company's recovery rows stay intact. */
  @Test
  void shouldKeepReversalInsideOneCompany() {
    try (TenantScope.Scope ignored = TenantScope.openCompany(TENANT_ID)) {
      kolId = createKol(TENANT_ID);
      long prepayId = IdWorker.getId();
      long sourceId = IdWorker.getId();
      deduct(prepayId, "100");
      credit("credit_100", sourceId, "100");
      clawback("claw_100", sourceId, "100");
      assertThat(reversalCount()).isEqualTo(1);
    }
    try (TenantScope.Scope ignored = TenantScope.openCompany(OTHER_TENANT_ID)) {
      long otherKol = createKol(OTHER_TENANT_ID);
      long prepayId = IdWorker.getId();
      long sourceId = IdWorker.getId();
      deduct(prepayId, "100", OTHER_TENANT_ID, otherKol);
      credit("credit_100", sourceId, "100", OTHER_TENANT_ID, otherKol);
      clawback("claw_100", sourceId, "100", OTHER_TENANT_ID, otherKol);
      assertThat(reversedCountInTenant(OTHER_TENANT_ID)).isEqualTo(1);
      assertThat(reversedCountInTenant(TENANT_ID)).isEqualTo(1);
    }
  }

  /** The relaxed CHECK accepts positive and negative rows and still rejects zero. */
  @Test
  void shouldAcceptNegativeRecoveryRowsAndRejectZero() {
    long deductionLedger = IdWorker.getId();
    long creditLedger = IdWorker.getId();
    long adjustmentLedger = IdWorker.getId();
    jdbc.update(
        "INSERT INTO crm_commission_ledger (id, tenant_id, kol_id, entry_type, amount_cny,"
            + " source_type, source_id, source_key, occurred_at, balance_after_cny, create_time)"
            + " VALUES (?, 1, 990001, 'PREPAY_DEDUCT', -100, 'PREPAY', 990001, 't141_deduct',"
            + " ?, -100, ?)",
        deductionLedger,
        Timestamp.from(TIME),
        Timestamp.from(TIME));
    jdbc.update(
        "INSERT INTO crm_commission_ledger (id, tenant_id, kol_id, entry_type, amount_cny,"
            + " source_type, source_id, source_key, occurred_at, balance_after_cny, create_time)"
            + " VALUES (?, 1, 990001, 'COMMISSION_CREDIT', 100, 'COMMISSION', 990002,"
            + " 't141_credit', ?, 0, ?)",
        creditLedger,
        Timestamp.from(TIME.plusSeconds(1)),
        Timestamp.from(TIME));
    jdbc.update(
        "INSERT INTO crm_commission_ledger (id, tenant_id, kol_id, entry_type, amount_cny,"
            + " source_type, source_id, source_key, occurred_at, balance_after_cny, create_time)"
            + " VALUES (?, 1, 990001, 'COMMISSION_ADJUSTMENT', -100, 'COMMISSION', 990002,"
            + " 't141_claw', ?, -100, ?)",
        adjustmentLedger,
        Timestamp.from(TIME.plusSeconds(2)),
        Timestamp.from(TIME));
    jdbc.update(
        "INSERT INTO crm_commission_recovery (id, tenant_id, kol_id, credit_ledger_id,"
            + " deduction_ledger_id, amount_cny, create_time) VALUES (?, 1, 990001, ?, ?, 100, ?)",
        IdWorker.getId(),
        creditLedger,
        deductionLedger,
        Timestamp.from(TIME));
    jdbc.update(
        "INSERT INTO crm_commission_recovery (id, tenant_id, kol_id, credit_ledger_id,"
            + " deduction_ledger_id, amount_cny, create_time) VALUES (?, 1, 990001, ?, ?, -100, ?)",
        IdWorker.getId(),
        adjustmentLedger,
        deductionLedger,
        Timestamp.from(TIME.plusSeconds(1)));
    try {
      jdbc.update(
          "INSERT INTO crm_commission_recovery (id, tenant_id, kol_id, credit_ledger_id,"
              + " deduction_ledger_id, amount_cny, create_time) VALUES (?, 1, 990001, ?, ?, 0, ?)",
          IdWorker.getId(),
          creditLedger,
          deductionLedger,
          Timestamp.from(TIME.plusSeconds(2)));
      throw new AssertionError("zero recovery amount must be rejected");
    } catch (org.springframework.dao.DataIntegrityViolationException expected) {
      assertThat(expected.getMessage()).contains("ck_crm_commission_recovery_amount");
    }
  }

  private long createKol(long tenantId) {
    long id = IdWorker.getId();
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO crm_kol (id, tenant_id, kol_code, name, email, country, social_accounts,"
            + " status, create_time, update_time, is_deleted) VALUES (?, ?, ?, ?, ?, 'US',"
            + " '[]'::jsonb, 1, ?, ?, FALSE)",
        id,
        tenantId,
        "KOL-AAA" + Long.toString(id).substring(Long.toString(id).length() - 8),
        PREFIX + "creator",
        PREFIX + tenantId + "_" + id + "@example.invalid",
        Timestamp.from(now),
        Timestamp.from(now));
    return id;
  }

  private void deduct(long prepayId, String amount) {
    wallets.postPrepayDeduction(TENANT_ID, kolId, prepayId, new BigDecimal(amount), TIME, 1L);
  }

  private void deduct(long prepayId, String amount, long kol) {
    wallets.postPrepayDeduction(TENANT_ID, kol, prepayId, new BigDecimal(amount), TIME, 1L);
  }

  private void deduct(long prepayId, String amount, long tenantId, long kol) {
    wallets.postPrepayDeduction(tenantId, kol, prepayId, new BigDecimal(amount), TIME, 1L);
  }

  private void credit(String key, long sourceId, String amount) {
    wallets.postCommissionCredit(
        new CommissionWalletService.CreditCommand(
            TENANT_ID, kolId, sourceId, PREFIX + key, new BigDecimal(amount), TIME));
  }

  private void credit(String key, long sourceId, String amount, long kol) {
    wallets.postCommissionCredit(
        new CommissionWalletService.CreditCommand(
            TENANT_ID, kol, sourceId, PREFIX + key + "_" + kol, new BigDecimal(amount), TIME));
  }

  private void credit(String key, long sourceId, String amount, long tenantId, long kol) {
    wallets.postCommissionCredit(
        new CommissionWalletService.CreditCommand(
            tenantId, kol, sourceId, PREFIX + key + "_" + kol, new BigDecimal(amount), TIME));
  }

  private void clawback(String key, long sourceId, String amount) {
    wallets.postCommissionAdjustment(
        TENANT_ID, kolId, sourceId, PREFIX + key, new BigDecimal(amount).negate(), TIME);
  }

  private void clawback(String key, long sourceId, String amount, long kol) {
    wallets.postCommissionAdjustment(
        TENANT_ID, kol, sourceId, PREFIX + key + "_" + kol, new BigDecimal(amount).negate(), TIME);
  }

  private void clawback(String key, long sourceId, String amount, long tenantId, long kol) {
    wallets.postCommissionAdjustment(
        tenantId, kol, sourceId, PREFIX + key + "_" + kol, new BigDecimal(amount).negate(), TIME);
  }

  private BigDecimal balance() {
    return wallets.currentBalance(TENANT_ID, kolId);
  }

  private BigDecimal balanceOf(long kol) {
    return wallets.currentBalance(TENANT_ID, kol);
  }

  /** 已抵扣: SUM(amount_cny) over the creator's recovery rows (net after reversals). */
  private BigDecimal recoveredSum() {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(amount_cny), 0) FROM crm_commission_recovery WHERE tenant_id = ?"
            + " AND kol_id = ?",
        BigDecimal.class,
        TENANT_ID,
        kolId);
  }

  private BigDecimal recoveredOf(long prepayId) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(r.amount_cny), 0) FROM crm_commission_recovery r"
            + " JOIN crm_commission_ledger l ON l.tenant_id = r.tenant_id AND l.id ="
            + " r.deduction_ledger_id WHERE r.tenant_id = ? AND r.kol_id = ? AND l.source_id = ?",
        BigDecimal.class,
        TENANT_ID,
        kolId,
        prepayId);
  }

  private BigDecimal recoveredOf(long prepayId, long kol) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(r.amount_cny), 0) FROM crm_commission_recovery r"
            + " JOIN crm_commission_ledger l ON l.tenant_id = r.tenant_id AND l.id ="
            + " r.deduction_ledger_id WHERE r.tenant_id = ? AND r.kol_id = ? AND l.source_id = ?",
        BigDecimal.class,
        TENANT_ID,
        kol,
        prepayId);
  }

  /** 待抵扣: outstanding prepay debt after net recoveries (same shape as pendingDeductions). */
  private BigDecimal pendingDeduction() {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(-l.amount_cny - COALESCE((SELECT SUM(r.amount_cny)"
            + " FROM crm_commission_recovery r WHERE r.deduction_ledger_id = l.id), 0)), 0)"
            + " FROM crm_commission_ledger l WHERE l.tenant_id = ? AND l.kol_id = ?"
            + " AND l.entry_type = 'PREPAY_DEDUCT'",
        BigDecimal.class,
        TENANT_ID,
        kolId);
  }

  private int reversalCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM crm_commission_recovery r"
            + " JOIN crm_commission_ledger c ON c.tenant_id = r.tenant_id AND c.id ="
            + " r.credit_ledger_id WHERE r.tenant_id = ? AND r.kol_id = ? AND c.entry_type ="
            + " 'COMMISSION_ADJUSTMENT'",
        Integer.class,
        TENANT_ID,
        kolId);
  }

  private String reversalsPointAtEntryType() {
    List<String> types =
        jdbc.queryForList(
            "SELECT DISTINCT c.entry_type FROM crm_commission_recovery r"
                + " JOIN crm_commission_ledger c ON c.tenant_id = r.tenant_id AND c.id ="
                + " r.credit_ledger_id WHERE r.tenant_id = ? AND r.kol_id = ? AND r.amount_cny < 0",
            String.class,
            TENANT_ID,
            kolId);
    return types.isEmpty() ? "" : types.getFirst();
  }

  private int reversedCountInTenant(long tenantId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM crm_commission_recovery r"
            + " JOIN crm_commission_ledger c ON c.tenant_id = r.tenant_id AND c.id ="
            + " r.credit_ledger_id WHERE r.tenant_id = ? AND c.entry_type = 'COMMISSION_ADJUSTMENT'",
        Integer.class,
        tenantId);
  }
}
