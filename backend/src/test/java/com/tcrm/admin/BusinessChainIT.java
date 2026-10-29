package com.tcrm.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.commission.job.CommissionReleaseJob;
import com.tcrm.admin.integration.service.WeidianTokenRenewalJob;
import com.tcrm.admin.link.service.LinkIndexSyncJob;
import com.tcrm.admin.order.service.OrderSettlementFlowService;
import com.tcrm.admin.order.service.OrderSnapshotCorrection;
import com.tcrm.admin.order.service.OrderSyncCoordinator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Chains A and B. Each method opens its own company through the HTTP APIs and talks to {@link
 * FakeWeidian}. Settlement is pulled through {@link OrderSettlementFlowService#pull}; release goes
 * through {@link CommissionReleaseJob#releaseDue}.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import({ChainMailCapture.class, ChainScheduler.class})
class BusinessChainIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final FakeWeidian WEIDIAN = FakeWeidian.start();

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
    registry.add("crm.bootstrap.password", () -> PASSWORD);
    registry.add("crm.link.base-url", () -> "https://t.example.test");
    registry.add("crm.kol-portal.base-url", () -> "https://kol.example.com");
    registry.add("crm.link.click-consumer.enabled", () -> "false");
    registry.add("crm.weidian.qps", () -> "50");
    WeidianTestProperties.register(registry, WEIDIAN.baseUrl() + "/api", WEIDIAN.baseUrl());
  }

  private final ChainWorld world;

  @Autowired
  BusinessChainIT(
      MockMvc mvc,
      ObjectMapper json,
      JdbcTemplate jdbc,
      StringRedisTemplate redis,
      OrderSettlementFlowService flows,
      CommissionReleaseJob releaseJob,
      LinkIndexSyncJob linkSync,
      OrderSyncCoordinator orderSync,
      OrderSnapshotCorrection snapshots,
      WeidianTokenRenewalJob renewal) {
    orderSync.setScheduled(false);
    linkSync.setScheduled(false);
    snapshots.setScheduled(false);
    renewal.setScheduled(false);
    this.world =
        new ChainWorld(mvc, json, jdbc, redis, flows, releaseJob, linkSync, WEIDIAN, PASSWORD);
  }

  @BeforeEach
  void reset() {
    WEIDIAN.reset();
    ChainMailCapture.SENT.clear();
  }

  @AfterEach
  void cleanup() {
    world.cleanup();
    WEIDIAN.assertProtocol();
  }

  @AfterAll
  static void stopWeidian() {
    WEIDIAN.stop();
  }

  @Test
  void shouldPayOutAccruedCommission_whenPaidOrderSettlesThroughTheChain() throws Exception {
    world.open("payout");
    world.syncPaidOrder();
    // Line pays 40.00 and 20.00, freight 5.00, so the order pay is 65.00.
    // Goods income 6500 fen = 65.00, fee -20 fen = -0.20, profit share -10 fen = -0.10.
    // Basis = 64.70. Split by line pay: 64.70 * 40/60 = 43.1333... -> 43.13,
    // 64.70 * 20/60 = 21.5666... -> 21.57. The rounded parts already sum to 64.70, so the
    // largest-line tail is 0.00. Rate 0.1000 gives 4.31 and 2.16, and 4.31 + 2.16 = 6.47
    // which equals round(64.70 * 0.10, 2).
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    assertConfirmed("43.13", "4.31", "21.57", "2.16");
    world.release();
    ChainWorld.money(world.overview().at("/income/available"), "6.47");

    JsonNode detail = world.order(world.bdToken());
    assertThat(detail.path("orderStatus").asString()).isEqualTo("PAID");
    assertThat(detail.path("attributionStatus").asString()).isEqualTo("ATTRIBUTED");
    // The detail DTO names the Weidian order number orderNo. The list keeps weidianOrderId too.
    assertThat(detail.path("orderNo").asString()).isEqualTo(world.orderNo());
    ChainWorld.money(detail.path("payAmount"), "65.00");
    ChainWorld.money(detail.path("freight"), "5.00");
    assertThat(detail.toString()).doesNotContain("t79-buyer");
    assertThat(world.buyerHash()).isNotBlank().doesNotContain("t79-buyer");
    JsonNode listed = world.orders(world.bdToken());
    assertThat(ChainWorld.count(listed.path("total"))).isEqualTo(1);
    JsonNode listedOrder = listed.path("items").get(0);
    assertThat(listedOrder.path("weidianOrderId").asString()).isEqualTo(world.orderNo());
    assertAttributed(detail, listedOrder);

    long payoutAccountId = world.createPayout();
    long withdrawalId = world.applyWithdrawal(payoutAccountId, "6.47");
    world.approveWithdrawal(withdrawalId);
    world.pay(withdrawalId, "6.47");

    ChainWorld.money(world.overview().at("/income/available"), "0.00");
    assertThat(world.withdrawal(world.financeToken(), withdrawalId).path("status").asString())
        .isEqualTo("PAID");
    assertThat(world.kolWithdrawal(withdrawalId).path("status").asString()).isEqualTo("PAID");
    assertThat(world.outboxEvents(withdrawalId)).contains("PAID");
    ChainWorld.assertLedger(
        world.ledger(),
        "COMMISSION_CREDIT=4.31",
        "COMMISSION_CREDIT=2.16",
        "COMMISSION_WITHDRAWAL=-6.47");
    assertChain("6.47", "4.31", "2.16", "6.47", "6.47");
    JsonNode settlement = world.settlements(world.financeToken());
    assertThat(ChainWorld.count(settlement.path("total"))).isEqualTo(1);
    ChainWorld.money(settlement.path("items").get(0).path("commission"), "6.47");
    assertThat(settlement.path("items").get(0).path("status").asString()).isEqualTo("POSTED");
  }

  @Test
  void shouldReduceAvailableBalance_whenPartialRefundArrivesAfterRelease() throws Exception {
    world.open("refund");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    ChainWorld.money(world.overview().at("/income/available"), "6.47");
    // A later income-detail refund of -100 fen = -1.00. Basis becomes 63.70.
    // 63.70 * 40/60 = 42.4666... -> 42.47; 63.70 * 20/60 = 21.2333... -> 21.23.
    // Tail is 0.00 again. Commissions 4.25 and 2.12 sum to 6.37. The already released
    // 4.31 and 2.16 drop by 0.06 and 0.04, and the available balance follows 6.47 -> 6.37.
    int revisions = world.revisionCount();
    world.stubIncome(world.refundFlow());
    assertThat(world.pull()).isTrue();
    // One new revision per line. There is no revision list API.
    assertThat(world.revisionCount()).isEqualTo(revisions + 2);
    world.release();
    assertConfirmed("42.47", "4.25", "21.23", "2.12");
    ChainWorld.money(world.overview().at("/income/available"), "6.37");
    ChainWorld.assertLedger(
        world.ledger(),
        "COMMISSION_CREDIT=4.31",
        "COMMISSION_CREDIT=2.16",
        "COMMISSION_ADJUSTMENT=-0.06",
        "COMMISSION_ADJUSTMENT=-0.04");
    // Credits stay 4.31 + 2.16. The refund is two decreases, 0.06 and 0.04.
    assertChain("6.37", "4.25", "2.12", "6.47", "0.10");
  }

  @Test
  void shouldLeaveWalletUntouched_whenOrderRefundsBeforeSettlement() throws Exception {
    world.open("presettle");
    world.syncPaidOrder();
    // The only flow is a pre-settle refund of -6500 fen = -65.00, opposite the 65.00 pay.
    // With no income rows the basis is a verified 0.00, so release posts nothing.
    world.stubPreSettleRefund();
    assertThat(world.pull()).isTrue();
    List<Map<String, Object>> states = world.itemStates();
    assertThat(states).hasSize(2);
    for (Map<String, Object> state : states) {
      assertThat(state.get("verified")).isEqualTo(true);
      assertThat(ChainWorld.money(state.get("basis_cny"))).isEqualByComparingTo("0.00");
      assertThat(ChainWorld.money(state.get("accrued_commission_cny")))
          .isEqualByComparingTo("0.00");
      assertThat(state.get("release_basis")).isEqualTo("UNSETTLED");
      assertThat(state.get("release_at")).isNull();
    }
    world.release();
    assertThat(world.ledger()).isEmpty();
    ChainWorld.money(world.overview().at("/income/available"), "0.00");
    JsonNode chain = world.chain(world.bdToken());
    ChainWorld.money(chain.at("/funds/kolCommission"), "0.00");
    assertThat(chain.at("/funds/accountStatus").asString()).isEqualTo("POSTED");
    assertThat(chain.at("/funds/shopProfit").isNull()).isTrue();
  }

  private void assertConfirmed(
      String basisA, String commissionA, String basisB, String commissionB) {
    List<Map<String, Object>> states = world.itemStates();
    assertThat(states).hasSize(2);
    assertLine(states.get(0), world.exclusiveA(), basisA, commissionA, world.tradeTime());
    assertLine(states.get(1), world.exclusiveB(), basisB, commissionB, world.tradeTime());
  }

  private static void assertLine(
      Map<String, Object> state,
      String itemId,
      String basis,
      String commission,
      Instant tradeTime) {
    assertThat(state.get("weidian_item_id")).isEqualTo(itemId);
    assertThat(state.get("verified")).isEqualTo(true);
    assertThat(ChainWorld.money(state.get("basis_cny"))).isEqualByComparingTo(basis);
    assertThat(ChainWorld.money(state.get("accrued_commission_cny")))
        .isEqualByComparingTo(commission);
    assertThat(state.get("release_basis")).isEqualTo("CONFIRMED");
    assertThat(ChainWorld.instant(state.get("release_at"))).isEqualTo(tradeTime);
  }

  private void assertAttributed(JsonNode detail, JsonNode listedOrder) {
    assertThat(detail.path("items")).hasSize(2);
    assertThat(listedOrder.path("items")).hasSize(2);
    for (JsonNode item : listedOrder.path("items")) {
      assertThat(item.path("attributionType").intValue()).isEqualTo(1);
    }
    for (JsonNode item : detail.path("items")) {
      assertThat(item.path("kolId").asString()).isEqualTo(Long.toString(world.kolId()));
      String itemId = item.path("weidianItemId").asString();
      if (itemId.equals(world.exclusiveA())) {
        ChainWorld.money(item.path("payAmount"), "40.00");
      } else {
        assertThat(itemId).isEqualTo(world.exclusiveB());
        ChainWorld.money(item.path("payAmount"), "20.00");
      }
    }
  }

  private void assertChain(
      String orderCommission, String lineA, String lineB, String credited, String withdrawn)
      throws Exception {
    JsonNode detail = world.order(world.bdToken());
    String itemA = null;
    String itemB = null;
    for (JsonNode item : detail.path("items")) {
      if (world.exclusiveA().equals(item.path("weidianItemId").asString())) {
        itemA = item.path("id").asString();
      } else {
        itemB = item.path("id").asString();
      }
    }
    JsonNode chain = world.chain(world.bdToken());
    ChainWorld.money(chain.at("/funds/payAmount"), "65.00");
    ChainWorld.money(chain.at("/funds/refundTotal"), "0.00");
    ChainWorld.money(chain.at("/funds/kolCommission"), orderCommission);
    assertThat(chain.at("/funds/shopProfit").isNull()).isTrue();
    assertThat(chain.at("/funds/accountStatus").asString()).isEqualTo("POSTED");
    assertLineCommission(lineOf(chain, itemA), lineA);
    assertLineCommission(lineOf(chain, itemB), lineB);
    BigDecimal increase = BigDecimal.ZERO;
    BigDecimal decrease = BigDecimal.ZERO;
    for (JsonNode flow : chain.path("financeFlows")) {
      assertThat(flow.path("type").asString()).isEqualTo("KOL_COMMISSION");
      BigDecimal amount = new BigDecimal(flow.path("amount").asString());
      assertThat(amount).isPositive();
      if ("INCREASE".equals(flow.path("direction").asString())) {
        increase = increase.add(amount);
      } else {
        assertThat(flow.path("direction").asString()).isEqualTo("DECREASE");
        decrease = decrease.add(amount);
      }
    }
    assertThat(increase).isEqualByComparingTo(credited);
    assertThat(decrease).isEqualByComparingTo(withdrawn);
  }

  private static void assertLineCommission(JsonNode line, String commission) {
    ChainWorld.money(line.at("/commission/netCommission"), commission);
    ChainWorld.money(line.at("/commission/rateSnapshot"), "0.1000");
    assertThat(line.at("/commission/releaseBasis").asString()).isEqualTo("CONFIRMED");
    assertThat(line.at("/commission/shopProfit").isNull()).isTrue();
  }

  private static JsonNode lineOf(JsonNode chain, String orderItemId) {
    for (JsonNode line : chain.path("lines")) {
      if (orderItemId.equals(line.path("orderItemId").asString())) {
        return line;
      }
    }
    throw new AssertionError("no chain line for " + orderItemId);
  }
}
