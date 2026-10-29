package com.tcrm.admin.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.commission.job.CommissionReleaseJob;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.integration.api.WeidianBusinessApi.TradeDetailItem;
import com.tcrm.admin.integration.api.WeidianBusinessApi.TradeDetailRequest;
import com.tcrm.admin.integration.api.WeidianBusinessApi.TradeDetailResponse;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.service.OrderCommissionSync;
import com.tcrm.admin.order.service.OrderSettlementFlowService;
import com.tcrm.admin.order.service.OrderSettlementSyncCoordinator;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 76 end-to-end coverage: the settlement flow pull feeds the commission basis, snapshots
 * verify and accrue, the release job credits the wallet, and repeat pulls stay idempotent. The
 * Weidian API is stubbed at the {@code integration} boundary; amounts follow the default unit
 * constant (fen), pending the M0 step 11 confirmation.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OrderSettlementFlowIT {
  private static final long TENANT_ID = 1L;
  private static final long SHOP_ID = 10L;
  private static final long DISABLED_SHOP_ID = 30L;
  private static final String PASSWORD = "t76-pass-" + UUID.randomUUID();
  private static final Instant PAY_TIME = Instant.parse("2026-10-20T08:00:00Z");
  private static final Instant INCOME_TIME = Instant.parse("2026-10-27T08:00:00Z");
  private static final AtomicLong FIXTURE_IDS = new AtomicLong(7_600_000_000_000L);
  private static final AtomicLong KOL_SEQ = new AtomicLong(1);

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

  @MockitoBean WeidianBusinessApi remote;

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OrderSettlementFlowService flows;
  @Autowired private OrderSettlementSyncCoordinator coordinator;
  @Autowired private CommissionReleaseJob releaseJob;
  @Autowired private WithdrawalService withdrawals;
  @Autowired private OrderCommissionSync commissionSync;
  @Autowired private OrderMapper orderMapper;
  @Autowired private OrderItemMapper itemMapper;
  @Autowired private TransactionTemplate tx;

  private long kolId;
  private long orderId;
  private long itemA;
  private long itemB;

  @BeforeEach
  void prepare() throws Exception {
    CompanySessions.companyAdmin(mvc, json, PASSWORD);
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_code,shop_name,weidian_shop_id,status,create_time,update_time)"
            + " VALUES(?,1,'SHOP-760001','t76 shop','10',1,now(),now()) ON CONFLICT DO NOTHING",
        SHOP_ID);
    jdbc.update(
        "INSERT INTO crm_weidian_shop_auth(id,tenant_id,shop_id,auth_mode,status,create_time,update_time)"
            + " VALUES(10,1,10,'SERVICE','AUTHORIZED',now(),now()) ON CONFLICT DO NOTHING");
    kolId = insertKol("0.1000");
    insertPromotionConfig(kolId, "0.1000", PAY_TIME.minusSeconds(3600));
    long[] ids = insertOrder("t76_wd_order_1", kolId, "6.00", "4.00", "2.00");
    orderId = ids[0];
    itemA = ids[1];
    itemB = ids[2];
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM crm_order_settlement_flow WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_commission_item_revision WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_commission_item_state WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_commission_ledger WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_commission_wallet WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_commission_rule_version WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_kol_promotion_record WHERE tenant_id = ?", TENANT_ID);
    jdbc.update(
        "DELETE FROM crm_order_item WHERE tenant_id = ? AND order_id = ?", TENANT_ID, orderId);
    jdbc.update("DELETE FROM crm_order WHERE tenant_id = ? AND id = ?", TENANT_ID, orderId);
    jdbc.update("DELETE FROM crm_kol WHERE tenant_id = ? AND id = ?", TENANT_ID, kolId);
    jdbc.update("DELETE FROM crm_order_sync_state WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_weidian_shop_auth WHERE tenant_id = ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_shop WHERE tenant_id = ?", TENANT_ID);
    jdbc.update(
        "DELETE FROM sys_config WHERE config_key = 'weidian.settlement.sync-interval-minutes'");
    jdbc.update("DELETE FROM crm_order_sync_state WHERE tenant_id <> ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_weidian_shop_auth WHERE tenant_id <> ?", TENANT_ID);
    jdbc.update("DELETE FROM crm_shop WHERE tenant_id <> ?", TENANT_ID);
  }

  @Test
  void shouldVerifyAccrueReleaseAndExposeAvailableAmount() throws Exception {
    // Hand-computed (unit = fen): income 600 == pay 6.00 confirms the basis; fee -4 never enters
    // it (task 81). Lines pay 4.00/2.00 -> basis 4.00/2.00; at 10% the accrual is 0.40/0.20.
    stubIncomeDetail(incomeRow("600"), row("支出", "交易手续费", "-4"));

    pull();

    assertThat(flowCount()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_order_settlement_flow WHERE order_id = ?",
                Integer.class,
                orderId))
        .isEqualTo(2);
    assertThat(orderField("settled_time")).isEqualTo(Timestamp.from(INCOME_TIME));
    assertThat(orderField("commission_basis_reason")).isNull();
    assertThat(itemField(itemA, "verified")).isEqualTo(true);
    assertThat(itemField(itemA, "basis_cny")).isEqualTo(new BigDecimal("4.00"));
    assertThat(itemField(itemA, "accrued_commission_cny")).isEqualTo(new BigDecimal("0.40"));
    assertThat(itemField(itemA, "release_basis")).isEqualTo("CONFIRMED");
    assertThat(itemField(itemA, "release_at")).isEqualTo(Timestamp.from(INCOME_TIME));
    assertThat(itemField(itemB, "basis_cny")).isEqualTo(new BigDecimal("2.00"));
    assertThat(itemField(itemB, "accrued_commission_cny")).isEqualTo(new BigDecimal("0.20"));

    releaseJob.releaseDue();

    // The release posts one credit per item line: 0.40 + 0.20 = 0.60.
    assertThat(walletBalance()).isEqualByComparingTo("0.60");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_commission_ledger WHERE kol_id = ?"
                    + " AND entry_type = 'COMMISSION_CREDIT' AND amount_cny IN (0.40, 0.20)",
                Integer.class,
                kolId))
        .isEqualTo(2);
    assertThat(
            TenantScope.callCompany(
                TENANT_ID, () -> withdrawals.conditions(TENANT_ID, kolId).available()))
        .isEqualTo("0.60");
  }

  @Test
  void shouldKeepAccrualUnreleasedWithIncomeMismatchWhenUnitIsWrong() throws Exception {
    // The API returned yuan while we assume fen: income 6 -> 0.06 != pay 6.00, so the goods income
    // does not confirm. The pay-time accrual stays (4.00/2.00), but release never starts (reason
    // INCOME_MISMATCH) and nothing reaches the wallet.
    stubIncomeDetail(row("收入", "货款收入", "6"), row("支出", "交易手续费", "-0.04"));

    pull();

    assertThat(flowCount()).isEqualTo(2);
    assertThat(itemField(itemA, "verified")).isEqualTo(true);
    assertThat(itemField(itemA, "basis_cny")).isEqualTo(new BigDecimal("4.00"));
    assertThat(itemField(itemA, "accrued_commission_cny")).isEqualTo(new BigDecimal("0.40"));
    assertThat(itemField(itemA, "release_basis")).isEqualTo("UNSETTLED");
    assertThat(orderField("commission_basis_reason")).isEqualTo("INCOME_MISMATCH");
    assertThat(orderField("settled_time")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT evidence FROM crm_commission_item_revision WHERE item_state_id ="
                    + " (SELECT id FROM crm_commission_item_state WHERE order_item_id = ?)"
                    + " ORDER BY id DESC LIMIT 1",
                String.class,
                itemA))
        .contains("release UNSETTLED");

    releaseJob.releaseDue();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_commission_ledger WHERE kol_id = ?",
                Integer.class,
                kolId))
        .isZero();
  }

  @Test
  void shouldNotChangeBasisWhenIncomeDetailRefundAppearsAfterRelease() throws Exception {
    stubIncomeDetail(incomeRow("600"), row("支出", "交易手续费", "-4"));
    pull();
    releaseJob.releaseDue();
    assertThat(walletBalance()).isEqualByComparingTo("0.60");

    // The backfill re-observes the window and now sees a partial refund of -60 fen. Task 81: the
    // basis stays the goods income, and refunds reach commission only through the refund module,
    // so no revision and no COMMISSION_ADJUSTMENT land here.
    stubIncomeDetail(incomeRow("600"), row("支出", "交易手续费", "-4"), row("支出", "退款", "-60"));
    pull();

    assertThat(flowCount()).isEqualTo(3);
    assertThat(itemField(itemA, "basis_cny")).isEqualTo(new BigDecimal("4.00"));
    assertThat(itemField(itemA, "accrued_commission_cny")).isEqualTo(new BigDecimal("0.40"));
    assertThat(itemField(itemB, "accrued_commission_cny")).isEqualTo(new BigDecimal("0.20"));
    assertThat(walletBalance()).isEqualByComparingTo("0.60");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_commission_ledger WHERE kol_id = ?"
                    + " AND entry_type = 'COMMISSION_ADJUSTMENT'",
                Integer.class,
                kolId))
        .isZero();
  }

  @Test
  void shouldNotDuplicateFlowsRevisionsOrWalletPostingsOnRepeatPull() throws Exception {
    stubIncomeDetail(incomeRow("600"), row("支出", "交易手续费", "-4"));
    pull();
    releaseJob.releaseDue();

    // Same window, same rows: the fingerprints dedup, no new revision, no second credit.
    pull();
    releaseJob.releaseDue();

    assertThat(flowCount()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_commission_item_revision WHERE item_state_id ="
                    + " (SELECT id FROM crm_commission_item_state WHERE order_item_id = ?)",
                Integer.class,
                itemA))
        .isEqualTo(1);
    assertThat(walletBalance()).isEqualByComparingTo("0.60");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_commission_ledger WHERE kol_id = ?"
                    + " AND entry_type = 'COMMISSION_CREDIT'",
                Integer.class,
                kolId))
        .isEqualTo(2);
  }

  @Test
  void shouldAppendOneRevisionAndPostOnceWhenSettlementArrivesAfterPaidAccrual() throws Exception {
    // Paid order, no settlement flow yet: the commission accrues from the pay amount (basis
    // 4.00/2.00 -> 0.40/0.20) and stays unreleased, so the wallet is untouched.
    syncOrderFromDb();

    assertThat(itemField(itemA, "verified")).isEqualTo(true);
    assertThat(itemField(itemA, "basis_cny")).isEqualTo(new BigDecimal("4.00"));
    assertThat(itemField(itemA, "accrued_commission_cny")).isEqualTo(new BigDecimal("0.40"));
    assertThat(itemField(itemA, "release_basis")).isEqualTo("UNSETTLED");
    assertThat(itemField(itemB, "basis_cny")).isEqualTo(new BigDecimal("2.00"));
    assertThat(itemField(itemB, "accrued_commission_cny")).isEqualTo(new BigDecimal("0.20"));
    assertThat(itemField(itemB, "release_basis")).isEqualTo("UNSETTLED");
    assertThat(orderField("commission_basis_reason")).isEqualTo("NO_SETTLEMENT");

    releaseJob.releaseDue();
    assertThat(walletBalanceOrZero()).isEqualByComparingTo("0.00");

    long revisionsA = revisionCount(itemA);
    long revisionsB = revisionCount(itemB);

    // The goods-income flow arrives: it confirms the basis (no new basis, no second posting).
    stubIncomeDetail(incomeRow("600"), row("支出", "交易手续费", "-4"));
    pull();

    assertThat(revisionCount(itemA)).isEqualTo(revisionsA + 1);
    assertThat(revisionCount(itemB)).isEqualTo(revisionsB + 1);
    assertThat(itemField(itemA, "basis_cny")).isEqualTo(new BigDecimal("4.00"));
    assertThat(itemField(itemA, "accrued_commission_cny")).isEqualTo(new BigDecimal("0.40"));
    assertThat(itemField(itemA, "release_basis")).isEqualTo("CONFIRMED");
    assertThat(itemField(itemB, "basis_cny")).isEqualTo(new BigDecimal("2.00"));
    assertThat(itemField(itemB, "accrued_commission_cny")).isEqualTo(new BigDecimal("0.20"));
    assertThat(itemField(itemB, "release_basis")).isEqualTo("CONFIRMED");
    assertThat(orderField("commission_basis_reason")).isNull();

    releaseJob.releaseDue();

    assertThat(walletBalance()).isEqualByComparingTo("0.60");
    assertThat(creditCount()).isEqualTo(2);
    assertThat(creditTotal()).isEqualByComparingTo("0.60");

    // Repeat pull with the same flows: no new revision, no new posting.
    long ledgerBefore = creditCount();
    pull();

    assertThat(revisionCount(itemA)).isEqualTo(revisionsA + 1);
    assertThat(revisionCount(itemB)).isEqualTo(revisionsB + 1);
    assertThat(creditCount()).isEqualTo(ledgerBefore);
  }

  @Test
  void shouldSaveUnmatchedFlowsWithoutCommissionEffects() {
    // The unknown order id is stored verbatim with no local order id and never counted; our own
    // order keeps its NO_SETTLEMENT reason and stays unverified.
    when(remote.tradeDetail(anyLong(), argThat(r -> "PRE_SETTLE_REFUND".equals(queryType(r)))))
        .thenReturn(emptyResponse());
    when(remote.tradeDetail(anyLong(), argThat(r -> "INCOME_DETAIL".equals(queryType(r)))))
        .thenReturn(
            response(
                List.of(
                    new TradeDetailItem(
                        "t76_unknown_order",
                        null,
                        "收入",
                        "货款收入",
                        new BigDecimal("600"),
                        INCOME_TIME,
                        "",
                        null),
                    new TradeDetailItem(
                        "t76_unknown_order",
                        null,
                        "支出",
                        "交易手续费",
                        new BigDecimal("-4"),
                        INCOME_TIME,
                        "",
                        null))));

    pull();

    assertThat(flowCount()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_order_settlement_flow WHERE weidian_order_id ="
                    + " 't76_unknown_order' AND order_id IS NULL",
                Integer.class))
        .isEqualTo(2);
    // No flow ever matched our order, so nothing was triggered for it: no snapshot row exists
    // and the basis check never ran.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_commission_item_state WHERE order_id = ?",
                Integer.class,
                orderId))
        .isZero();
    assertThat(orderField("commission_basis_reason")).isNull();
  }

  @Test
  void shouldSkipDisabledAndUnauthorizedShopsAndIsolateCompanies() throws Exception {
    var other = CompanySessions.openCompany(mvc, json, PASSWORD, "t76-settle-co", "t76-admin");
    var disabledCo =
        CompanySessions.openCompany(mvc, json, PASSWORD, "t76-settle-off", "t76-off-admin");
    String platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    // A disabled company is skipped entirely, like a disabled or unauthorized shop.
    mvc.perform(
            put("/admin-api/platform/tenants/" + disabledCo.tenantId() + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}")
                .header("X-Admin-Token", platform))
        .andExpect(status().isOk());
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_code,shop_name,weidian_shop_id,status,create_time,update_time)"
            + " VALUES(?,?,'SHOP-760002','t76 disabled shop','30',0,now(),now())",
        DISABLED_SHOP_ID,
        TENANT_ID);
    jdbc.update(
        "INSERT INTO crm_weidian_shop_auth(id,tenant_id,shop_id,auth_mode,status,create_time,update_time)"
            + " VALUES(30,?,30,'SERVICE','AUTHORIZED',now(),now())",
        TENANT_ID);
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_code,shop_name,weidian_shop_id,create_time,update_time)"
            + " VALUES(40,?,'SHOP-760003','t76 other company','40',now(),now())",
        other.tenantId());
    jdbc.update(
        "INSERT INTO crm_weidian_shop_auth(id,tenant_id,shop_id,auth_mode,status,create_time,update_time)"
            + " VALUES(40,?,40,'SERVICE','AUTHORIZED',now(),now())",
        other.tenantId());
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_code,shop_name,weidian_shop_id,create_time,update_time)"
            + " VALUES(50,?,'SHOP-760004','t76 disabled company','50',now(),now())",
        disabledCo.tenantId());
    jdbc.update(
        "INSERT INTO crm_weidian_shop_auth(id,tenant_id,shop_id,auth_mode,status,create_time,update_time)"
            + " VALUES(50,?,50,'SERVICE','AUTHORIZED',now(),now())",
        disabledCo.tenantId());
    when(remote.tradeDetail(anyLong(), any())).thenReturn(emptyResponse());

    coordinator.scheduled();

    ArgumentCaptor<Long> shopIds = ArgumentCaptor.forClass(Long.class);
    verify(remote, org.mockito.Mockito.atLeastOnce()).tradeDetail(shopIds.capture(), any());
    assertThat(shopIds.getAllValues()).contains(SHOP_ID, 40L).doesNotContain(DISABLED_SHOP_ID, 50L);
    // Each shop's pull checkpoints are stamped inside its own company.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_order_sync_state WHERE shop_id IN (10, 40)"
                    + " AND (settlement_pull_at IS NOT NULL OR settlement_backfill_at IS NOT NULL)",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void shouldApplyChangedSyncIntervalWithoutRestart() throws Exception {
    String platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    when(remote.tradeDetail(anyLong(), any())).thenReturn(emptyResponse());
    jdbc.update("DELETE FROM crm_order_sync_state WHERE shop_id = ?", SHOP_ID);
    jdbc.update(
        "INSERT INTO crm_order_sync_state(id,tenant_id,shop_id,settlement_pull_at,"
            + " settlement_backfill_at,create_time,update_time) VALUES(11,1,10,?,?,now(),now())",
        Timestamp.from(Instant.now().minusSeconds(20 * 60)),
        Timestamp.from(Instant.now()));

    // 20 minutes since the last pull: not due under the default 60-minute interval.
    coordinator.scheduled();
    verify(remote, never()).tradeDetail(anyLong(), any());

    // Platform admin shortens the interval to 15 minutes; the next round pulls without restart.
    mvc.perform(
            put("/admin-api/platform/system-configs/weidian.settlement.sync-interval-minutes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"15\"}")
                .header("X-Admin-Token", platform))
        .andExpect(status().isOk());

    coordinator.scheduled();

    ArgumentCaptor<Long> shopIds = ArgumentCaptor.forClass(Long.class);
    verify(remote, org.mockito.Mockito.atLeastOnce()).tradeDetail(shopIds.capture(), any());
    assertThat(shopIds.getAllValues()).contains(SHOP_ID);
  }

  @Test
  void shouldPageThroughWindowsUntilTotalIsReached() {
    // Page 1 is full (100 rows) so the pull must ask for page 2 and stop at the reported total
    // (102 rows): one goods-income row plus 101 distinct fee rows.
    java.util.List<TradeDetailItem> page1 = new ArrayList<>();
    page1.add(incomeRow("600"));
    for (int i = 1; i <= 99; i++) {
      page1.add(row("支出", "交易手续费", "-" + i));
    }
    java.util.List<TradeDetailItem> page2 =
        List.of(row("支出", "交易手续费", "-100"), row("支出", "交易手续费", "-101"));
    when(remote.tradeDetail(anyLong(), argThat(r -> "INCOME_DETAIL".equals(queryType(r)))))
        .thenAnswer(
            invocation -> {
              TradeDetailRequest request = invocation.getArgument(1);
              // Both pages report the window total (102), like the real API.
              return new TradeDetailResponse(
                  new ArrayList<>(request.pageNo() == 1 ? page1 : page2), 102, null);
            });
    when(remote.tradeDetail(anyLong(), argThat(r -> "PRE_SETTLE_REFUND".equals(queryType(r)))))
        .thenReturn(emptyResponse());

    pull();

    assertThat(flowCount()).isEqualTo(102);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_order_settlement_flow WHERE weidian_order_id ="
                    + " 't76_wd_order_1' AND order_id = ?",
                Integer.class,
                orderId))
        .isEqualTo(102);
  }

  private void pull() {
    TenantScope.runCompany(
        TENANT_ID,
        () ->
            flows.pull(
                SHOP_ID, INCOME_TIME.minusSeconds(3600), INCOME_TIME.plusSeconds(3600), null));
  }

  /**
   * Review finding 1: a pull that dies mid-way (flows already stored) must not strand the order —
   * the next successful pull recovers the recompute set from the persisted flows, because the
   * checkpoints never advanced past them.
   */
  @Test
  void shouldRecomputeFlowsLeftByFailedPullOnNextRound() {
    // Round 1: INCOME_DETAIL stores its flows, then PRE_SETTLE_REFUND blows up mid-pull.
    when(remote.tradeDetail(anyLong(), argThat(r -> "INCOME_DETAIL".equals(queryType(r)))))
        .thenReturn(response(List.of(incomeRow("600"), row("支出", "交易手续费", "-4"))));
    when(remote.tradeDetail(anyLong(), argThat(r -> "PRE_SETTLE_REFUND".equals(queryType(r)))))
        .thenThrow(new RuntimeException("network glitch"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                TenantScope.runCompany(
                    TENANT_ID,
                    () ->
                        flows.pull(
                            SHOP_ID,
                            INCOME_TIME.minusSeconds(3600),
                            INCOME_TIME.plusSeconds(3600),
                            null)))
        .isInstanceOf(RuntimeException.class);

    // The flows are persisted but the order was never recomputed: no snapshot exists yet.
    assertThat(flowCount()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_commission_item_state WHERE order_id = ?",
                Integer.class,
                orderId))
        .isZero();

    // Round 2: everything succeeds; the stored fingerprints are no longer new, yet the order
    // must still be recomputed from the persisted state.
    stubIncomeDetail(incomeRow("600"), row("支出", "交易手续费", "-4"));

    Boolean ok =
        TenantScope.callCompany(
            TENANT_ID,
            () ->
                flows.pull(
                    SHOP_ID, INCOME_TIME.minusSeconds(3600), INCOME_TIME.plusSeconds(3600), null));

    assertThat(ok).isTrue();
    assertThat(itemField(itemA, "verified")).isEqualTo(true);
    assertThat(itemField(itemA, "basis_cny")).isEqualTo(new BigDecimal("4.00"));
    assertThat(itemField(itemA, "accrued_commission_cny")).isEqualTo(new BigDecimal("0.40"));
    assertThat(itemField(itemA, "release_basis")).isEqualTo("CONFIRMED");
    assertThat(orderField("settled_time")).isEqualTo(Timestamp.from(INCOME_TIME));
    assertThat(orderField("commission_basis_reason")).isNull();
  }

  private void stubIncomeDetail(TradeDetailItem... rows) {
    when(remote.tradeDetail(anyLong(), argThat(r -> "INCOME_DETAIL".equals(queryType(r)))))
        .thenReturn(response(List.of(rows)));
    when(remote.tradeDetail(anyLong(), argThat(r -> "PRE_SETTLE_REFUND".equals(queryType(r)))))
        .thenReturn(emptyResponse());
  }

  private static String queryType(TradeDetailRequest request) {
    return request == null ? null : request.queryType();
  }

  private static TradeDetailResponse response(List<TradeDetailItem> rows) {
    return new TradeDetailResponse(new ArrayList<>(rows), rows.size(), null);
  }

  private static TradeDetailResponse emptyResponse() {
    return new TradeDetailResponse(List.of(), 0, null);
  }

  private static TradeDetailItem incomeRow(String fen) {
    return row("收入", "货款收入", fen);
  }

  private static TradeDetailItem row(String tradeType, String subType, String fen) {
    return new TradeDetailItem(
        "t76_wd_order_1", null, tradeType, subType, new BigDecimal(fen), INCOME_TIME, "", null);
  }

  private int flowCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM crm_order_settlement_flow WHERE tenant_id = ?",
        Integer.class,
        TENANT_ID);
  }

  private Object orderField(String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM crm_order WHERE id = ?", Object.class, orderId);
  }

  private Object itemField(long itemId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM crm_commission_item_state WHERE order_item_id = ?",
        Object.class,
        itemId);
  }

  /** Runs the order commission sync the way the order write path does: in a transaction. */
  private void syncOrderFromDb() {
    TenantScope.runCompany(
        TENANT_ID,
        () ->
            tx.executeWithoutResult(
                status -> {
                  OrderDO order = orderMapper.selectById(orderId);
                  List<OrderItemDO> lines =
                      itemMapper.selectList(
                          new LambdaQueryWrapper<OrderItemDO>()
                              .eq(OrderItemDO::getOrderId, orderId)
                              .orderByAsc(OrderItemDO::getId));
                  commissionSync.sync(order, lines);
                }));
  }

  private long revisionCount(long orderItemId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM crm_commission_item_revision WHERE item_state_id ="
            + " (SELECT id FROM crm_commission_item_state WHERE order_item_id = ?)",
        Long.class,
        orderItemId);
  }

  private int creditCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM crm_commission_ledger WHERE kol_id = ?"
            + " AND entry_type = 'COMMISSION_CREDIT'",
        Integer.class,
        kolId);
  }

  private BigDecimal creditTotal() {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(amount_cny), 0) FROM crm_commission_ledger WHERE kol_id = ?"
            + " AND entry_type = 'COMMISSION_CREDIT'",
        BigDecimal.class,
        kolId);
  }

  /** Before the first posting the wallet row may not exist yet; that reads as zero. */
  private BigDecimal walletBalanceOrZero() {
    List<BigDecimal> balances =
        jdbc.queryForList(
            "SELECT balance_cny FROM crm_commission_wallet WHERE tenant_id = ? AND kol_id = ?",
            BigDecimal.class,
            TENANT_ID,
            kolId);
    return balances.isEmpty() ? BigDecimal.ZERO : balances.getFirst();
  }

  private BigDecimal walletBalance() {
    return jdbc.queryForObject(
        "SELECT balance_cny FROM crm_commission_wallet WHERE tenant_id = ? AND kol_id = ?",
        BigDecimal.class,
        TENANT_ID,
        kolId);
  }

  private long insertKol(String rate) {
    long id = Math.abs(UUID.randomUUID().getMostSignificantBits());
    jdbc.update(
        "INSERT INTO crm_kol(id,tenant_id,kol_code,name,email,country,status,review_status,"
            + "default_rate,create_time,update_time) VALUES(?,?,?,?,'t76.kol@example.test','US',1,"
            + "'APPROVED',?::numeric,now(),now())",
        id,
        TENANT_ID,
        String.format("KOL-TSA%03d", KOL_SEQ.incrementAndGet()),
        "t76 kol",
        rate);
    return id;
  }

  private void insertPromotionConfig(long kolId, String rate, Instant effectiveTime) {
    jdbc.update(
        "INSERT INTO crm_kol_promotion_record(id,tenant_id,kol_id,change_type,before_json,"
            + "after_json,version_no,effective_time,create_time) VALUES(?,?,?,'CONFIG','{}'::jsonb,"
            + "?::jsonb,1,?,now())",
        Math.abs(UUID.randomUUID().getMostSignificantBits()),
        TENANT_ID,
        kolId,
        "{\"defaultRate\":\"" + rate + "\",\"configVersion\":1}",
        Timestamp.from(effectiveTime));
  }

  private long[] insertOrder(
      String orderNo, long kolId, String pay, String itemPayA, String itemPayB) {
    long orderId = Math.abs(UUID.randomUUID().getMostSignificantBits());
    long itemA = Math.abs(UUID.randomUUID().getMostSignificantBits());
    long itemB = Math.abs(UUID.randomUUID().getMostSignificantBits());
    jdbc.update(
        "INSERT INTO crm_order(id,tenant_id,shop_id,weidian_order_id,kol_id,attribution_status,"
            + "order_status,raw_status,order_time,pay_time,total_amount,pay_amount,freight,"
            + "discount,source,last_sync_time,create_time,update_time) VALUES"
            + "(?,?,?,?,?,'ATTRIBUTED','PAID','PAID',?,?,?::numeric,?::numeric,0,0,1,now(),now(),now())",
        orderId,
        TENANT_ID,
        SHOP_ID,
        orderNo,
        kolId,
        Timestamp.from(PAY_TIME),
        Timestamp.from(PAY_TIME),
        pay,
        pay);
    jdbc.update(
        "INSERT INTO crm_order_item(id,tenant_id,order_id,sub_order_id,weidian_item_id,sku_id,"
            + "item_name,sku_name,quantity,pay_amount,refund_amount,kol_id,attribution_type,"
            + "create_time,update_time) VALUES(?,?,?,?,?,'','t76 item','',1,?::numeric,0,?,1,now(),now())",
        itemA,
        TENANT_ID,
        orderId,
        "SUB-" + itemA,
        "WDI-" + itemA,
        itemPayA,
        kolId);
    jdbc.update(
        "INSERT INTO crm_order_item(id,tenant_id,order_id,sub_order_id,weidian_item_id,sku_id,"
            + "item_name,sku_name,quantity,pay_amount,refund_amount,kol_id,attribution_type,"
            + "create_time,update_time) VALUES(?,?,?,?,?,'','t76 item','',1,?::numeric,0,?,1,now(),now())",
        itemB,
        TENANT_ID,
        orderId,
        "SUB-" + itemB,
        "WDI-" + itemB,
        itemPayB,
        kolId);
    return new long[] {orderId, itemA, itemB};
  }
}
