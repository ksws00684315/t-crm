package com.tcrm.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.commission.job.CommissionReleaseJob;
import com.tcrm.admin.integration.service.WeidianApiException;
import com.tcrm.admin.integration.service.WeidianTokenRenewalJob;
import com.tcrm.admin.link.service.LinkIndexSyncJob;
import com.tcrm.admin.order.service.OrderSettlementFlowService;
import com.tcrm.admin.order.service.OrderSnapshotCorrection;
import com.tcrm.admin.order.service.OrderSyncCoordinator;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Chains C and D. One company per method, Weidian only through {@link FakeWeidian}. A failed
 * settlement round must leave the stored flows in place for the next pull.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import({ChainMailCapture.class, ChainScheduler.class})
class GuardChainIT {
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
  GuardChainIT(
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
  void shouldLeaveLinesUnverified_whenGoodsIncomeUnitDoesNotMatchPay() throws Exception {
    world.open("unit");
    world.syncPaidOrder();
    // The same 65 is returned as yuan. Fen conversion makes it 0.65, which is not the 65.00 pay,
    // so the lines stay unverified for INCOME_MISMATCH and the wallet is not credited.
    world.stubIncomeInYuan();
    assertThat(world.pull()).isTrue();
    assertThat(world.basisReason()).isEqualTo("INCOME_MISMATCH");
    assertThat(world.itemStates()).hasSize(2);
    for (Map<String, Object> state : world.itemStates()) {
      assertThat(state.get("verified")).isEqualTo(false);
      assertThat(state.get("basis_cny")).isNull();
      assertThat(state.get("release_at")).isNull();
    }
    world.release();
    assertThat(world.ledger()).isEmpty();
    ChainWorld.money(world.overview().at("/income/available"), "0.00");
    JsonNode chain = world.chain(world.financeToken());
    assertThat(chain.at("/funds/kolCommission").isNull()).isTrue();
    assertThat(chain.at("/funds/accountStatus").asString()).isEqualTo("UNVERIFIED");
  }

  @Test
  void shouldRecomputeStoredFlowsOnce_whenOneSettlementRoundFails() throws Exception {
    world.open("retry");
    world.syncPaidOrder();
    // Same basis as the payout chain: 6500 - 20 - 10 fen = 64.70, commissions 4.31 and 2.16.
    world.stubIncome(world.unmatchedFlow());
    world.failNextPreSettle();
    assertThatThrownBy(world::pull).isInstanceOf(WeidianApiException.class);
    // Order sync already wrote one unverified row per line. The thrown round skips recompute,
    // so those rows stay unverified and the wallet stays empty.
    assertThat(world.basisReason()).isEqualTo("NO_SETTLEMENT");
    assertThat(world.itemStates()).hasSize(2);
    for (Map<String, Object> state : world.itemStates()) {
      assertThat(state.get("verified")).isEqualTo(false);
      assertThat(state.get("basis_cny")).isNull();
    }
    assertThat(world.ledger()).isEmpty();
    assertThat(world.flowOrderIds(world.unmatchedOrder())).singleElement().isNull();
    assertThat(world.flowOrderIds(world.orderNo())).isNotEmpty().allMatch(id -> id != null);

    // An unchanged order is not fetched again. The list call is what shows this round still runs.
    long before = WEIDIAN.count("vdian.order.list.get");
    world.syncAgain();
    TestWait.until(
        Duration.ofSeconds(30),
        () -> WEIDIAN.count("vdian.order.list.get") > before,
        "order sync did not run after the failed settlement round");
    assertThat(world.order(world.adminToken()).path("orderStatus").asString()).isEqualTo("PAID");

    assertThat(world.pull()).isTrue();
    assertThat(world.itemStates()).hasSize(2);
    world.release();
    ChainWorld.assertLedger(world.ledger(), "COMMISSION_CREDIT=4.31", "COMMISSION_CREDIT=2.16");
    assertThat(world.pull()).isTrue();
    world.release();
    ChainWorld.assertLedger(world.ledger(), "COMMISSION_CREDIT=4.31", "COMMISSION_CREDIT=2.16");
    ChainWorld.money(world.overview().at("/income/available"), "6.47");
    assertThat(world.flowOrderIds(world.unmatchedOrder())).singleElement().isNull();
  }

  @Test
  void shouldHideFundsAndOrders_whenCallerIsOutsideTheOwningScope() throws Exception {
    world.open("scope");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    long payoutAccountId = world.approvePayout();
    long withdrawalId = world.applyWithdrawal(payoutAccountId, "6.47");
    String outsider = world.otherCompanyToken();
    String otherBd = world.otherBdToken();
    String viewer = world.viewerToken();
    String otherKol = world.otherKolToken("scope");
    String orderPath = "/admin-api/order/orders/" + world.orderId();
    String chainPath = orderPath + "/chain";
    String withdrawalPath = "/admin-api/commission/withdrawals/" + withdrawalId;
    String freeze = "{\"kolId\":\"" + world.kolId() + "\",\"reason\":\"t79\"}";
    String resume = "/admin-api/commission/withdrawal-freezes/" + world.kolId() + "/resume";

    assertThat(world.getStatus(ChainWorld.ADMIN, outsider, orderPath)).isEqualTo(404);
    assertThat(world.getStatus(ChainWorld.ADMIN, outsider, chainPath)).isEqualTo(404);
    assertThat(world.getStatus(ChainWorld.ADMIN, outsider, withdrawalPath)).isEqualTo(404);
    assertThat(world.settlements(outsider).path("items")).isEmpty();
    assertThat(ChainWorld.count(world.orders(outsider).path("total"))).isZero();
    assertThat(world.postStatus(outsider, "/admin-api/commission/withdrawal-freezes", freeze))
        .isEqualTo(404);
    assertThat(world.putStatus(outsider, withdrawalPath + "/payment", payBody())).isEqualTo(404);

    assertThat(world.getStatus(ChainWorld.ADMIN, otherBd, orderPath)).isEqualTo(403);
    assertThat(world.getStatus(ChainWorld.ADMIN, otherBd, chainPath)).isEqualTo(403);
    assertThat(world.getStatus(ChainWorld.ADMIN, otherBd, withdrawalPath)).isEqualTo(404);
    assertThat(ChainWorld.count(world.orders(otherBd).path("total"))).isZero();
    assertThat(world.settlements(otherBd).path("items")).isEmpty();
    // The BD template has neither commission:freeze nor withdrawal:pay, so these 403s stop at
    // @SaCheckPermission and never reach requireKolScope.
    assertThat(world.postStatus(otherBd, "/admin-api/commission/withdrawal-freezes", freeze))
        .isEqualTo(403);
    assertThat(world.putStatus(otherBd, resume, "{\"reason\":\"t79\"}")).isEqualTo(403);
    assertThat(world.putStatus(otherBd, withdrawalPath + "/payment", payBody())).isEqualTo(403);

    // OWN, not this creator's BD, but holding the freeze and pay codes. The service then applies
    // requireKolScope and answers 404 COMMON_NOT_FOUND.
    String payer = world.unassignedFundsToken();
    assertThat(world.postOutcome(payer, "/admin-api/commission/withdrawal-freezes", freeze))
        .isEqualTo("404 COMMON_NOT_FOUND");
    assertThat(world.putOutcome(payer, resume, "{\"reason\":\"t79\"}"))
        .isEqualTo("404 COMMON_NOT_FOUND");
    assertThat(world.putOutcome(payer, withdrawalPath + "/payment", payBody()))
        .isEqualTo("404 COMMON_NOT_FOUND");

    JsonNode hidden = world.chain(viewer);
    assertThat(hidden.path("funds").isNull()).isTrue();
    assertThat(hidden.path("financeFlows").isNull()).isTrue();
    assertThat(hidden.path("lines")).isNotEmpty();
    for (JsonNode line : hidden.path("lines")) {
      assertThat(line.path("commission").isNull()).isTrue();
    }
    assertThat(
            world.getStatus(ChainWorld.ADMIN, viewer, "/admin-api/commission/settlement-details"))
        .isEqualTo(403);

    assertThat(ChainWorld.count(world.kolOrders(world.kolToken()).path("total"))).isEqualTo(1);
    assertThat(ChainWorld.count(world.kolOrders(otherKol).path("total"))).isZero();
    ChainWorld.money(world.overview(otherKol).at("/income/available"), "0.00");
  }

  private static String payBody() {
    return "{\"idempotencyKey\":\""
        + UUID.randomUUID()
        + "\",\"amountCny\":\"6.47\",\"actualAmount\":\"6.47\",\"actualCurrency\":\"CNY\","
        + "\"paidAt\":\""
        + OffsetDateTime.now(ZoneOffset.UTC)
        + "\",\"method\":\"PAYPAL\",\"voucher\":\"1\"}";
  }
}
