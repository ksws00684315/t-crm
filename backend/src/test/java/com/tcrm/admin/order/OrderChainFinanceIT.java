package com.tcrm.admin.order;

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
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
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
 * Task 72 end-to-end coverage of the order chain funds: one order runs through snapshot accrual,
 * release, a refund clawback, two partial withdrawals (one paid, one in flight), and the chain
 * endpoint then returns the funds, the per-line commission blocks and the finance flows. Viewers
 * without {@code commission:view}, OWN scopes on mixed attribution, and other companies see null or
 * 404 (design §6.2, handoff B07–B09).
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OrderChainFinanceIT {
  private static final long TENANT_ID = 1L;
  private static final String PASSWORD = "t72-pass-" + UUID.randomUUID();
  private static final Instant PAID_AT = Instant.now().minus(1, ChronoUnit.DAYS);
  private static final Instant SETTLED_AT = Instant.now().minus(2, ChronoUnit.HOURS);
  private static final String PAID_OUT_JSON = "2026-10-25T04:00:00Z";
  private static final AtomicLong FIXTURE_IDS = new AtomicLong(7_200_000_000_000L);

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
  @Autowired private CommissionReleaseJob releaseJob;
  @Autowired private WithdrawalService withdrawals;

  private String adminToken;
  private long kolA;
  private long kolB;
  private long orderId;
  private long item1;
  private long item2;

  @BeforeEach
  void prepare() throws Exception {
    adminToken = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    insertShop(7201L);
    kolA = insertKol();
    kolB = insertKol();
    insertPromotionConfig(kolA, "0.3000", PAID_AT.minusSeconds(3600));
    orderId = nextId();
    item1 = nextId();
    item2 = nextId();
    insertOrder(orderId, null, "t72-wd-order-1");
    insertItem(item1, orderId, kolA, "200.00");
    // Unattributed line: enters the snapshot as unverified and never blocks OWN coverage.
    insertItem(item2, orderId, null, "0.00");
    insertRefund(orderId, item1, "t72-refund-1", "100.00");
  }

  @AfterEach
  void cleanup() {
    for (long kol : List.of(kolA, kolB)) {
      jdbc.update(
          "DELETE FROM crm_commission_withdrawal_payment WHERE tenant_id = ? AND withdrawal_id IN"
              + " (SELECT id FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?)",
          TENANT_ID,
          TENANT_ID,
          kol);
      jdbc.update(
          "DELETE FROM crm_commission_withdrawal_allocation WHERE tenant_id = ? AND"
              + " withdrawal_id IN (SELECT id FROM crm_commission_withdrawal WHERE tenant_id = ?"
              + " AND kol_id = ?)",
          TENANT_ID,
          TENANT_ID,
          kol);
      jdbc.update(
          "DELETE FROM crm_commission_withdrawal_order WHERE tenant_id = ? AND withdrawal_id IN"
              + " (SELECT id FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?)",
          TENANT_ID,
          TENANT_ID,
          kol);
      jdbc.update(
          "DELETE FROM crm_commission_withdrawal_history WHERE tenant_id = ? AND withdrawal_id IN"
              + " (SELECT id FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?)",
          TENANT_ID,
          TENANT_ID,
          kol);
      jdbc.update(
          "DELETE FROM crm_commission_withdrawal_outbox WHERE tenant_id = ? AND withdrawal_id IN"
              + " (SELECT id FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?)",
          TENANT_ID,
          TENANT_ID,
          kol);
      jdbc.update(
          "DELETE FROM crm_commission_withdrawal WHERE tenant_id = ? AND kol_id = ?",
          TENANT_ID,
          kol);
      jdbc.update(
          "DELETE FROM crm_commission_ledger WHERE tenant_id = ? AND kol_id = ?", TENANT_ID, kol);
      jdbc.update(
          "DELETE FROM crm_commission_wallet WHERE tenant_id = ? AND kol_id = ?", TENANT_ID, kol);
      jdbc.update(
          "DELETE FROM crm_kol_promotion_record WHERE tenant_id = ? AND kol_id = ?",
          TENANT_ID,
          kol);
    }
    jdbc.update(
        "DELETE FROM crm_notification WHERE tenant_id = ? AND type LIKE 'WITHDRAWAL_%'", TENANT_ID);
    jdbc.update("DELETE FROM crm_commission_withdrawal_outbox WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_commission_item_revision WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_commission_item_state WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_refund WHERE tenant_id = ? AND order_id = ?", TENANT_ID, orderId);
    jdbc.update(
        "DELETE FROM crm_order_item WHERE tenant_id = ? AND order_id = ?", TENANT_ID, orderId);
    jdbc.update("DELETE FROM crm_order WHERE tenant_id = ? AND id = ?", TENANT_ID, orderId);
    jdbc.update("DELETE FROM crm_kol WHERE tenant_id = ? AND id IN (?, ?)", TENANT_ID, kolA, kolB);
    jdbc.update("DELETE FROM crm_shop WHERE tenant_id = ? AND id = 7201", TENANT_ID);
  }

  @Test
  void shouldReturnFundsLinesAndFlows_afterAccrueReleaseWithdrawAndPay() throws Exception {
    // Accrue: item1 verified at 500 × 0.30 = 150.00; item2 stays unverified without a source basis.
    sync("500.00", "0.00");
    releaseJob.releaseDue();
    // Refund clawback: 500 − 100 = 400 × 0.30 = 120.00, a −30.00 COMMISSION_ADJUSTMENT.
    sync("500.00", "100.00");
    // Low minimum so the partially released amount splits over two applications.
    lowerMinimumWithdrawal();
    long operator = operatorId();
    var first =
        withdrawals.apply(
            kol(kolA),
            new WithdrawalService.ApplyCommand(
                UUID.randomUUID().toString(),
                "100.00",
                null,
                null,
                new WithdrawalService.AccountInput(
                    "PAYPAL", "t72pay@example.invalid", "T72 Creator", "USD", null, null),
                "t72 first"));
    withdrawals.review(superAdmin(operator), first.id(), "APPROVE", null);
    long voucher = withdrawals.uploadVoucher(superAdmin(operator), "t72-receipt.png", voucherPng());
    withdrawals.pay(
        superAdmin(operator),
        first.id(),
        new WithdrawalService.PaymentCommand(
            UUID.randomUUID().toString(),
            "100.00",
            "100.00",
            "CNY",
            "2026-10-25T12:00:00+08:00",
            "BANK",
            "t72 paid",
            null,
            voucher));
    var second =
        withdrawals.apply(
            kol(kolA),
            new WithdrawalService.ApplyCommand(
                UUID.randomUUID().toString(),
                "20.00",
                null,
                null,
                new WithdrawalService.AccountInput(
                    "PAYPAL", "t72pay@example.invalid", "T72 Creator", "USD", null, null),
                "t72 second"));

    mvc.perform(
            get("/admin-api/order/orders/{id}/chain", orderId).header("X-Admin-Token", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.orderNo").value("t72-wd-order-1"))
        // Order funds (B07): pay 200, deduplicated successful refund 100, net commission 120,
        // shop profit pending M0, and the unattributed line keeps the order UNVERIFIED.
        .andExpect(jsonPath("$.data.funds.payAmount").value("200.00"))
        .andExpect(jsonPath("$.data.funds.refundTotal").value("100.00"))
        .andExpect(jsonPath("$.data.funds.kolCommission").value("120.00"))
        .andExpect(jsonPath("$.data.funds.shopProfit").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.funds.accountStatus").value("UNVERIFIED"))
        // Line blocks (B07/B09): pay-time rate snapshot, occupation split by application state.
        .andExpect(jsonPath("$.data.lines[0].orderItemId").value(String.valueOf(item1)))
        .andExpect(jsonPath("$.data.lines[0].commission.rateSnapshot").value("0.3000"))
        .andExpect(jsonPath("$.data.lines[0].commission.netCommission").value("120.00"))
        .andExpect(jsonPath("$.data.lines[0].commission.shopProfit").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.lines[0].commission.releaseBasis").value("CONFIRMED"))
        .andExpect(jsonPath("$.data.lines[0].commission.occupiedTotal").value("120.00"))
        .andExpect(
            jsonPath("$.data.lines[0].commission.withdrawals[0].applicationNo")
                .value(first.applicationNo()))
        .andExpect(jsonPath("$.data.lines[0].commission.withdrawals[0].status").value("PAID"))
        .andExpect(
            jsonPath("$.data.lines[0].commission.withdrawals[0].paidAt").value(PAID_OUT_JSON))
        .andExpect(
            jsonPath("$.data.lines[0].commission.withdrawals[1].applicationNo")
                .value(second.applicationNo()))
        .andExpect(
            jsonPath("$.data.lines[0].commission.withdrawals[1].status").value("PENDING_REVIEW"))
        .andExpect(
            jsonPath("$.data.lines[0].commission.withdrawals[1].paidAt")
                .value(Matchers.nullValue()))
        // Unverified line: unknown amounts stay null, never a fake zero (only occupation is a
        // known 0.00 because unverified lines can never be occupied).
        .andExpect(jsonPath("$.data.lines[1].orderItemId").value(String.valueOf(item2)))
        .andExpect(jsonPath("$.data.lines[1].commission.netCommission").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.lines[1].commission.rateSnapshot").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.lines[1].commission.releaseBasis").value("UNVERIFIED"))
        .andExpect(jsonPath("$.data.lines[1].commission.occupiedTotal").value("0.00"))
        .andExpect(jsonPath("$.data.lines[1].commission.withdrawals").isEmpty())
        // Finance flows (B08): release credit increase, refund clawback decrease, then the two
        // withdrawal allocations — the paid one carries its application number and payment time,
        // the pending one keeps a null transfer time.
        .andExpect(jsonPath("$.data.financeFlows[0].flowNo").value(Matchers.startsWith("CL")))
        .andExpect(jsonPath("$.data.financeFlows[0].type").value("KOL_COMMISSION"))
        .andExpect(jsonPath("$.data.financeFlows[0].direction").value("INCREASE"))
        .andExpect(jsonPath("$.data.financeFlows[0].amount").value("150.00"))
        .andExpect(jsonPath("$.data.financeFlows[0].shopName").value("t72 Shop"))
        .andExpect(jsonPath("$.data.financeFlows[0].businessAt").value(Matchers.notNullValue()))
        .andExpect(jsonPath("$.data.financeFlows[0].transferredAt").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.financeFlows[0].withdrawalNo").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.financeFlows[1].flowNo").value(Matchers.startsWith("CL")))
        .andExpect(jsonPath("$.data.financeFlows[1].direction").value("DECREASE"))
        .andExpect(jsonPath("$.data.financeFlows[1].amount").value("30.00"))
        .andExpect(jsonPath("$.data.financeFlows[2].flowNo").value(Matchers.startsWith("WA")))
        .andExpect(jsonPath("$.data.financeFlows[2].direction").value("DECREASE"))
        .andExpect(jsonPath("$.data.financeFlows[2].amount").value("100.00"))
        .andExpect(jsonPath("$.data.financeFlows[2].transferredAt").value(PAID_OUT_JSON))
        .andExpect(jsonPath("$.data.financeFlows[2].withdrawalNo").value(first.applicationNo()))
        .andExpect(jsonPath("$.data.financeFlows[3].flowNo").value(Matchers.startsWith("WA")))
        .andExpect(jsonPath("$.data.financeFlows[3].amount").value("20.00"))
        .andExpect(jsonPath("$.data.financeFlows[3].transferredAt").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.financeFlows[3].withdrawalNo").value(second.applicationNo()));

    // A staff viewer holding commission:view sees the same financial blocks.
    String financeStaff =
        staffToken(
            "t72_finance_" + UUID.randomUUID().toString().substring(0, 8),
            List.of("order:view", "commission:view"));
    mvc.perform(
            get("/admin-api/order/orders/{id}/chain", orderId)
                .header("X-Admin-Token", financeStaff))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.funds.kolCommission").value("120.00"))
        .andExpect(jsonPath("$.data.lines[0].commission.netCommission").value("120.00"))
        .andExpect(jsonPath("$.data.financeFlows[2].withdrawalNo").value(first.applicationNo()));

    // A staff viewer without commission:view gets the wiring but no financial data.
    String orderViewer =
        staffToken(
            "t72_orders_" + UUID.randomUUID().toString().substring(0, 8), List.of("order:view"));
    mvc.perform(
            get("/admin-api/order/orders/{id}/chain", orderId).header("X-Admin-Token", orderViewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.funds").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.financeFlows").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.lines[0].commission").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.lines[0].orderItemId").value(String.valueOf(item1)));
  }

  @Test
  void shouldHideOrderAmountsFromOwnViewerOnMixedAttribution() throws Exception {
    sync("500.00", "0.00");
    releaseJob.releaseDue();
    long bd1 = createStaff("t72_bd1_" + UUID.randomUUID().toString().substring(0, 8), "OWN");
    long bd2 = createStaff("t72_bd2_" + UUID.randomUUID().toString().substring(0, 8), "OWN");
    jdbc.update("UPDATE crm_kol SET bd_user_id = ? WHERE id = ?", bd1, kolA);
    jdbc.update("UPDATE crm_kol SET bd_user_id = ? WHERE id = ?", bd2, kolB);
    jdbc.update("UPDATE crm_order SET bd_user_id = ? WHERE id = ?", bd1, orderId);
    // Mixed attribution: item2 moves to another BD's creator.
    jdbc.update("UPDATE crm_order_item SET kol_id = ? WHERE id = ?", kolB, item2);

    String bd1Token = CompanySessions.login(mvc, json, usernameOf(bd1), PASSWORD);
    mvc.perform(
            get("/admin-api/order/orders/{id}/chain", orderId).header("X-Admin-Token", bd1Token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.lines[0].orderItemId").value(String.valueOf(item1)))
        .andExpect(jsonPath("$.data.funds").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.financeFlows").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.lines[0].commission").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.lines[1].commission").value(Matchers.nullValue()));

    // The other BD holds no snapshot of the order (the order-level OWN gate already rejects).
    String bd2Token = CompanySessions.login(mvc, json, usernameOf(bd2), PASSWORD);
    mvc.perform(
            get("/admin-api/order/orders/{id}/chain", orderId).header("X-Admin-Token", bd2Token))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldShowOrderAmountsToOwnViewerCoveringEveryAttributedLine() throws Exception {
    sync("500.00", "0.00");
    releaseJob.releaseDue();
    long bd = createStaff("t72_bd_ok_" + UUID.randomUUID().toString().substring(0, 8), "OWN");
    jdbc.update("UPDATE crm_kol SET bd_user_id = ? WHERE id = ?", bd, kolA);
    jdbc.update("UPDATE crm_order SET bd_user_id = ? WHERE id = ?", bd, orderId);

    String token = CompanySessions.login(mvc, json, usernameOf(bd), PASSWORD);
    // Unattributed lines never block the coverage (same basis as the settlement details).
    mvc.perform(get("/admin-api/order/orders/{id}/chain", orderId).header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.funds.kolCommission").value("150.00"))
        .andExpect(jsonPath("$.data.funds.accountStatus").value("UNVERIFIED"))
        .andExpect(jsonPath("$.data.lines[0].commission.netCommission").value("150.00"))
        .andExpect(jsonPath("$.data.lines[1].commission.netCommission").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.data.financeFlows[0].amount").value("150.00"));
  }

  @Test
  void shouldKeepPayTimeRateSnapshot_afterTheCreatorRateChanges() throws Exception {
    sync("500.00", "0.00");
    releaseJob.releaseDue();
    // A later promotion config (effective after the pay time) must not re-price the paid line:
    // the snapshot keeps the pay-time revision, never the creator's current rate.
    insertPromotionConfig(kolA, "0.5000", PAID_AT.plusSeconds(3600));
    sync("500.00", "0.00");

    mvc.perform(
            get("/admin-api/order/orders/{id}/chain", orderId).header("X-Admin-Token", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.lines[0].commission.rateSnapshot").value("0.3000"))
        .andExpect(jsonPath("$.data.lines[0].commission.netCommission").value("150.00"))
        .andExpect(jsonPath("$.data.funds.kolCommission").value("150.00"));
  }

  @Test
  void shouldIsolateCompanies_onTheChain() throws Exception {
    var other =
        CompanySessions.openCompany(
            mvc,
            json,
            PASSWORD,
            "t72x" + (System.nanoTime() % 100_000_000L),
            "t72-other-" + UUID.randomUUID().toString().substring(0, 8));
    mvc.perform(
            get("/admin-api/order/orders/{id}/chain", orderId)
                .header("X-Admin-Token", other.token()))
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------ helpers

  private String staffToken(String username, List<String> permissions) throws Exception {
    createStaff(username, "ALL", permissions);
    return CompanySessions.login(mvc, json, username, PASSWORD);
  }

  private long createStaff(String username, String scope) throws Exception {
    return createStaff(username, scope, List.of("order:view", "commission:view"));
  }

  private long createStaff(String username, String scope, List<String> permissions)
      throws Exception {
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
                                "nickname", username,
                                "role", "STAFF",
                                "postTemplate", "BD",
                                "dataScope", scope,
                                "permissions", permissions))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(json.readTree(body).at("/data/id").asText());
  }

  private String usernameOf(long userId) {
    return jdbc.queryForObject("SELECT username FROM sys_user WHERE id = ?", String.class, userId);
  }

  private void lowerMinimumWithdrawal() throws Exception {
    int version =
        json.readTree(
                mvc.perform(
                        get("/admin-api/commission/settlement-configs/current")
                            .header("X-Admin-Token", adminToken))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/version")
            .asInt();
    mvc.perform(
            put("/admin-api/commission/settlement-configs/current")
                .header("X-Admin-Token", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("version", version, "minimum", "10.00", "days", 7))))
        .andExpect(status().isOk());
  }

  /**
   * Refreshes both item snapshots of the order in one call: the sync engine logically deletes lines
   * missing from the list.
   */
  private void sync(String item1Basis, String item1Refund) {
    TenantScope.runCompany(
        TENANT_ID,
        () ->
            snapshots.syncOrderItems(
                new OrderSourceDTO(orderId, 7201L, PAID_AT),
                List.of(
                    new ItemSourceDTO(
                        item1,
                        "SUB-" + item1,
                        kolA,
                        1,
                        null,
                        new BigDecimal(item1Basis),
                        Boolean.FALSE,
                        new BigDecimal(item1Refund),
                        "CONFIRMED",
                        SETTLED_AT,
                        null,
                        false),
                    new ItemSourceDTO(
                        item2,
                        "SUB-" + item2,
                        null,
                        0,
                        null,
                        null,
                        null,
                        null,
                        "UNKNOWN",
                        null,
                        null,
                        false))));
  }

  private static long nextId() {
    return FIXTURE_IDS.incrementAndGet();
  }

  private void insertShop(long id) {
    Integer existing =
        jdbc.queryForObject(
            "SELECT count(*) FROM crm_shop WHERE tenant_id = ? AND id = ?",
            Integer.class,
            TENANT_ID,
            id);
    if (existing == null || existing == 0) {
      jdbc.update(
          "INSERT INTO crm_shop(id,tenant_id,shop_code,shop_name,weidian_shop_id,create_time,update_time)"
              + " VALUES(?,?,?,?,?,now(),now())",
          id,
          TENANT_ID,
          "SHOP-007201",
          "t72 Shop",
          "7201");
    }
  }

  private long insertKol() {
    long id = nextId();
    // KOL- + 3 letters + 8 digits keeps the 16-char code constraint and its format check.
    String code = String.format("KOL-TAA%08d", id % 100_000_000L);
    jdbc.update(
        "INSERT INTO crm_kol(id,tenant_id,kol_code,name,email,country,status,review_status,"
            + "create_time,update_time) VALUES(?,?,?,?,?,'US',1,'APPROVED',now(),now())",
        id,
        TENANT_ID,
        code,
        "t72 kol " + id,
        "t72-" + id + "@example.invalid");
    return id;
  }

  private void insertPromotionConfig(long kolId, String rate, Instant effectiveTime) {
    jdbc.update(
        "INSERT INTO crm_kol_promotion_record(id,tenant_id,kol_id,change_type,before_json,"
            + "after_json,version_no,effective_time,create_time) VALUES(?,?,?,'CONFIG','{}'::jsonb,"
            + "?::jsonb,1,?,now())",
        nextId(),
        TENANT_ID,
        kolId,
        "{\"defaultRate\":\"" + rate + "\",\"configVersion\":1}",
        Timestamp.from(effectiveTime));
  }

  private void insertOrder(long id, Long bdUserId, String orderNo) {
    jdbc.update(
        "INSERT INTO crm_order(id,tenant_id,shop_id,weidian_order_id,kol_id,bd_user_id,"
            + "attribution_status,order_status,raw_status,order_time,pay_time,total_amount,"
            + "pay_amount,freight,discount,source,last_sync_time,create_time,update_time) VALUES"
            + "(?,?,?,?,?,?,'ATTRIBUTED','PAID','PAID',now(),?,200,200,0,0,1,now(),now(),now())",
        id,
        TENANT_ID,
        7201L,
        orderNo,
        null,
        bdUserId,
        Timestamp.from(PAID_AT));
  }

  private void insertItem(long id, long orderId, Long kolId, String payAmount) {
    jdbc.update(
        "INSERT INTO crm_order_item(id,tenant_id,order_id,sub_order_id,weidian_item_id,sku_id,"
            + "item_name,sku_name,quantity,pay_amount,refund_amount,kol_id,attribution_type,"
            + "create_time,update_time) VALUES(?,?,?,?,?,'sku','t72 item','',1,?::numeric,0,?,"
            + "?,now(),now())",
        id,
        TENANT_ID,
        orderId,
        "SUB-" + id,
        "WDI-" + id,
        payAmount,
        kolId,
        kolId == null ? 0 : 1);
  }

  private void insertRefund(long orderId, long itemId, String no, String amount) {
    jdbc.update(
        "INSERT INTO crm_refund(id,tenant_id,order_id,order_item_id,weidian_refund_no,amount,"
            + "raw_status,refund_type,refund_status,refund_time,finish_time,create_time,update_time)"
            + " VALUES(?,?,?,?,?,?::numeric,'10','REFUND_ONLY','SUCCESS',?,?,now(),now())",
        nextId(),
        TENANT_ID,
        orderId,
        itemId,
        no,
        amount,
        Timestamp.from(PAID_AT.plusSeconds(60)),
        Timestamp.from(PAID_AT.plusSeconds(120)));
  }

  private long operatorId() {
    return jdbc.queryForObject(
        "SELECT id FROM sys_user WHERE username = ?", Long.class, "bootstrap");
  }

  private KolAccountDTO kol(long id) {
    return new KolAccountDTO(
        id,
        "KOL-T72-" + id,
        "T72 Creator",
        "t72-" + id + "@example.invalid",
        "US",
        null,
        1,
        1,
        0,
        null,
        TENANT_ID,
        "T72",
        true);
  }

  private static AccountDTO superAdmin(long id) {
    return new AccountDTO(
        id,
        "t72_admin",
        null,
        "T72 Admin",
        null,
        Role.SUPER_ADMIN,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        TENANT_ID,
        "T72",
        true,
        Set.of(),
        null);
  }

  private static byte[] voucherPng() {
    return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 't', '7', '2'};
  }
}
