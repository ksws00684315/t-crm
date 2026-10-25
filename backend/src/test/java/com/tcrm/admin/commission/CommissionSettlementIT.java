package com.tcrm.admin.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.commission.api.CommissionItemApi.ItemSourceDTO;
import com.tcrm.admin.commission.api.CommissionItemApi.OrderSourceDTO;
import com.tcrm.admin.commission.job.CommissionReleaseJob;
import com.tcrm.admin.commission.service.CommissionItemSnapshotService;
import com.tcrm.admin.commission.service.CommissionWalletService;
import com.tcrm.admin.commission.service.KolCommissionService;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 62 end-to-end coverage: source snapshots accrue, the release job credits the wallet with
 * FIFO prepay recovery, refunds and attribution corrections post adjustments, and the new endpoints
 * read the same data (design §4.8, §6.2, §6.3).
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CommissionSettlementIT {
  private static final long TENANT_ID = 1L;
  private static final String ORDER_NO = "t62_wd_order_1";
  private static final String PASSWORD = "t62-pass-" + UUID.randomUUID();
  private static final AtomicLong CODE_SEQ = new AtomicLong(626000);
  private static final AtomicLong FIXTURE_IDS = new AtomicLong(6_700_000_000_000L);

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.11")
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
    registry.add("crm.bootstrap.password", () -> PASSWORD);
    registry.add("crm.link.base-url", () -> "https://t.example.test");
    registry.add("crm.kol-portal.base-url", () -> "https://kol.example.test");
    registry.add("crm.link.click-consumer.enabled", () -> "false");
    WeidianTestProperties.register(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
  @Autowired private CommissionItemSnapshotService snapshots;
  @Autowired private CommissionWalletService wallets;
  @Autowired private KolCommissionService kolCommissions;
  @Autowired private CommissionReleaseJob releaseJob;

  private String adminToken;
  private long kolA;
  private long kolB;
  private long orderId;
  private long itemId;

  @BeforeEach
  void prepare() throws Exception {
    adminToken = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    kolA = insertKol("t62.kol.a." + UUID.randomUUID() + "@t62.test", "0.1000");
    kolB = insertKol("t62.kol.b." + UUID.randomUUID() + "@t62.test", "0.2000");
    long[] ids = insertOrder(ORDER_NO, kolA);
    orderId = ids[0];
    itemId = ids[1];
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM crm_commission_item_revision WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_commission_item_state WHERE tenant_id = ?", TENANT_ID);
    jdbc.update(
        "DELETE FROM crm_commission_recovery WHERE tenant_id = ? AND kol_id IN (?, ?)",
        TENANT_ID,
        kolA,
        kolB);
    jdbc.update(
        "DELETE FROM crm_commission_ledger WHERE tenant_id = ? AND kol_id IN (?, ?)",
        TENANT_ID,
        kolA,
        kolB);
    jdbc.update(
        "DELETE FROM crm_commission_wallet WHERE tenant_id = ? AND kol_id IN (?, ?)",
        TENANT_ID,
        kolA,
        kolB);
    jdbc.update("DELETE FROM crm_commission_rule_version WHERE tenant_id = ?", TENANT_ID);
    jdbc.update(
        "DELETE FROM crm_kol_promotion_record WHERE tenant_id = ? AND kol_id IN (?, ?)",
        TENANT_ID,
        kolA,
        kolB);
    jdbc.update(
        "DELETE FROM crm_order_item WHERE tenant_id = ? AND order_id = ?", TENANT_ID, orderId);
    jdbc.update("DELETE FROM crm_order WHERE tenant_id = ? AND id = ?", TENANT_ID, orderId);
    jdbc.update("DELETE FROM crm_kol WHERE tenant_id = ? AND id IN (?, ?)", TENANT_ID, kolA, kolB);
  }

  @Test
  void shouldRunOrderToWalletFlowAndExposeItOnEndpoints() throws Exception {
    Instant payTime = Instant.now().minus(1, ChronoUnit.DAYS);
    Instant settledAt = Instant.now().minus(1, ChronoUnit.HOURS);

    // 1. Without a source basis the line stays unverified: nothing accrues, nothing releases.
    sync(orderId, itemId, kolA, 1, null, null, settledAt);
    assertThat(itemStateField(itemId, "verified")).isEqualTo(false);
    assertThat(itemStateField(itemId, "release_basis")).isEqualTo("UNVERIFIED");
    releaseJob.releaseDue();
    assertThat(walletBalance(kolA)).isEqualByComparingTo("0.00");

    // 2. Verified source arrives: basis 200 at 10% = 20.00, settled → CONFIRMED release basis.
    insertPromotionConfig(kolA, "0.1000", payTime.minusSeconds(3600));
    sync(orderId, itemId, kolA, 1, new BigDecimal("200.00"), null, settledAt);
    assertThat(itemStateField(itemId, "verified")).isEqualTo(true);
    assertThat(itemStateField(itemId, "accrued_commission_cny")).isEqualTo(new BigDecimal("20.00"));
    assertThat(itemStateField(itemId, "release_basis")).isEqualTo("CONFIRMED");

    // 3. The release job credits the wallet with a COMMISSION_CREDIT ledger row.
    releaseJob.releaseDue();
    assertThat(walletBalance(kolA)).isEqualByComparingTo("20.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_commission_ledger WHERE kol_id = ? AND entry_type ="
                    + " 'COMMISSION_CREDIT' AND amount_cny = 20",
                Integer.class,
                kolA))
        .isEqualTo(1);
    assertThat(itemStateField(itemId, "released_commission_cny"))
        .isEqualTo(new BigDecimal("20.00"));

    // 4. A 50 refund cuts the accrual to 15 and posts a -5 COMMISSION_ADJUSTMENT.
    sync(orderId, itemId, kolA, 1, new BigDecimal("200.00"), new BigDecimal("50.00"), settledAt);
    assertThat(walletBalance(kolA)).isEqualByComparingTo("15.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_commission_ledger WHERE kol_id = ? AND entry_type ="
                    + " 'COMMISSION_ADJUSTMENT' AND amount_cny = -5",
                Integer.class,
                kolA))
        .isEqualTo(1);

    // 5. A prepay deduction takes the wallet negative (-15); a larger verified revision releases
    //    25 more, of which 15 offsets the outstanding prepay FIFO and 10 lands in the balance.
    TenantScope.runCompany(
        TENANT_ID,
        () ->
            wallets.postPrepayDeduction(
                TENANT_ID, kolA, 9901L, new BigDecimal("30.00"), Instant.now(), 5L));
    assertThat(walletBalance(kolA)).isEqualByComparingTo("-15.00");
    // Hand calc: basis 450 − refund 50 = 400 × 0.10 = 40.00 accrued; released was 15, so the
    // release posts +25. The wallet moves -15 + 25 = 10; FIFO books the whole 25 against the
    // outstanding 30 prepay deduction (pending drops to 5).
    sync(orderId, itemId, kolA, 1, new BigDecimal("450.00"), new BigDecimal("50.00"), settledAt);
    releaseJob.releaseDue();
    assertThat(walletBalance(kolA)).isEqualByComparingTo("10.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cny), 0) FROM crm_commission_recovery WHERE kol_id = ?",
                BigDecimal.class,
                kolA))
        .isEqualByComparingTo("25.00");

    // 6. Attribution correction moves the line to KOL B (20%): KOL A is clawed back 40 released.
    insertPromotionConfig(kolB, "0.2000", payTime.minusSeconds(3600));
    jdbc.update("UPDATE crm_order_item SET kol_id = ? WHERE id = ?", kolB, itemId);
    sync(orderId, itemId, kolB, 2, new BigDecimal("200.00"), null, settledAt);
    assertThat(walletBalance(kolA)).isEqualByComparingTo("-30.00");
    assertThat(itemStateField(itemId, "released_commission_cny")).isEqualTo(new BigDecimal("0.00"));
    assertThat(itemStateField(itemId, "accrued_commission_cny")).isEqualTo(new BigDecimal("40.00"));
    releaseJob.releaseDue();
    assertThat(walletBalance(kolB)).isEqualByComparingTo("40.00");

    // 7. Admin endpoints read the same data; the config optimistic lock behaves per contract.
    mvc.perform(get("/admin-api/commission/incomes").header("X-Admin-Token", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page.items").doesNotExist())
        .andExpect(jsonPath("$.data.total").isNumber())
        .andExpect(jsonPath("$.data.total").value(2))
        .andExpect(jsonPath("$.data.summary.paid").exists())
        .andExpect(jsonPath("$.data.summary.commissionPaid").value("0.00"))
        .andExpect(jsonPath("$.data.summary.prepayPaid").value("0.00"))
        .andExpect(jsonPath("$.data.summary.unverified").isNumber())
        .andExpect(jsonPath("$.data.summary.unverified").value(0));
    mvc.perform(
            get("/admin-api/commission/settlement-details")
                .param("orderNo", ORDER_NO)
                .header("X-Admin-Token", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").isNumber())
        .andExpect(jsonPath("$.data.items[0].orderNo").value(ORDER_NO))
        .andExpect(jsonPath("$.data.items[0].status").value("POSTED"))
        .andExpect(jsonPath("$.data.items[0].commission").value("40.00"))
        .andExpect(jsonPath("$.data.items[0].lines[0].product").value("t62 item"))
        .andExpect(
            jsonPath("$.data.items[0].lines[0].sku").value(org.hamcrest.Matchers.nullValue()));

    mvc.perform(
            get("/admin-api/commission/settlement-configs/current")
                .header("X-Admin-Token", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(0))
        .andExpect(jsonPath("$.data.minimum").value("200.00"))
        .andExpect(jsonPath("$.data.channels[0].days").value(7));
    mvc.perform(
            put("/admin-api/commission/settlement-configs/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":9,\"minimum\":\"150.00\",\"days\":5}")
                .header("X-Admin-Token", adminToken))
        .andExpect(status().isConflict());
    mvc.perform(
            put("/admin-api/commission/settlement-configs/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("version", 0, "minimum", "150.00", "days", 5)))
                .header("X-Admin-Token", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(1))
        .andExpect(jsonPath("$.data.minimum").value("150.00"))
        .andExpect(jsonPath("$.data.channels[0].days").value(5));
  }

  @Test
  void shouldApplyPayWindowAndOccupationOnIncome() throws Exception {
    String email = "t67.income." + UUID.randomUUID() + "@t67.test";
    long kol = insertKol(email, "0.1000");
    String inNo = "t67Win" + kol;
    String outNo = "t67Wout" + kol;
    String endNo = "t67Wend" + kol;
    String prepayInNo = "t67Pin" + kol;
    try {
      insertWallet(kol, "20.00");
      insertWithdrawal(kol, "t67Wpending" + kol, "30.00", "PENDING_REVIEW");
      insertWithdrawal(kol, "t67Wrejected" + kol, "50.00", "REJECTED");
      long paidIn = insertWithdrawal(kol, inNo, "15.00", "PAID");
      insertWithdrawalPayment(
          paidIn, "15.00", "15.123456", "USD", Instant.parse("2026-10-10T08:00:00Z"), 8801L);
      long paidOut = insertWithdrawal(kol, outNo, "25.00", "PAID");
      insertWithdrawalPayment(
          paidOut, "25.00", "25.00", "CNY", Instant.parse("2026-09-01T00:00:00Z"), 8802L);
      long paidEnd = insertWithdrawal(kol, endNo, "8.00", "PAID");
      insertWithdrawalPayment(
          paidEnd, "8.00", "8.00", "CNY", Instant.parse("2026-11-01T00:00:00Z"), 8805L);
      insertLedger(kol, "COMMISSION_WITHDRAWAL", "-40.00", paidIn, "t67-wd-" + kol);
      long prepayIn = insertPrepay(kol, prepayInNo, "10.00", true);
      insertPrepayPayment(prepayIn, "10.00", Instant.parse("2026-10-09T00:00:00Z"), 8803L);
      long prepayOut = insertPrepay(kol, "t67Pout" + kol, "20.00", true);
      insertPrepayPayment(prepayOut, "20.00", Instant.parse("2026-09-15T00:00:00Z"), 8804L);
      insertLedger(kol, "PREPAY_DEDUCT", "-7.00", prepayIn, "t67-pd-" + kol);

      // Hand calc: occupied is the 30 pending review only (rejected 50 and paid rows stay out).
      // available = wallet 20 − 30 = −10. cumulative = ledger 40 + all prepay 30 = 70.
      // Window [2026-10-01, 2026-11-01) keeps commission 15 and prepay 10. The payment at end is
      // excluded. lastPaidAt is the later of 2026-10-10 and 2026-10-09.
      String start = "2026-10-01T00:00:00Z";
      String end = "2026-11-01T00:00:00Z";
      mvc.perform(
              get("/admin-api/commission/incomes")
                  .param("keyword", email)
                  .param("start", start)
                  .param("end", end)
                  .header("X-Admin-Token", adminToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.total").value(1))
          .andExpect(jsonPath("$.data.items[0].occupied").value("30.00"))
          .andExpect(jsonPath("$.data.items[0].available").value("-10.00"))
          .andExpect(jsonPath("$.data.items[0].cumulativePaid").value("70.00"))
          .andExpect(jsonPath("$.data.items[0].paid").value("25.00"))
          .andExpect(jsonPath("$.data.items[0].commissionPaid").value("15.00"))
          .andExpect(jsonPath("$.data.items[0].prepayPaid").value("10.00"))
          .andExpect(jsonPath("$.data.items[0].pendingDeduction").value("7.00"))
          .andExpect(jsonPath("$.data.items[0].lastPaidAt").value("2026-10-10T08:00:00Z"))
          .andExpect(jsonPath("$.data.summary.paid").value("25.00"))
          .andExpect(jsonPath("$.data.summary.commissionPaid").value("15.00"))
          .andExpect(jsonPath("$.data.summary.prepayPaid").value("10.00"));

      mvc.perform(
              get("/admin-api/commission/incomes")
                  .param("keyword", email)
                  .param("paymentType", "COMMISSION")
                  .param("start", start)
                  .param("end", end)
                  .header("X-Admin-Token", adminToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.items[0].paid").value("15.00"))
          .andExpect(jsonPath("$.data.items[0].commissionPaid").value("15.00"))
          .andExpect(jsonPath("$.data.items[0].prepayPaid").value("0.00"))
          .andExpect(jsonPath("$.data.items[0].cumulativePaid").value("70.00"))
          .andExpect(jsonPath("$.data.items[0].lastPaidAt").value("2026-10-10T08:00:00Z"))
          .andExpect(jsonPath("$.data.summary.paid").value("15.00"))
          .andExpect(jsonPath("$.data.summary.prepayPaid").value("0.00"));

      mvc.perform(
              get("/admin-api/commission/incomes")
                  .param("keyword", email)
                  .param("paymentType", "PREPAY")
                  .param("start", start)
                  .param("end", end)
                  .header("X-Admin-Token", adminToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.items[0].paid").value("10.00"))
          .andExpect(jsonPath("$.data.items[0].commissionPaid").value("0.00"))
          .andExpect(jsonPath("$.data.items[0].prepayPaid").value("10.00"))
          .andExpect(jsonPath("$.data.items[0].cumulativePaid").value("70.00"))
          .andExpect(jsonPath("$.data.items[0].lastPaidAt").value("2026-10-09T00:00:00Z"))
          .andExpect(jsonPath("$.data.summary.paid").value("10.00"))
          .andExpect(jsonPath("$.data.summary.commissionPaid").value("0.00"));

      mvc.perform(
              get("/admin-api/commission/incomes/" + kol)
                  .param("start", start)
                  .param("end", end)
                  .header("X-Admin-Token", adminToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.payments.length()").value(2))
          .andExpect(jsonPath("$.data.payments[0].type").value("COMMISSION"))
          .andExpect(jsonPath("$.data.payments[0].sourceNo").value(inNo))
          .andExpect(jsonPath("$.data.payments[0].withdrawalId").value(String.valueOf(paidIn)))
          .andExpect(jsonPath("$.data.payments[0].amountCny").value("15.00"))
          .andExpect(jsonPath("$.data.payments[0].actualAmount").value("15.123456"))
          .andExpect(jsonPath("$.data.payments[0].actualCurrency").value("USD"))
          .andExpect(jsonPath("$.data.payments[0].voucher").value("8801"))
          .andExpect(jsonPath("$.data.payments[0].deduct").value(false))
          .andExpect(jsonPath("$.data.payments[0].deducted").value("0.00"))
          .andExpect(jsonPath("$.data.payments[0].pending").value("0.00"))
          .andExpect(jsonPath("$.data.payments[1].type").value("PREPAY"))
          .andExpect(jsonPath("$.data.payments[1].sourceNo").value(prepayInNo))
          .andExpect(jsonPath("$.data.payments[1].deduct").value(true))
          .andExpect(jsonPath("$.data.payments[1].deducted").value("0.00"))
          .andExpect(jsonPath("$.data.payments[1].pending").value("7.00"))
          .andExpect(
              jsonPath("$.data.payments[1].withdrawalId").value(org.hamcrest.Matchers.nullValue()));

      mvc.perform(
              get("/admin-api/commission/incomes/" + kol)
                  .param("paymentType", "COMMISSION")
                  .param("start", start)
                  .param("end", end)
                  .header("X-Admin-Token", adminToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.payments.length()").value(1))
          .andExpect(jsonPath("$.data.payments[0].type").value("COMMISSION"))
          .andExpect(jsonPath("$.data.payments[0].sourceNo").value(inNo));

      mvc.perform(
              get("/admin-api/commission/incomes/" + kol)
                  .param("paymentType", "PREPAY")
                  .param("start", start)
                  .param("end", end)
                  .header("X-Admin-Token", adminToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.payments.length()").value(1))
          .andExpect(jsonPath("$.data.payments[0].type").value("PREPAY"))
          .andExpect(jsonPath("$.data.payments[0].sourceNo").value(prepayInNo));
    } finally {
      deletePayFixtures(kol);
    }
  }

  @Test
  void shouldLimitOwnScopeToAssignedKolsAndKeepKolDataSeparate() throws Exception {
    // BD with OWN scope only sees their own KOL in the income list.
    String bdToken = createOwnScopeBd(kolA);
    Instant settledAt = Instant.now().minus(1, ChronoUnit.HOURS);
    insertPromotionConfig(kolA, "0.1000", Instant.now().minus(2, ChronoUnit.DAYS));
    insertPromotionConfig(kolB, "0.2000", Instant.now().minus(2, ChronoUnit.DAYS));
    sync(orderId, itemId, kolA, 1, new BigDecimal("100.00"), null, settledAt);
    long[] idsB = insertOrder(ORDER_NO + "_b", kolB);
    try {
      sync(idsB[0], idsB[1], kolB, 1, new BigDecimal("100.00"), null, settledAt);
      releaseJob.releaseDue();

      mvc.perform(get("/admin-api/commission/incomes").header("X-Admin-Token", bdToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.total").isNumber())
          .andExpect(jsonPath("$.data.total").value(1))
          .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(kolA)))
          .andExpect(jsonPath("$.data.items[0].estimated").value("10.00"));
      // Mixed-attribution aggregate hides from OWN: kolB's order stays invisible by filter.
      mvc.perform(
              get("/admin-api/commission/settlement-details")
                  .param("orderNo", ORDER_NO + "_b")
                  .header("X-Admin-Token", bdToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.total").value(0));

      // KOL overview reads only the login KOL: kolB got 100 × 20% released.
      var overviewB =
          TenantScope.callCompany(TENANT_ID, () -> kolCommissions.overview(TENANT_ID, kolB));
      assertThat(overviewB.income().available()).isEqualTo("20.00");
      assertThat(overviewB.income().estimated()).isEqualTo("20.00");
      var recordsB =
          TenantScope.callCompany(
              TENANT_ID, () -> kolCommissions.fundRecords(TENANT_ID, kolB, 1, 20));
      assertThat(recordsB.items()).isEmpty();
    } finally {
      jdbc.update(
          "DELETE FROM crm_commission_item_state WHERE tenant_id = ? AND order_item_id = ?",
          TENANT_ID,
          idsB[1]);
      jdbc.update(
          "DELETE FROM crm_order_item WHERE tenant_id = ? AND order_id = ?", TENANT_ID, idsB[0]);
      jdbc.update("DELETE FROM crm_order WHERE tenant_id = ? AND id = ?", TENANT_ID, idsB[0]);
    }
  }

  private void sync(
      long orderId,
      long itemId,
      Long kolId,
      int attributionType,
      BigDecimal basis,
      BigDecimal refund,
      Instant settledAt) {
    TenantScope.runCompany(
        TENANT_ID,
        () ->
            snapshots.syncOrderItems(
                new OrderSourceDTO(orderId, 30L, Instant.now().minus(1, ChronoUnit.DAYS)),
                List.of(
                    new ItemSourceDTO(
                        itemId,
                        "SUB-" + itemId,
                        kolId,
                        attributionType,
                        null,
                        basis,
                        Boolean.FALSE,
                        refund,
                        settledAt == null ? "UNKNOWN" : "CONFIRMED",
                        settledAt,
                        null,
                        false))));
  }

  private BigDecimal walletBalance(long kolId) {
    return TenantScope.callCompany(TENANT_ID, () -> wallets.currentBalance(TENANT_ID, kolId));
  }

  private Object itemStateField(long itemId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM crm_commission_item_state WHERE order_item_id = ?",
        Object.class,
        itemId);
  }

  private long insertKol(String email, String rate) {
    long id = Math.abs(UUID.randomUUID().getMostSignificantBits());
    String code = "KOL-TGA" + CODE_SEQ.incrementAndGet();
    jdbc.update(
        "INSERT INTO crm_kol(id,tenant_id,kol_code,name,email,country,status,review_status,"
            + "default_rate,create_time,update_time) VALUES(?,?,?,?,?,'US',1,'APPROVED',"
            + "?::numeric,now(),now())",
        id,
        TENANT_ID,
        code,
        "t62 kol",
        email,
        rate);
    return id;
  }

  private long[] insertOrder(String orderNo, long kolId) {
    long orderId = Math.abs(UUID.randomUUID().getMostSignificantBits());
    long itemId = Math.abs(UUID.randomUUID().getMostSignificantBits());
    List<Long> shops =
        jdbc.queryForList(
            "SELECT id FROM crm_shop WHERE tenant_id = ? ORDER BY id LIMIT 1",
            Long.class,
            TENANT_ID);
    Long shopId = shops.isEmpty() ? null : shops.getFirst();
    jdbc.update(
        "INSERT INTO crm_order(id,tenant_id,shop_id,weidian_order_id,kol_id,attribution_status,"
            + "order_status,raw_status,order_time,pay_time,total_amount,pay_amount,freight,"
            + "discount,source,last_sync_time,create_time,update_time) VALUES"
            + "(?,?,?,?,?,'ATTRIBUTED','PAID','PAID',now(),now(),200,200,0,0,1,now(),now(),now())",
        orderId,
        TENANT_ID,
        shopId == null ? 30L : shopId,
        orderNo,
        kolId);
    jdbc.update(
        "INSERT INTO crm_order_item(id,tenant_id,order_id,sub_order_id,weidian_item_id,sku_id,"
            + "item_name,sku_name,quantity,pay_amount,refund_amount,kol_id,attribution_type,"
            + "create_time,update_time) VALUES(?,?,?,?,?,'','t62 item','',2,200,0,?,1,now(),now())",
        itemId,
        TENANT_ID,
        orderId,
        "SUB-" + itemId,
        "WDI-" + itemId,
        kolId);
    return new long[] {orderId, itemId};
  }

  private void insertPromotionConfig(long kolId, String rate, Instant effectiveTime) {
    Integer existing =
        jdbc.queryForObject(
            "SELECT count(*) FROM crm_kol_promotion_record WHERE kol_id = ? AND change_type ="
                + " 'CONFIG'",
            Integer.class,
            kolId);
    if (existing != null && existing > 0) {
      return;
    }
    jdbc.update(
        "INSERT INTO crm_kol_promotion_record(id,tenant_id,kol_id,change_type,before_json,"
            + "after_json,version_no,effective_time,create_time) VALUES(?,?,?,'CONFIG','{}'::jsonb,"
            + "?::jsonb,1,?,now())",
        Math.abs(UUID.randomUUID().getMostSignificantBits()),
        TENANT_ID,
        kolId,
        "{\"defaultRate\":\"" + rate + "\",\"configVersion\":1}",
        java.sql.Timestamp.from(effectiveTime));
  }

  private static long nextFixtureId() {
    return FIXTURE_IDS.incrementAndGet();
  }

  private void insertWallet(long kolId, String balance) {
    jdbc.update(
        "INSERT INTO crm_commission_wallet(id,tenant_id,kol_id,balance_cny,create_time,update_time)"
            + " VALUES(?,?,?,?::numeric,now(),now())",
        nextFixtureId(),
        TENANT_ID,
        kolId,
        balance);
  }

  private long insertWithdrawal(long kolId, String applicationNo, String amount, String status) {
    long id = nextFixtureId();
    jdbc.update(
        "INSERT INTO crm_commission_withdrawal(id,tenant_id,kol_id,application_no,request_id,"
            + "request_fingerprint,amount_cny,status,account_type,account_cipher,account_masked,"
            + "account_holder,actor_id,create_time,update_time) VALUES(?,?,?,?,?::uuid,?,"
            + "?::numeric,?,'USDT','v1:test','us***','t67',?,now(),now())",
        id,
        TENANT_ID,
        kolId,
        applicationNo,
        UUID.randomUUID().toString(),
        fingerprint(),
        amount,
        status,
        kolId);
    return id;
  }

  private void insertWithdrawalPayment(
      long withdrawalId,
      String amountCny,
      String actualAmount,
      String actualCurrency,
      Instant paidAt,
      long voucherFileId) {
    jdbc.update(
        "INSERT INTO crm_commission_withdrawal_payment(id,tenant_id,withdrawal_id,request_id,"
            + "request_fingerprint,amount_cny,actual_amount,actual_currency,paid_at,method,"
            + "voucher_file_id,operator_id,operator_name,operator_role,create_time) VALUES"
            + "(?,?,?,?::uuid,?,?::numeric,?::numeric,?,?, 'BANK',?,1,'t67','PAYMENT',now())",
        nextFixtureId(),
        TENANT_ID,
        withdrawalId,
        UUID.randomUUID().toString(),
        fingerprint(),
        amountCny,
        actualAmount,
        actualCurrency,
        java.sql.Timestamp.from(paidAt),
        voucherFileId);
  }

  private long insertPrepay(long kolId, String prepayNo, String amount, boolean deduct) {
    long id = nextFixtureId();
    jdbc.update(
        "INSERT INTO crm_prepay(id,tenant_id,kol_id,prepay_no,request_id,applicant_id,amount_cny,"
            + "deduct_from_commission,payout_account_type,payout_account_cipher,"
            + "payout_account_masked,status,approved_by,approved_time,create_time,update_time)"
            + " VALUES(?,?,?,?,?::uuid,1,?::numeric,?, 'USDT','v1:test','us***','PAID',1,now(),"
            + "now(),now())",
        id,
        TENANT_ID,
        kolId,
        prepayNo,
        UUID.randomUUID().toString(),
        amount,
        deduct);
    return id;
  }

  private void insertPrepayPayment(
      long prepayId, String amount, Instant paidAt, long voucherFileId) {
    jdbc.update(
        "INSERT INTO crm_prepay_payment(id,tenant_id,prepay_id,request_id,request_fingerprint,"
            + "pay_amount_cny,pay_time,pay_method,voucher_file_id,operator_id,create_time) VALUES"
            + "(?,?,?,?::uuid,?,?::numeric,?,'BANK',?,1,now())",
        nextFixtureId(),
        TENANT_ID,
        prepayId,
        UUID.randomUUID().toString(),
        fingerprint(),
        amount,
        java.sql.Timestamp.from(paidAt),
        voucherFileId);
  }

  private void insertLedger(
      long kolId, String entryType, String amount, long sourceId, String sourceKey) {
    jdbc.update(
        "INSERT INTO crm_commission_ledger(id,tenant_id,kol_id,entry_type,amount_cny,source_type,"
            + "source_id,source_key,occurred_at,balance_after_cny,create_time) VALUES"
            + "(?,?,?,?,?::numeric,?,?,?,now(),0,now())",
        nextFixtureId(),
        TENANT_ID,
        kolId,
        entryType,
        amount,
        entryType,
        sourceId,
        sourceKey);
  }

  private void deletePayFixtures(long kolId) {
    jdbc.update(
        "DELETE FROM crm_commission_withdrawal_payment WHERE tenant_id = ? AND withdrawal_id IN"
            + " (SELECT id FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?)",
        TENANT_ID,
        TENANT_ID,
        kolId);
    jdbc.update(
        "DELETE FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?",
        TENANT_ID,
        kolId);
    jdbc.update(
        "DELETE FROM crm_prepay_payment WHERE tenant_id = ? AND prepay_id IN"
            + " (SELECT id FROM crm_prepay WHERE tenant_id = ? AND kol_id = ?)",
        TENANT_ID,
        TENANT_ID,
        kolId);
    jdbc.update("DELETE FROM crm_prepay WHERE tenant_id = ? AND kol_id = ?", TENANT_ID, kolId);
    jdbc.update(
        "DELETE FROM crm_commission_ledger WHERE tenant_id = ? AND kol_id = ?", TENANT_ID, kolId);
    jdbc.update(
        "DELETE FROM crm_commission_wallet WHERE tenant_id = ? AND kol_id = ?", TENANT_ID, kolId);
    jdbc.update("DELETE FROM crm_kol WHERE tenant_id = ? AND id = ?", TENANT_ID, kolId);
  }

  private static String fingerprint() {
    String hex = UUID.randomUUID().toString().replace("-", "");
    return (hex + hex).substring(0, 64);
  }

  private String createOwnScopeBd(long kolId) throws Exception {
    String username = "t62_bd_" + UUID.randomUUID().toString().substring(0, 8);
    String body =
        mvc.perform(
                post("/admin-api/system/users")
                    .header("X-Admin-Token", adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "username", username,
                                "password", PASSWORD,
                                "nickname", "t62 bd",
                                "role", "STAFF",
                                "postTemplate", "BD",
                                "dataScope", "OWN",
                                "permissions", List.of("commission:view")))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long userId = Long.parseLong(json.readTree(body).at("/data/id").asText());
    jdbc.update("UPDATE crm_kol SET bd_user_id = ? WHERE id = ?", userId, kolId);
    return CompanySessions.login(mvc, json, username, PASSWORD);
  }
}
