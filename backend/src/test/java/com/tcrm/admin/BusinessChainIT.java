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
    // Paid but not settled (task 81): the basis is the pay amount. Split by line pay:
    // 65.00 * 40/60 = 43.333... -> 43.33, 65.00 * 20/60 = 21.666... -> 21.67, tail 0.00.
    // Rate 0.1000 gives 4.33 and 2.17, and 4.33 + 2.17 = 6.50.
    assertPaidButUnsettled("43.33", "4.33", "21.67", "2.17");
    // Goods income 6500 fen = 65.00 confirms that basis; fee -20 and profit share -10 never
    // enter it (owner decision 2026-10-29).
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    assertConfirmed("43.33", "4.33", "21.67", "2.17");
    world.release();
    ChainWorld.money(world.overview().at("/income/available"), "6.50");

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
    long withdrawalId = world.applyWithdrawal(payoutAccountId, "6.50");
    world.approveWithdrawal(withdrawalId);
    world.pay(withdrawalId, "6.50");

    ChainWorld.money(world.overview().at("/income/available"), "0.00");
    assertThat(world.withdrawal(world.financeToken(), withdrawalId).path("status").asString())
        .isEqualTo("PAID");
    assertThat(world.kolWithdrawal(withdrawalId).path("status").asString()).isEqualTo("PAID");
    assertThat(world.outboxEvents(withdrawalId)).contains("PAID");
    ChainWorld.assertLedger(
        world.ledger(),
        "COMMISSION_CREDIT=4.33",
        "COMMISSION_CREDIT=2.17",
        "COMMISSION_WITHDRAWAL=-6.50");
    assertChain("6.50", "4.33", "2.17", "6.50", "6.50");
    JsonNode settlement = world.settlements(world.financeToken());
    assertThat(ChainWorld.count(settlement.path("total"))).isEqualTo(1);
    ChainWorld.money(settlement.path("items").get(0).path("commission"), "6.50");
    assertThat(settlement.path("items").get(0).path("status").asString()).isEqualTo("POSTED");
  }

  @Test
  void shouldNetRefundGapOnceWhenIncomeDetailRefundFlowArrivesAfterRelease() throws Exception {
    world.open("refund");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    ChainWorld.money(world.overview().at("/income/available"), "6.50");
    // A later income-detail refund of -100 fen = -1.00 reaches the flows with no CRM refund record
    // (task 81 PR review, 2026-10-30): the gap 1.00 is netted by pay ratio 40 : 20, so the lines
    // carry 0.67 and 0.33 extra refund. The basis stays 43.33 / 21.67 and the accrual drops to
    // (43.33 - 0.67) x 10% = 4.27 and (21.67 - 0.33) x 10% = 2.13. The released surplus is taken
    // back as COMMISSION_ADJUSTMENT: -0.06 and -0.04.
    int revisions = world.revisionCount();
    world.stubIncome(world.refundFlow());
    assertThat(world.pull()).isTrue();
    assertThat(world.revisionCount()).isEqualTo(revisions + 2);
    world.release();
    assertConfirmed("43.33", "4.27", "21.67", "2.13");
    ChainWorld.money(world.overview().at("/income/available"), "6.40");
    ChainWorld.assertLedger(
        world.ledger(),
        "COMMISSION_CREDIT=4.33",
        "COMMISSION_CREDIT=2.17",
        "COMMISSION_ADJUSTMENT=-0.06",
        "COMMISSION_ADJUSTMENT=-0.04");
    assertChain("6.40", "4.27", "2.13", "6.50", "0.10");
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
  }

  @Test
  void shouldZeroCommissionWhenOrderClosesBeforeSettlementWithoutRefundRecord() throws Exception {
    world.open("closed");
    world.syncPaidOrder();
    assertPaidButUnsettled("43.33", "4.33", "21.67", "2.17");
    // Closed after payment and before any settlement, with no CRM refund (task 81 PR review,
    // 2026-10-30): the basis is a verified zero, so the frozen commission goes to zero.
    world.restubOrderStatus("close");
    world.awaitOrderStatus("CLOSED");
    for (Map<String, Object> state : world.itemStates()) {
      assertThat(state.get("verified")).isEqualTo(true);
      assertThat(ChainWorld.money(state.get("basis_cny"))).isEqualByComparingTo("0.00");
      assertThat(ChainWorld.money(state.get("accrued_commission_cny")))
          .isEqualByComparingTo("0.00");
    }
    world.release();
    assertThat(world.ledger()).isEmpty();
    ChainWorld.money(world.overview().at("/income/available"), "0.00");
    JsonNode kolOrders = world.kolOrders(world.kolToken());
    assertThat(kolLineStatus(kolOrders, "t79-a")).isEqualTo("NO_COMMISSION");
    assertThat(kolLineStatus(kolOrders, "t79-b")).isEqualTo("NO_COMMISSION");
  }

  @Test
  void shouldTakeReleasedCommissionBackIntoNegativeWalletWhenFullyRefundedAfterWithdrawal()
      throws Exception {
    world.open("negative");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    ChainWorld.money(world.overview().at("/income/available"), "6.50");
    long payoutAccountId = world.createPayout();
    long withdrawalId = world.applyWithdrawal(payoutAccountId, "6.50");
    world.approveWithdrawal(withdrawalId);
    world.pay(withdrawalId, "6.50");
    ChainWorld.money(world.overview().at("/income/available"), "0.00");
    // Both lines are refunded in full with SUCCESS, then the order closes after settlement.
    world.stubRefund(world.refund(1), "1", 4000, "10");
    world.awaitRefundStatus(world.refund(1), "SUCCESS");
    world.stubRefund(world.refund(2), "2", 2000, "10");
    world.awaitRefundStatus(world.refund(2), "SUCCESS");
    world.restubOrderStatus("close");
    world.awaitOrderStatus("CLOSED");
    // The released 6.50 is taken back as negative adjustments, so the wallet goes below zero.
    world.release();
    ChainWorld.money(world.overview().at("/income/available"), "-6.50");
    for (Map<String, Object> state : world.itemStates()) {
      assertThat(ChainWorld.money(state.get("accrued_commission_cny")))
          .isEqualByComparingTo("0.00");
    }
    // Both lines were withdrawn (paid), so the withdrawal occupation outranks the zero accrual.
    JsonNode kolOrders = world.kolOrders(world.kolToken());
    assertThat(kolLineStatus(kolOrders, "t79-a")).isEqualTo("WITHDRAWN");
    assertThat(kolLineStatus(kolOrders, "t79-b")).isEqualTo("WITHDRAWN");
  }

  @Test
  void shouldReleaseRemainingCommissionWhenPartialRefundSucceedsBeforeSettlement()
      throws Exception {
    world.open("partial");
    world.syncPaidOrder();
    world.stubRefund(world.refund(1), "1", 1000, "10");
    world.awaitRefundStatus(world.refund(1), "SUCCESS");
    // Goods income is the pay 65.00 less the 10.00 refund, which also confirms the basis
    // (task 81 rule 5). Line A keeps 43.33 - 10.00 = 33.33, so its accrual is 3.33.
    world.stubGoodsIncome(5500);
    assertThat(world.pull()).isTrue();
    List<Map<String, Object>> states = world.itemStates();
    assertLine(states.get(0), world.exclusiveA(), "43.33", "3.33", world.tradeTime());
    assertLine(states.get(1), world.exclusiveB(), "21.67", "2.17", world.tradeTime());
    world.release();
    ChainWorld.money(world.overview().at("/income/available"), "5.50");
    ChainWorld.assertLedger(world.ledger(), "COMMISSION_CREDIT=3.33", "COMMISSION_CREDIT=2.17");
  }

  @Test
  void shouldKeepReleasedLineFrozenAndUnwithdrawableWhileRefundIsOpen() throws Exception {
    world.open("open");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    ChainWorld.money(world.overview().at("/income/available"), "6.50");
    // Raw status 2 is not a completed refund, so it stays UNKNOWN and blocks line A (rule 1).
    world.stubRefund(world.refund(1), "1", 4000, "2");
    world.awaitRefundStatus(world.refund(1), "UNKNOWN");
    JsonNode kolOrders = world.kolOrders(world.kolToken());
    assertThat(kolLineStatus(kolOrders, "t79-a")).isEqualTo("FROZEN");
    assertThat(kolLineStatus(kolOrders, "t79-b")).isEqualTo("WITHDRAWABLE");
    // 可提现 (L-033): the wallet still holds 6.50, but line A's 4.33 sits in the open refund, so
    // the displayed available drops to what the rows still fund — line B's 2.17 — and equals the
    // cap an application can actually allocate. The blocked released money stays visible as
    // frozen, keeping 预估 6.50 = 冻结 4.33 + 可提现 2.17 + 占用 0.00.
    ChainWorld.money(world.conditions().at("/available"), "2.17");
    ChainWorld.money(world.conditions().at("/frozen"), "4.33");
    ChainWorld.money(world.overview().at("/income/available"), "2.17");
    ChainWorld.money(world.overview().at("/income/frozen"), "4.33");
    // Line A's released 4.33 cannot be occupied by a new application: only line B is funded.
    long payoutAccountId = world.createPayout();
    assertThat(world.applyWithdrawalOutcome(payoutAccountId, "6.50"))
        .isEqualTo("422 WITHDRAWAL_INSUFFICIENT_AVAILABLE");
  }

  @Test
  void shouldKeepPullingWhenOpenRefundClosesAndLineReturnsToReleasedFingerprint() throws Exception {
    world.open("undo");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    int revisions = world.revisionCount();
    // Line A goes after_sales false -> true -> false, so its fingerprint returns to the first one.
    // A failed recompute would answer false and hold the shop's checkpoint back.
    world.stubRefund(world.refund(1), "1", 4000, "2");
    world.awaitRefundStatus(world.refund(1), "UNKNOWN");
    assertThat(world.pull()).isTrue();
    assertThat(world.revisionCount()).isEqualTo(revisions + 1);
    world.closeRefund(world.refund(1));
    assertThat(world.pull()).isTrue();
    assertThat(world.revisionCount()).isEqualTo(revisions + 2);
    // Repeat recompute and release: nothing new, and the one release stands.
    assertThat(world.pull()).isTrue();
    world.release();
    assertThat(world.revisionCount()).isEqualTo(revisions + 2);
    ChainWorld.money(world.overview().at("/income/available"), "6.50");
    ChainWorld.assertLedger(world.ledger(), "COMMISSION_CREDIT=4.33", "COMMISSION_CREDIT=2.17");
    assertThat(kolLineStatus(world.kolOrders(world.kolToken()), "t79-a")).isEqualTo("WITHDRAWABLE");
  }

  @Test
  void shouldPauseAndResumeTheWithdrawal_whenRefundsBlockAndClearTheOccupiedLines()
      throws Exception {
    world.open("pause");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    ChainWorld.money(world.overview().at("/income/available"), "6.50");
    long payoutAccountId = world.createPayout();
    long withdrawalId = world.applyWithdrawal(payoutAccountId, "6.50");
    // Reviewed up front, so the pause hits a PENDING_PAYMENT application — the state the payment
    // registration runs on.
    world.approveWithdrawal(withdrawalId);
    assertThat(world.withdrawal(world.financeToken(), withdrawalId).path("paused").asBoolean())
        .isFalse();

    // Both occupied lines go into an open refund (raw status 2 stays UNKNOWN): the application is
    // paused, once, even though the recompute runs twice (L-032).
    world.stubRefund(world.refund(1), "1", 4000, "2");
    world.awaitRefundStatus(world.refund(1), "UNKNOWN");
    world.stubRefund(world.refund(2), "2", 2000, "2");
    world.awaitRefundStatus(world.refund(2), "UNKNOWN");
    assertThat(world.pull()).isTrue();
    JsonNode paused = world.withdrawal(world.financeToken(), withdrawalId);
    assertThat(paused.path("paused").asBoolean()).isTrue();
    assertThat(historyCount(paused, "PAUSED")).isEqualTo(1);
    assertThat(world.pull()).isTrue();
    assertThat(historyCount(world.withdrawal(world.financeToken(), withdrawalId), "PAUSED"))
        .isEqualTo(1);

    // Payment is refused while paused (design §4.8.6: the pause blocks payment only).
    assertThat(world.payWithdrawalOutcome(withdrawalId, "6.50")).isEqualTo("409 WITHDRAWAL_PAUSED");
    // Wallet 6.50 is fully occupied and every released row is blocked: nothing is withdrawable.
    ChainWorld.money(world.conditions().at("/available"), "0.00");

    // Line A's refund closes first: one unblocked line does not lift the whole application.
    world.closeRefund(world.refund(1));
    assertThat(world.pull()).isTrue();
    assertThat(world.withdrawal(world.financeToken(), withdrawalId).path("paused").asBoolean())
        .isTrue();
    assertThat(historyCount(world.withdrawal(world.financeToken(), withdrawalId), "RESUMED"))
        .isZero();

    // Line B closes too: every line is unblocked, the pause lifts and RESUMED is written.
    world.closeRefund(world.refund(2));
    assertThat(world.pull()).isTrue();
    JsonNode resumed = world.withdrawal(world.financeToken(), withdrawalId);
    assertThat(resumed.path("paused").asBoolean()).isFalse();
    assertThat(historyCount(resumed, "RESUMED")).isEqualTo(1);

    world.pay(withdrawalId, "6.50");
    assertThat(world.withdrawal(world.financeToken(), withdrawalId).path("status").asString())
        .isEqualTo("PAID");
  }

  @Test
  void shouldLeavePaidApplicationUnpaused_whenOccupiedLinesRefundAfterwards() throws Exception {
    world.open("paid-pause");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    long payoutAccountId = world.createPayout();
    long withdrawalId = world.applyWithdrawal(payoutAccountId, "6.50");
    world.approveWithdrawal(withdrawalId);
    world.pay(withdrawalId, "6.50");
    // A refund opens afterwards: the paid application is outside the active set, so the linkage
    // leaves it alone — no paused flag and no PAUSED history (L-032).
    world.stubRefund(world.refund(1), "1", 4000, "2");
    world.awaitRefundStatus(world.refund(1), "UNKNOWN");
    assertThat(world.pull()).isTrue();
    JsonNode detail = world.withdrawal(world.financeToken(), withdrawalId);
    assertThat(detail.path("paused").asBoolean()).isFalse();
    assertThat(historyCount(detail, "PAUSED")).isZero();
  }

  @Test
  void shouldRefuseThePayment_whenTheWalletCannotCoverItAfterAnotherLineWasPaidAndRefunded()
      throws Exception {
    world.open("shortfall");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    long payoutAccountId = world.createPayout();
    // Two applications: line A's 4.33 is paid out first (wallet 2.17), line B's 2.17 is still
    // awaiting payment when line A is refunded in full — the clawback drags the wallet to
    // −2.16. Line B itself is not over-occupied, so nothing is paused; the payment is refused
    // on the wallet balance instead (owner decision 2026-11-01).
    long first = world.applyWithdrawal(payoutAccountId, "4.33");
    world.approveWithdrawal(first);
    world.pay(first, "4.33");
    long second = world.applyWithdrawal(payoutAccountId, "2.17");
    world.approveWithdrawal(second);
    world.stubRefund(world.refund(1), "1", 4000, "10");
    world.awaitRefundStatus(world.refund(1), "SUCCESS");
    assertThat(world.pull()).isTrue();
    // Line B is not blocked, so the stored pause must be clear before the wallet check fires.
    JsonNode detail = world.withdrawal(world.financeToken(), second);
    assertThat(detail.path("status").asString()).isEqualTo("PENDING_PAYMENT");
    assertThat(detail.path("paused").asBoolean()).isFalse();
    assertThat(historyCount(detail, "PAUSED")).isZero();
    assertThat(world.payWithdrawalOutcome(second, "2.17"))
        .isEqualTo("422 WITHDRAWAL_INSUFFICIENT_AVAILABLE");
    // The refused payment leaves the wallet untouched (−1.83 stays) and the rejection exit is
    // still available: 预估 2.50 = 冻结 0.00 + 可提现(−4.00) + 占用 2.17 + 已付 4.33.
    ChainWorld.money(world.overview().at("/income/available"), "-4.00");
  }

  @Test
  void shouldKeepTheWithdrawalPaused_whenTheRefundSucceedsShortOfTheOccupation() throws Exception {
    world.open("short");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    long payoutAccountId = world.createPayout();
    long withdrawalId = world.applyWithdrawal(payoutAccountId, "6.50");
    world.approveWithdrawal(withdrawalId);
    // Open refund pauses the application; a refund that concludes still leaves line A's
    // occupation (4.33) above its released money (refunded in full → 0.00), so the pause stays —
    // owner decision 2026-11-01: no resume, payment refused, reject is the only exit.
    world.stubRefund(world.refund(1), "1", 4000, "2");
    world.awaitRefundStatus(world.refund(1), "UNKNOWN");
    assertThat(world.pull()).isTrue();
    assertThat(world.withdrawal(world.financeToken(), withdrawalId).path("paused").asBoolean())
        .isTrue();
    world.stubRefund(world.refund(1), "1", 4000, "10");
    world.awaitRefundStatus(world.refund(1), "SUCCESS");
    assertThat(world.pull()).isTrue();
    JsonNode detail = world.withdrawal(world.financeToken(), withdrawalId);
    assertThat(detail.path("paused").asBoolean()).isTrue();
    assertThat(historyCount(detail, "RESUMED")).isZero();
    assertThat(world.payWithdrawalOutcome(withdrawalId, "6.50")).isEqualTo("409 WITHDRAWAL_PAUSED");
    // Rejecting clears the stored pause without a RESUMED row; the wallet keeps 2.17.
    world.rejectWithdrawal(withdrawalId, "t90 refund shortfall, reapply");
    JsonNode rejected = world.withdrawal(world.financeToken(), withdrawalId);
    assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
    assertThat(rejected.path("paused").asBoolean()).isFalse();
    assertThat(historyCount(rejected, "RESUMED")).isZero();
  }

  @Test
  void shouldPauseTheWithdrawal_whenTheRefundArrivesStraightAsSuccess() throws Exception {
    world.open("straight");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    long payoutAccountId = world.createPayout();
    long withdrawalId = world.applyWithdrawal(payoutAccountId, "6.50");
    world.approveWithdrawal(withdrawalId);
    // The refund never passes through an open state: the line lands refunded in full straight
    // away, the release drops below the occupation, and the expanded trigger still pauses the
    // application before any payment can go out.
    world.stubRefund(world.refund(1), "1", 4000, "10");
    world.awaitRefundStatus(world.refund(1), "SUCCESS");
    assertThat(world.pull()).isTrue();
    JsonNode detail = world.withdrawal(world.financeToken(), withdrawalId);
    assertThat(detail.path("paused").asBoolean()).isTrue();
    assertThat(historyCount(detail, "PAUSED")).isEqualTo(1);
    assertThat(world.payWithdrawalOutcome(withdrawalId, "6.50")).isEqualTo("409 WITHDRAWAL_PAUSED");
  }

  @Test
  void shouldResumeTheWithdrawal_whenTheRefundedLineStillCoversTheOccupation() throws Exception {
    world.open("fits");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    long payoutAccountId = world.createPayout();
    // Only 2.00 of line A (4.33) is occupied: a 10.00 refund on it still leaves 3.33 released ≥
    // 2.00, so once the refund concludes the pause lifts and the payment goes through.
    long withdrawalId = world.applyWithdrawal(payoutAccountId, "2.00");
    world.approveWithdrawal(withdrawalId);
    world.stubRefund(world.refund(1), "1", 1000, "2");
    world.awaitRefundStatus(world.refund(1), "UNKNOWN");
    assertThat(world.pull()).isTrue();
    assertThat(world.withdrawal(world.financeToken(), withdrawalId).path("paused").asBoolean())
        .isTrue();
    world.stubRefund(world.refund(1), "1", 1000, "10");
    world.awaitRefundStatus(world.refund(1), "SUCCESS");
    assertThat(world.pull()).isTrue();
    JsonNode resumed = world.withdrawal(world.financeToken(), withdrawalId);
    assertThat(resumed.path("paused").asBoolean()).isFalse();
    assertThat(historyCount(resumed, "RESUMED")).isEqualTo(1);
    world.pay(withdrawalId, "2.00");
    assertThat(world.withdrawal(world.financeToken(), withdrawalId).path("status").asString())
        .isEqualTo("PAID");
  }

  @Test
  void shouldNotCountUnpaidLinesAsUnverifiedOrBlockWithdrawal() throws Exception {
    world.open("unpaid");
    world.syncPaidOrder();
    world.stubIncome();
    assertThat(world.pull()).isTrue();
    world.release();
    ChainWorld.money(world.overview().at("/income/available"), "6.50");
    world.syncUnpaidOrder();
    // The unpaid order's lines are UNPAID: no count and no block. Its attribution stays PENDING
    // until payment, so the creator's list shows only the paid order.
    JsonNode conditions = world.conditions();
    assertThat(conditions.path("unverifiedCount").asInt()).isZero();
    assertThat(conditions.path("canApply").asBoolean()).isTrue();
    assertThat(ChainWorld.count(world.kolOrders(world.kolToken()).path("total"))).isEqualTo(1);
    // A paid order whose line pays cannot be allocated is unverified, so its two lines count,
    // while the unpaid order's two lines still do not: the count is 2, not 4.
    world.syncPaidOrderWithoutLinePay();
    conditions = world.conditions();
    assertThat(conditions.path("unverifiedCount").asInt()).isEqualTo(2);
    assertThat(conditions.path("canApply").asBoolean()).isFalse();
    assertThat(ChainWorld.count(world.kolOrders(world.kolToken()).path("total"))).isEqualTo(2);
  }

  /** PAUSED/RESUMED entries of one withdrawal detail history. */
  private static long historyCount(JsonNode detail, String action) {
    long count = 0;
    for (JsonNode event : detail.path("history")) {
      if (action.equals(event.path("action").asString())) {
        count++;
      }
    }
    return count;
  }

  /** Settlement status of the creator's order line with the given product name. */
  private static String kolLineStatus(JsonNode kolOrders, String itemName) {
    for (JsonNode order : kolOrders.path("items")) {
      for (JsonNode line : order.path("items")) {
        if (itemName.equals(line.path("itemName").asString())) {
          return line.path("settlementStatus").asString();
        }
      }
    }
    throw new AssertionError("no creator line " + itemName);
  }

  /**
   * Paid but not settled (task 81): each line is verified and accrued, release still waits for the
   * goods-income settlement, the settlement row shows FROZEN and nothing is withdrawable yet.
   */
  private void assertPaidButUnsettled(
      String basisA, String commissionA, String basisB, String commissionB) throws Exception {
    List<Map<String, Object>> states = world.itemStates();
    assertThat(states).hasSize(2);
    assertUnsettledLine(states.get(0), world.exclusiveA(), basisA, commissionA);
    assertUnsettledLine(states.get(1), world.exclusiveB(), basisB, commissionB);
    ChainWorld.money(world.overview().at("/income/available"), "0.00");
    JsonNode settlement = world.settlements(world.financeToken());
    assertThat(settlement.path("items").get(0).path("status").asString()).isEqualTo("FROZEN");
    assertThat(settlement.path("items").get(0).path("weidianWithdrawable").isString()).isFalse();
  }

  private static void assertUnsettledLine(
      Map<String, Object> state, String itemId, String basis, String commission) {
    assertThat(state.get("weidian_item_id")).isEqualTo(itemId);
    assertThat(state.get("verified")).isEqualTo(true);
    assertThat(ChainWorld.money(state.get("basis_cny"))).isEqualByComparingTo(basis);
    assertThat(ChainWorld.money(state.get("accrued_commission_cny")))
        .isEqualByComparingTo(commission);
    assertThat(state.get("release_basis")).isEqualTo("UNSETTLED");
    assertThat(state.get("release_at")).isNull();
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
