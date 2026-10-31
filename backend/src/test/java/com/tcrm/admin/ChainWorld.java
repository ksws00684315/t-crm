package com.tcrm.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.idev.excel.FastExcel;
import com.tcrm.admin.commission.job.CommissionReleaseJob;
import com.tcrm.admin.link.service.LinkIndexSyncJob;
import com.tcrm.admin.order.service.OrderSettlementFlowService;
import com.tcrm.admin.product.service.MappingImportColumn;
import com.tcrm.admin.product.service.ProductImportColumn;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One company built only through HTTP, plus the job entries the chain tests call directly. Not a
 * base class: each {@code *ChainIT} constructs its own.
 */
final class ChainWorld {
  static final String ADMIN = "X-Admin-Token";
  static final String KOL_HEADER = "X-Kol-Token";
  private static final Pattern ACTIVATE = Pattern.compile("activate\\?token=([A-Za-z0-9_-]{43})");
  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 't', '7', '9'
  };
  private static final String[] CLEANUP = {
    "crm_commission_withdrawal_outbox",
    "crm_commission_withdrawal_payment",
    "crm_commission_withdrawal_history",
    "crm_commission_withdrawal_allocation",
    "crm_commission_withdrawal_order",
    "crm_commission_withdrawal",
    "crm_commission_withdrawal_freeze",
    "crm_commission_withdrawal_sequence",
    "crm_commission_recovery",
    "crm_commission_ledger",
    "crm_commission_wallet",
    "crm_commission_item_revision",
    "crm_commission_item_state",
    "crm_order_settlement_flow",
    "crm_refund",
    "crm_order_item",
    "crm_order",
    "crm_order_sync_failure",
    "crm_order_sync_state",
    "crm_kol_item",
    "crm_product",
    "crm_kol_platform",
    "crm_kol_promotion_record",
    "crm_kol_bd_history",
    "crm_kol_bd_transfer",
    "crm_kol_auth_token",
    "crm_kol_copy_token",
    "crm_kol_register_code",
    "crm_kol_cleanup_item",
    "crm_payout_account",
    "crm_file",
    "crm_notification",
    "crm_task_file",
    "crm_task",
    "crm_kol",
    "crm_link_template",
    "crm_weidian_shop_auth",
    "crm_weidian_app",
    "crm_shop",
    "crm_commission_rule_version"
  };

  private final MockMvc mvc;
  private final ObjectMapper json;
  private final JdbcTemplate jdbc;
  private final org.springframework.data.redis.core.StringRedisTemplate redis;
  private final OrderSettlementFlowService flows;
  private final CommissionReleaseJob releaseJob;
  private final LinkIndexSyncJob linkSync;
  private final FakeWeidian weidian;
  private final String password;

  private long tenantId;
  private long shopId;
  private String adminToken;
  private String bdToken;
  private String financeToken;
  private long bdUserId;
  private String kolToken;
  private long kolId;
  private String kolCode;
  private String email;
  private Ids ids;
  private Instant tradeTime;
  private JsonNode syncedOrder;
  private String detailEnvelope;

  ChainWorld(
      MockMvc mvc,
      ObjectMapper json,
      JdbcTemplate jdbc,
      org.springframework.data.redis.core.StringRedisTemplate redis,
      OrderSettlementFlowService flows,
      CommissionReleaseJob releaseJob,
      LinkIndexSyncJob linkSync,
      FakeWeidian weidian,
      String password) {
    this.mvc = mvc;
    this.json = json;
    this.jdbc = jdbc;
    this.redis = redis;
    this.flows = flows;
    this.releaseJob = releaseJob;
    this.linkSync = linkSync;
    this.weidian = weidian;
    this.password = password;
  }

  /** Company, shop, creator, products and mappings. Pay time is chosen later, by the order stub. */
  void open(String label) throws Exception {
    ids = Ids.next();
    email = "t79-" + ids.suffix() + "@example.test";
    CompanySessions.OpenedCompany company =
        CompanySessions.openCompany(
            mvc, json, password, ids.tenantCode(), "t79-admin-" + ids.suffix());
    tenantId = company.tenantId();
    adminToken = company.token();
    bdUserId = staff("t79-bd-" + ids.suffix(), "BD", null, null);
    staff("t79-fn-" + ids.suffix(), "FINANCE", null, null);
    bdToken = CompanySessions.login(mvc, json, "t79-bd-" + ids.suffix(), password);
    financeToken = CompanySessions.login(mvc, json, "t79-fn-" + ids.suffix(), password);
    shopId = createShop(label);
    authorize();
    checkApis();
    refreshCapacity();
    long templateId = createTemplate();
    createKol(label);
    assignBd();
    reviewAndRate(templateId);
    settlementMinimum();
    activate();
    importCatalog();
    linkSync.drain();
    assertIndex();
  }

  void cleanup() {
    if (tenantId <= 0) {
      return;
    }
    // The link outbox has no tenant column. Rows point at this company's kol, shop or product.
    jdbc.update(
        "DELETE FROM crm_link_index_outbox WHERE scope_id IN ("
            + "SELECT id FROM crm_kol WHERE tenant_id = ? "
            + "UNION SELECT id FROM crm_shop WHERE tenant_id = ? "
            + "UNION SELECT id FROM crm_product WHERE tenant_id = ? "
            + "UNION SELECT id FROM crm_kol_item WHERE tenant_id = ?)",
        tenantId,
        tenantId,
        tenantId,
        tenantId);
    for (String table : CLEANUP) {
      jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenantId);
    }
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
  }

  String adminToken() {
    return adminToken;
  }

  String bdToken() {
    return bdToken;
  }

  String financeToken() {
    return financeToken;
  }

  String kolToken() {
    return kolToken;
  }

  long kolId() {
    return kolId;
  }

  String kolCode() {
    return kolCode;
  }

  long shopId() {
    return shopId;
  }

  long tenantId() {
    return tenantId;
  }

  String orderNo() {
    return ids.order();
  }

  String exclusiveA() {
    return ids.exclusiveA();
  }

  String exclusiveB() {
    return ids.exclusiveB();
  }

  /** Weidian refund number of the n-th refund stubbed by a test. */
  String refund(int n) {
    return ids.refund(n);
  }

  String unmatchedOrder() {
    return ids.unmatched();
  }

  Instant tradeTime() {
    return tradeTime;
  }

  long orderId() {
    return Long.parseLong(syncedOrder.path("id").asString());
  }

  /** Stubs a paid two-line order and syncs it. Pay time is two seconds ahead of the mappings. */
  JsonNode syncPaidOrder() throws Exception {
    tradeTime = Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.SECONDS);
    weidian.stub("vdian.order.list.get", ChainSamples.orderList(ids.order(), tradeTime, "65.00"));
    detailEnvelope =
        ChainSamples.orderDetail(
            ids.order(),
            tradeTime,
            ids.exclusiveA(),
            "40.00",
            ids.exclusiveB(),
            "20.00",
            "5.00",
            "65.00");
    weidian.stub("vdian.order.get", detailEnvelope);
    accepted(
        "/admin-api/order/syncs",
        adminToken,
        json.writeValueAsString(Map.of("shopId", Long.toString(shopId))));
    JsonNode[] found = new JsonNode[1];
    try {
      TestWait.until(
          Duration.ofSeconds(30),
          () -> {
            try {
              JsonNode page =
                  data(ADMIN, adminToken, "/admin-api/order/orders?orderNo=" + ids.order());
              if (Integer.parseInt(page.path("total").asString()) >= 1) {
                found[0] = page.path("items").get(0);
                return true;
              }
              return false;
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          },
          "order " + ids.order() + " was not stored");
    } catch (AssertionError e) {
      throw new AssertionError(e.getMessage() + "; sync=" + syncState(), e);
    }
    syncedOrder = found[0];
    return syncedOrder;
  }

  /**
   * Goods income 6500 fen equals pay 65.00, which confirms the basis. Fee -20 and profit share -10
   * are listed for realism and never enter the basis (task 81). Extra rows (a later refund, an
   * unmatched order) are appended.
   */
  void stubIncome(ChainSamples.Flow... extra) {
    stubGoodsIncome(6500, extra);
  }

  /**
   * Same flows with the given goods income in fen. A partly refunded order receives pay minus the
   * refund, so 5500 fen is the income of 65.00 pay with a 10.00 refund (task 81).
   */
  void stubGoodsIncome(int goodsFen, ChainSamples.Flow... extra) {
    List<ChainSamples.Flow> rows = new ArrayList<>();
    // Fee and profit share are later than the goods income, so release_at can only match
    // the goods-income tradeTime.
    rows.add(flow(ids.order(), null, "收入", "货款收入", goodsFen, tradeTime));
    rows.add(flow(ids.order(), null, "支出", "交易手续费", -20, tradeTime.plusSeconds(30)));
    rows.add(flow(ids.order(), null, "支出", "分润", -10, tradeTime.plusSeconds(60)));
    rows.addAll(List.of(extra));
    weidian.stubTrade(
        "INCOME_DETAIL", ChainSamples.tradeDetail(rows.toArray(ChainSamples.Flow[]::new)));
    weidian.stubTrade("PRE_SETTLE_REFUND", ChainSamples.emptyTradeDetail());
  }

  /** Same flows, but the goods-income amount is 65 (yuan written where fen was expected). */
  void stubIncomeInYuan() {
    List<ChainSamples.Flow> rows = new ArrayList<>();
    rows.add(flow(ids.order(), null, "收入", "货款收入", 65, tradeTime));
    rows.add(flow(ids.order(), null, "支出", "交易手续费", -20, tradeTime.plusSeconds(30)));
    rows.add(flow(ids.order(), null, "支出", "分润", -10, tradeTime.plusSeconds(60)));
    weidian.stubTrade(
        "INCOME_DETAIL", ChainSamples.tradeDetail(rows.toArray(ChainSamples.Flow[]::new)));
    weidian.stubTrade("PRE_SETTLE_REFUND", ChainSamples.emptyTradeDetail());
  }

  /** No income rows. One pre-settle refund, so the basis is a verified zero. */
  void stubPreSettleRefund() {
    weidian.stubTrade("INCOME_DETAIL", ChainSamples.emptyTradeDetail());
    weidian.stubTrade(
        "PRE_SETTLE_REFUND",
        ChainSamples.tradeDetail(flow(ids.order(), ids.refund(), "支出", "退款", -6500)));
  }

  ChainSamples.Flow refundFlow() {
    return flow(ids.order(), ids.refund(), "支出", "退款", -100);
  }

  ChainSamples.Flow unmatchedFlow() {
    return flow(ids.unmatched(), null, "收入", "货款收入", 100);
  }

  /** First pre-settle page of the next pull returns a non-retryable Weidian error. */
  void failNextPreSettle() {
    weidian.onceTrade("PRE_SETTLE_REFUND", ChainSamples.error(10003));
  }

  boolean pull() {
    Instant end = Instant.now().plusSeconds(5);
    return TenantScope.callCompany(
        tenantId, () -> flows.pull(shopId, end.minus(Duration.ofDays(2)), end, null));
  }

  void release() throws Exception {
    TestWait.until(
        Duration.ofSeconds(5),
        () -> !Instant.now().isBefore(tradeTime),
        "release time " + tradeTime + " has not arrived");
    releaseJob.releaseDue();
  }

  /**
   * Re-reads the order with another Weidian status, e.g. {@code close}. The listed update time is
   * ahead of the first sync's pay time, otherwise the sync skips the order. Wait with
   * awaitOrderStatus.
   */
  void restubOrderStatus(String status) throws Exception {
    Instant updated = Instant.now().plusSeconds(10).truncatedTo(ChronoUnit.SECONDS);
    weidian.stub("vdian.order.list.get", ChainSamples.orderList(ids.order(), updated, "65.00"));
    weidian.stub("vdian.order.get", ChainSamples.withOrderStatus(detailEnvelope, status));
    syncAgain();
  }

  void awaitOrderStatus(String status) throws Exception {
    TestWait.until(
        Duration.ofSeconds(30),
        () ->
            status.equals(
                jdbc.queryForObject(
                    "SELECT order_status FROM crm_order WHERE id = ?", String.class, orderId())),
        "order " + ids.order() + " is not " + status);
  }

  /** Stubs one Weidian refund of the order on one order line, then syncs. */
  void stubRefund(String refundNo, String subOrderId, int feeFen, String rawStatus)
      throws Exception {
    String orderId = ids.order();
    weidian.stub(
        "open.SellerQueryRefundList",
        ChainSamples.refundList(refundNo, orderId, BigDecimal.valueOf(feeFen, 2).toPlainString()));
    weidian.stub(
        "open.sellerQueryRefundDetail",
        ChainSamples.refundDetail(refundNo, orderId, feeFen, rawStatus, subOrderId));
    syncAgain();
  }

  /** Waits for the refund row: 10 completed refunds are SUCCESS, any other status is UNKNOWN. */
  void awaitRefundStatus(String refundNo, String status) throws Exception {
    TestWait.until(
        Duration.ofSeconds(30),
        () ->
            jdbc.queryForList(
                    "SELECT refund_status FROM crm_refund WHERE weidian_refund_no = ?",
                    String.class,
                    refundNo)
                .contains(status),
        "refund " + refundNo + " is not " + status);
  }

  /** Stands in for the rejected and cancelled mappings RefundDictionary gains at M0 (L-034). */
  void closeRefund(String refundNo) {
    jdbc.update(
        "UPDATE crm_refund SET refund_status = 'CLOSED' WHERE weidian_refund_no = ?", refundNo);
  }

  /** Syncs one unpaid order on the same two products. Returns its Weidian order id. */
  String syncUnpaidOrder() throws Exception {
    String unpaid = ids.unmatched();
    String detail = ChainSamples.unpaidOrderDetail(samePair(unpaid, Instant.now()));
    return storeOrder(unpaid, detail, "UNPAID");
  }

  /**
   * Syncs a second paid order whose line pays are all zero while the order pays 65.00, so its basis
   * cannot be allocated (ALLOCATION_FAILED): paid but unverified. Returns its Weidian order id.
   */
  String syncPaidOrderWithoutLinePay() throws Exception {
    String other = ids.extraOrder();
    String detail =
        ChainSamples.orderDetail(
            other,
            Instant.now(),
            ids.exclusiveA(),
            "0.00",
            ids.exclusiveB(),
            "0.00",
            "5.00",
            "65.00");
    return storeOrder(other, detail, "PAID");
  }

  private String samePair(String orderId, Instant when) {
    return ChainSamples.orderDetail(
        orderId, when, ids.exclusiveA(), "40.00", ids.exclusiveB(), "20.00", "5.00", "65.00");
  }

  private String storeOrder(String orderId, String detail, String status) throws Exception {
    weidian.stub("vdian.order.list.get", ChainSamples.orderList(orderId, Instant.now(), "65.00"));
    weidian.stub("vdian.order.get", detail);
    syncAgain();
    TestWait.until(
        Duration.ofSeconds(30),
        () ->
            Integer.valueOf(1)
                .equals(
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crm_order WHERE weidian_order_id = ?"
                            + " AND order_status = ?",
                        Integer.class,
                        orderId,
                        status)),
        "order " + orderId + " was not stored as " + status);
    return orderId;
  }

  /** Creator's withdrawal conditions (11.03). */
  JsonNode conditions() throws Exception {
    return data(KOL_HEADER, kolToken, "/kol-api/withdrawals/conditions");
  }

  /** Creator's application, answered as {@code HTTP errorCode} so a rejection can be asserted. */
  String applyWithdrawalOutcome(long payoutAccountId, String amount) throws Exception {
    return outcome(
        post("/kol-api/withdrawals")
            .header(KOL_HEADER, kolToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"idempotencyKey\":\""
                    + UUID.randomUUID()
                    + "\",\"amount\":\""
                    + amount
                    + "\",\"payoutAccountId\":\""
                    + payoutAccountId
                    + "\"}"));
  }

  JsonNode order(String token) throws Exception {
    return data(ADMIN, token, "/admin-api/order/orders/" + orderId());
  }

  JsonNode orders(String token) throws Exception {
    return data(ADMIN, token, "/admin-api/order/orders?orderNo=" + ids.order());
  }

  JsonNode chain(String token) throws Exception {
    return data(ADMIN, token, "/admin-api/order/orders/" + orderId() + "/chain");
  }

  JsonNode overview() throws Exception {
    return data(KOL_HEADER, kolToken, "/kol-api/commissions/overview");
  }

  JsonNode overview(String token) throws Exception {
    return data(KOL_HEADER, token, "/kol-api/commissions/overview");
  }

  JsonNode kolOrders(String token) throws Exception {
    return data(KOL_HEADER, token, "/kol-api/orders");
  }

  JsonNode settlements(String token) throws Exception {
    return data(ADMIN, token, "/admin-api/commission/settlement-details?orderNo=" + ids.order());
  }

  /** PayPal account, approved by the assigned BD. Returns the account id. */
  long approvePayout() throws Exception {
    JsonNode created =
        created(
            KOL_HEADER,
            kolToken,
            "/kol-api/payout-accounts",
            "{\"accountType\":\"PAYPAL\",\"account\":\"t79-"
                + ids.suffix()
                + "@example.test\",\"holderName\":\"T79 Creator\",\"currency\":\"USD\"}");
    String id = created.path("id").asString();
    // last_submitted_at is TIMESTAMPTZ(3). The create response still carries the in-memory
    // instant, so the reviewer reads the stored stamp from the detail the same way the UI does.
    String stamp =
        data(ADMIN, bdToken, "/admin-api/commission/payout-accounts/" + id)
            .path("lastSubmittedAt")
            .asString();
    putOk(
        "/admin-api/commission/payout-accounts/" + id + "/review",
        bdToken,
        "{\"action\":\"APPROVE\",\"lastSubmittedAt\":\"" + stamp + "\"}");
    return Long.parseLong(id);
  }

  long applyWithdrawal(long payoutAccountId, String amount) throws Exception {
    JsonNode body =
        created(
            KOL_HEADER,
            kolToken,
            "/kol-api/withdrawals",
            "{\"idempotencyKey\":\""
                + UUID.randomUUID()
                + "\",\"amount\":\""
                + amount
                + "\",\"payoutAccountId\":\""
                + payoutAccountId
                + "\"}");
    return Long.parseLong(body.path("id").asString());
  }

  void approveWithdrawal(long withdrawalId) throws Exception {
    putOk(
        "/admin-api/commission/withdrawals/" + withdrawalId + "/review",
        bdToken,
        "{\"decision\":\"APPROVE\"}");
  }

  void pay(long withdrawalId, String amount) throws Exception {
    MvcResult uploaded =
        mvc.perform(
                multipart("/admin-api/files")
                    .file(new MockMultipartFile("file", "t79.png", "image/png", PNG))
                    .param("bizType", "WITHDRAWAL_VOUCHER")
                    .header(ADMIN, financeToken))
            .andExpect(status().isOk())
            .andReturn();
    String fileId =
        json.readTree(uploaded.getResponse().getContentAsString()).at("/data/fileId").asString();
    String paidAt = java.time.OffsetDateTime.now(ZoneOffset.UTC).toString();
    putOk(
        "/admin-api/commission/withdrawals/" + withdrawalId + "/payment",
        financeToken,
        "{\"idempotencyKey\":\""
            + UUID.randomUUID()
            + "\",\"amountCny\":\""
            + amount
            + "\",\"actualAmount\":\""
            + amount
            + "\",\"actualCurrency\":\"CNY\",\"paidAt\":\""
            + paidAt
            + "\",\"method\":\"PAYPAL\",\"voucher\":\""
            + fileId
            + "\"}");
  }

  JsonNode withdrawal(String token, long withdrawalId) throws Exception {
    return data(ADMIN, token, "/admin-api/commission/withdrawals/" + withdrawalId);
  }

  JsonNode kolWithdrawal(long withdrawalId) throws Exception {
    return data(KOL_HEADER, kolToken, "/kol-api/withdrawals/" + withdrawalId);
  }

  /** Another creator in the same company, activated from the invitation mail. */
  String otherKolToken(String label) throws Exception {
    String otherEmail = "t79-b-" + label + "-" + ids.suffix() + "@example.test";
    created(
        ADMIN,
        adminToken,
        "/admin-api/kol/kols",
        "{\"name\":\"t79 other "
            + label
            + "\",\"email\":\""
            + otherEmail
            + "\",\"country\":\"US\"}");
    return activate(otherEmail);
  }

  String login(String username) throws Exception {
    return CompanySessions.login(mvc, json, username, password);
  }

  String basisReason() {
    return jdbc.queryForObject(
        "SELECT commission_basis_reason FROM crm_order WHERE id = ?", String.class, orderId());
  }

  String buyerHash() {
    return jdbc.queryForObject(
        "SELECT buyer_hash FROM crm_order WHERE id = ?", String.class, orderId());
  }

  List<Map<String, Object>> itemStates() {
    return jdbc.queryForList(
        "SELECT i.weidian_item_id, s.verified, s.basis_cny, s.accrued_commission_cny,"
            + " s.release_basis, s.release_at FROM crm_commission_item_state s"
            + " JOIN crm_order_item i ON i.id = s.order_item_id WHERE i.order_id = ?"
            + " ORDER BY i.weidian_item_id",
        orderId());
  }

  List<Map<String, Object>> ledger() {
    return jdbc.queryForList(
        "SELECT entry_type, amount_cny FROM crm_commission_ledger WHERE kol_id = ?"
            + " ORDER BY id",
        kolId);
  }

  /** Matched {@code crm_order.id} per stored flow. Null means the Weidian order id is not ours. */
  List<Long> flowOrderIds(String weidianOrderId) {
    return jdbc.query(
        "SELECT order_id FROM crm_order_settlement_flow WHERE shop_id = ? AND weidian_order_id = ?"
            + " ORDER BY id",
        (rs, row) -> (Long) rs.getObject("order_id"),
        shopId,
        weidianOrderId);
  }

  /**
   * Triggers another order sync without moving the pay time. The call returns before it finishes.
   */
  void syncAgain() throws Exception {
    String body = json.writeValueAsString(Map.of("shopId", Long.toString(shopId)));
    // A sync still running answers ORDER_SYNC_RUNNING (409); wait until the shop lock is free.
    TestWait.until(
        Duration.ofSeconds(30),
        () -> {
          try {
            return postStatus(adminToken, "/admin-api/order/syncs", body) == 202;
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        },
        "order sync did not start");
  }

  String otherCompanyToken() throws Exception {
    Ids other = Ids.next();
    return CompanySessions.openCompany(
            mvc, json, password, other.tenantCode(), "t79-x-" + other.suffix())
        .token();
  }

  String otherBdToken() throws Exception {
    String username = "t79-ob-" + ids.suffix();
    staff(username, "BD", null, null);
    return login(username);
  }

  /**
   * OWN staff who does not own this creator. The permission list replaces the BD template, so the
   * account can pass {@code commission:freeze} and {@code withdrawal:pay} and still miss the
   * creator.
   */
  String unassignedFundsToken() throws Exception {
    String username = "t79-uf-" + ids.suffix();
    staff(username, "BD", "OWN", List.of("commission:view", "commission:freeze", "withdrawal:pay"));
    return login(username);
  }

  String viewerToken() throws Exception {
    String username = "t79-vw-" + ids.suffix();
    staff(username, "BD", "ALL", List.of("order:view"));
    return login(username);
  }

  int getStatus(String header, String token, String path) throws Exception {
    return mvc.perform(get(path).header(header, token)).andReturn().getResponse().getStatus();
  }

  int postStatus(String token, String path, String body) throws Exception {
    return mvc.perform(
            post(path).header(ADMIN, token).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  int putStatus(String token, String path, String body) throws Exception {
    return mvc.perform(
            put(path).header(ADMIN, token).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /** HTTP status and {@code errorCode}, so a scope miss is not confused with a permission 403. */
  String postOutcome(String token, String path, String body) throws Exception {
    return outcome(
        post(path).header(ADMIN, token).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  String putOutcome(String token, String path, String body) throws Exception {
    return outcome(
        put(path).header(ADMIN, token).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private String outcome(RequestBuilder request) throws Exception {
    MvcResult result = mvc.perform(request).andReturn();
    String raw = result.getResponse().getContentAsString();
    String code = json.readTree(raw).path("errorCode").asString();
    return result.getResponse().getStatus() + " " + code;
  }

  /** Matches ledger rows by type, comparing each amount with {@code isEqualByComparingTo}. */
  static void assertLedger(List<Map<String, Object>> rows, String... pairs) {
    assertThat(rows).hasSize(pairs.length);
    List<Map<String, Object>> left = new ArrayList<>(rows);
    for (String pair : pairs) {
      int split = pair.indexOf('=');
      String type = pair.substring(0, split);
      BigDecimal amount = new BigDecimal(pair.substring(split + 1));
      Map<String, Object> match = null;
      for (Map<String, Object> row : left) {
        BigDecimal actual = money(row.get("amount_cny"));
        if (type.equals(String.valueOf(row.get("entry_type")))
            && actual != null
            && actual.compareTo(amount) == 0) {
          match = row;
          break;
        }
      }
      assertThat(match).as(pair).isNotNull();
      assertThat(money(match.get("amount_cny"))).isEqualByComparingTo(amount);
      left.remove(match);
    }
  }

  int revisionCount() {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM crm_commission_item_revision r"
                + " JOIN crm_commission_item_state s ON s.id = r.item_state_id"
                + " JOIN crm_order_item i ON i.id = s.order_item_id WHERE i.order_id = ?",
            Integer.class,
            orderId());
    return count == null ? 0 : count;
  }

  List<String> outboxEvents(long withdrawalId) {
    return jdbc.queryForList(
        "SELECT event_type FROM crm_commission_withdrawal_outbox WHERE withdrawal_id = ?"
            + " ORDER BY id",
        String.class,
        withdrawalId);
  }

  static void money(JsonNode node, String expected) {
    assertThat(new BigDecimal(node.asString())).isEqualByComparingTo(expected);
  }

  /** Page totals are strings when the field is a long and numbers when it is an int. */
  static int count(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return 0;
    }
    if (node.isNumber()) {
      return node.intValue();
    }
    return Integer.parseInt(node.asString());
  }

  static BigDecimal money(Object value) {
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Timestamp) {
      return null;
    }
    return value == null ? null : new BigDecimal(value.toString());
  }

  static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    return value instanceof Instant stored ? stored : null;
  }

  private ChainSamples.Flow flow(
      String orderId, String refundId, String type, String subType, int amountFen) {
    return flow(orderId, refundId, type, subType, amountFen, tradeTime);
  }

  private ChainSamples.Flow flow(
      String orderId, String refundId, String type, String subType, int amountFen, Instant when) {
    return new ChainSamples.Flow(orderId, refundId, type, subType, amountFen, when);
  }

  private long staff(String username, String template, String scope, List<String> permissions)
      throws Exception {
    return CompanySessions.createStaff(
        mvc, json, adminToken, password, username, template, scope, permissions);
  }

  private long createShop(String label) throws Exception {
    JsonNode body =
        created(
            ADMIN,
            adminToken,
            "/admin-api/product/shops",
            json.writeValueAsString(
                Map.of(
                    "shopName",
                    "t79 " + label,
                    "channel",
                    "WEIDIAN",
                    "weidianShopId",
                    ids.shop())));
    return Long.parseLong(body.path("id").asString());
  }

  private void authorize() throws Exception {
    putOk(
        "/admin-api/integration/weidian/app",
        adminToken,
        "{\"appKey\":\"t79-app\",\"appSecret\":\"t79-secret\"}");
    weidian.openid(ids.shop());
    weidian.stub(
        "vdian.item.list.get",
        ChainSamples.itemList(ids.shop(), ids.productItemA(), ids.productItemB()));
    weidian.stub("vdian.order.list.get", ChainSamples.emptyOrderList());
    weidian.stub("open.SellerQueryRefundList", ChainSamples.emptyRefundList());
    JsonNode started =
        created(
            ADMIN,
            adminToken,
            "/admin-api/integration/weidian/shops/" + shopId + "/authorizations",
            "");
    String url = started.path("authorizeUrl").asString();
    String state = url.substring(url.indexOf("state=") + "state=".length());
    int amp = state.indexOf('&');
    if (amp >= 0) {
      state = state.substring(0, amp);
    }
    mvc.perform(
            get("/admin-api/integration/weidian/oauth/callback")
                .param("state", state)
                .param("code", "t79-code"))
        .andExpect(status().isFound());
  }

  private void checkApis() throws Exception {
    JsonNode status = data(ADMIN, adminToken, postPath(shopId, "api-checks"));
    assertThat(status.at("/apiStatus/product/ok").asBoolean()).isTrue();
    assertThat(status.at("/apiStatus/order/ok").asBoolean()).isTrue();
    assertThat(status.at("/apiStatus/refund/ok").asBoolean()).isTrue();
    assertThat(weidian.count("vdian.item.list.get")).isPositive();
  }

  private void refreshCapacity() throws Exception {
    JsonNode body = data(ADMIN, adminToken, postPath(shopId, "capacity-refreshes"));
    assertThat(Integer.parseInt(body.path("currentItems").asString())).isEqualTo(2);
  }

  private String postPath(long id, String action) throws Exception {
    MvcResult result =
        mvc.perform(post("/admin-api/product/shops/" + id + "/" + action).header(ADMIN, adminToken))
            .andExpect(status().isOk())
            .andReturn();
    return result.getResponse().getContentAsString();
  }

  private long createTemplate() throws Exception {
    JsonNode body =
        created(
            ADMIN,
            adminToken,
            "/admin-api/link/templates",
            "{\"templateCode\":\"t79-"
                + ids.suffix()
                + "\",\"templateName\":\"t79\",\"templateUrl\":"
                + "\"https://weidian.com/item.html?itemID={PRODUCT_ID}\",\"sortOrder\":0}");
    return Long.parseLong(body.path("id").asString());
  }

  private void createKol(String label) throws Exception {
    JsonNode body =
        created(
            ADMIN,
            adminToken,
            "/admin-api/kol/kols",
            "{\"name\":\"t79 " + label + "\",\"email\":\"" + email + "\",\"country\":\"US\"}");
    kolId = Long.parseLong(body.path("id").asString());
    kolCode = body.path("kolCode").asString();
  }

  private void assignBd() throws Exception {
    putOk(
        "/admin-api/kol/kols/" + kolId + "/bd-user",
        adminToken,
        "{\"bdUserId\":\"" + bdUserId + "\"}");
  }

  private void reviewAndRate(long templateId) throws Exception {
    MvcResult review =
        mvc.perform(
                post("/admin-api/kol/kols/" + kolId + "/review")
                    .header(ADMIN, adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"t79 kol\"}"))
            .andReturn();
    assertThat(review.getResponse().getStatus())
        .as(review.getResponse().getContentAsString())
        .isEqualTo(200);
    putOk(
        "/admin-api/kol/kols/" + kolId + "/promotion-config",
        adminToken,
        "{\"defaultRate\":\"0.1000\",\"sheetUrl\":"
            + "\"https://docs.google.com/spreadsheets/d/t79/edit\","
            + "\"remark\":\"chain\",\"inviteCode\":\"t79invite\","
            + "\"platforms\":[{\"templateId\":\""
            + templateId
            + "\",\"address\":\"https://landing.example/t79\"}],"
            + "\"jumpTemplateId\":"
            + templateId
            + "}");
  }

  private void settlementMinimum() throws Exception {
    JsonNode current = data(ADMIN, adminToken, "/admin-api/commission/settlement-configs/current");
    int version = Integer.parseInt(current.path("version").asString());
    JsonNode saved =
        putData(
            "/admin-api/commission/settlement-configs/current",
            adminToken,
            "{\"version\":" + version + ",\"minimum\":\"1.00\",\"days\":7}");
    assertThat(saved.path("minimum").asString()).isEqualTo("1.00");
  }

  private void activate() throws Exception {
    kolToken = activate(email);
  }

  private String activate(String address) throws Exception {
    String[] token = new String[1];
    TestWait.until(
        Duration.ofSeconds(10),
        () -> {
          for (var mail : ChainMailCapture.SENT) {
            if (address.equals(mail.to()) && "kol-invite".equals(mail.template())) {
              Matcher matcher = ACTIVATE.matcher(mail.text());
              if (matcher.find()) {
                token[0] = matcher.group(1);
                return true;
              }
            }
          }
          return false;
        },
        "invitation for " + address + " did not arrive");
    postOk(
        "/kol-api/auth/activate",
        "{\"token\":\"" + token[0] + "\",\"password\":\"" + password + "\"}");
    JsonNode login =
        created(
            null,
            null,
            "/kol-api/auth/login",
            "{\"email\":\"" + address + "\",\"password\":\"" + password + "\"}");
    return login.path("token").asString();
  }

  private void importCatalog() throws Exception {
    byte[] products =
        workbook(
            TestTemplates.render(ProductImportColumn.headerKeys()),
            List.of(
                List.of(ids.codeA(), ids.shop(), ids.productItemA(), "t79-a", "", "", "10.00", ""),
                List.of(
                    ids.codeB(), ids.shop(), ids.productItemB(), "t79-b", "", "", "10.00", "")));
    JsonNode imported = awaitTask(submit("/admin-api/product/product-imports", products));
    assertThat(imported.path("status").asString()).as(imported.toString()).isEqualTo("SUCCESS");
    byte[] mappings =
        workbook(
            TestTemplates.render(MappingImportColumn.headerKeys()),
            List.of(
                List.of(ids.shop(), ids.exclusiveA(), kolCode, ids.codeA()),
                List.of(ids.shop(), ids.exclusiveB(), kolCode, ids.codeB())));
    JsonNode mapped = awaitTask(submit("/admin-api/product/kol-item-imports", mappings));
    assertThat(mapped.path("status").asString()).as(mapped.toString()).isEqualTo("SUCCESS");
  }

  private void assertIndex() throws Exception {
    JsonNode page = data(ADMIN, adminToken, "/admin-api/product/kol-items?size=20");
    assertThat(page.path("items")).hasSize(2);
    for (JsonNode item : page.path("items")) {
      String exclusive = item.path("targetItemId").asString();
      String code = exclusive.equals(ids.exclusiveA()) ? ids.codeA() : ids.codeB();
      String value =
          "{\"kolItemId\":\""
              + item.path("id").asString()
              + "\",\"targetUrl\":\"https://weidian.com/item.html?itemID="
              + exclusive
              + "\",\"status\":1}";
      assertThat(redis.opsForHash().get("link:" + kolCode, code)).isEqualTo(value);
      String bucket = exclusive.substring(exclusive.length() - 2);
      assertThat(redis.opsForHash().get("linkitem:" + bucket, exclusive)).isEqualTo(value);
    }
  }

  private String syncState() throws Exception {
    return data(ADMIN, adminToken, "/admin-api/order/sync-states").toString();
  }

  private String submit(String path, byte[] workbook) throws Exception {
    MvcResult submit =
        mvc.perform(
                multipart(path)
                    .file(
                        new MockMultipartFile(
                            "file", "f.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, workbook))
                    .header(ADMIN, adminToken))
            .andExpect(status().isAccepted())
            .andReturn();
    return json.readTree(submit.getResponse().getContentAsString()).at("/data/taskId").asString();
  }

  private JsonNode awaitTask(String taskId) throws Exception {
    JsonNode[] holder = new JsonNode[1];
    TestWait.until(
        Duration.ofSeconds(30),
        Duration.ofMillis(100),
        () -> {
          try {
            JsonNode body = data(ADMIN, adminToken, "/admin-api/task/tasks/" + taskId);
            String state = body.path("status").asString();
            if ("SUCCESS".equals(state) || "PARTIAL".equals(state) || "FAILED".equals(state)) {
              holder[0] = body;
              return true;
            }
            return false;
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        },
        "import task " + taskId + " did not finish");
    return holder[0];
  }

  private static byte[] workbook(List<String> headers, List<List<String>> rows) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out)
        .head(headers.stream().map(List::of).toList())
        .sheet("Sheet1")
        .doWrite(rows);
    return out.toByteArray();
  }

  private JsonNode data(String header, String token, String path) throws Exception {
    if (path.startsWith("{")) {
      return json.readTree(path).path("data");
    }
    var request = get(path);
    if (header != null) {
      request.header(header, token);
    }
    MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
    return json.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private JsonNode created(String header, String token, String path, String body) throws Exception {
    MvcResult result = send(path, header, token, body).andExpect(status().isOk()).andReturn();
    return json.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private void accepted(String path, String token, String body) throws Exception {
    send(path, ADMIN, token, body).andExpect(status().isAccepted());
  }

  private void postOk(String path, String body) throws Exception {
    send(path, null, null, body).andExpect(status().isOk());
  }

  private void putOk(String path, String token, String body) throws Exception {
    putData(path, token, body);
  }

  private JsonNode putData(String path, String token, String body) throws Exception {
    MvcResult result =
        mvc.perform(
                put(path)
                    .header(ADMIN, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private org.springframework.test.web.servlet.ResultActions send(
      String path, String header, String token, String body) throws Exception {
    var request = post(path).contentType(MediaType.APPLICATION_JSON);
    if (header != null) {
      request.header(header, token);
    }
    if (!body.isEmpty()) {
      request.content(body);
    }
    return mvc.perform(request);
  }

  /** Digits derived from the clock so shops, items and orders do not collide inside one class. */
  private record Ids(long base) {
    static Ids next() {
      long tick = Math.floorMod(System.nanoTime(), 100_000_000L);
      return new Ids(790_000_000_000L + tick);
    }

    String suffix() {
      String hex = Long.toHexString(base);
      return hex.substring(Math.max(0, hex.length() - 8));
    }

    String tenantCode() {
      return "t79-" + suffix();
    }

    String shop() {
      return Long.toString(base);
    }

    String productItemA() {
      return Long.toString(base + 1);
    }

    String productItemB() {
      return Long.toString(base + 2);
    }

    String order() {
      return Long.toString(base + 3);
    }

    String unmatched() {
      return Long.toString(base + 4);
    }

    String refund() {
      return Long.toString(base + 5);
    }

    String extraOrder() {
      return Long.toString(base + 60);
    }

    /** Refund number for the n-th refund of a test; each refund number is unique. */
    String refund(int n) {
      return Long.toString(base + 100 + n);
    }

    String exclusiveA() {
      return Long.toString(base + 11);
    }

    String exclusiveB() {
      return Long.toString(base + 12);
    }

    String codeA() {
      return "T79A" + String.format("%04d", base % 10_000);
    }

    String codeB() {
      return "T79B" + String.format("%04d", (base + 1) % 10_000);
    }
  }
}
