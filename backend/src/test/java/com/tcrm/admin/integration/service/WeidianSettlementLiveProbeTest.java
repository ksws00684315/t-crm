package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tcrm.admin.shared.SecretCipher;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manual, read-only probe of the Weidian settlement fields behind the commission basis (task 75):
 * {@code shop.order.trade.detail} (settlement detail) and {@code vdian.order.get} 2.0. It only
 * records which fields carry the withdrawable amount, the settle time and the pre-settle estimate;
 * wiring them into commission stays a later task.
 *
 * <p>Tagged {@code weidian-live}: excluded from the default build and from docker-it. Run by hand
 * with the command written into docs/04-开发任务/75-微店结算字段-实测结果.md, plus the {@code TCRM_SECRET_KEY} /
 * {@code TCRM_DB_*} / {@code WEIDIAN_LIVE_SHOP_ID} environment. The probe is read-only: the
 * database is only SELECTed ({@code crm_weidian_shop_auth}, {@code crm_order}), the access token is
 * decrypted but never refreshed, only the two whitelisted methods are called, at most {@value
 * #MAX_REQUESTS} requests with at least one second between neighbours, and no Weidian write
 * operation is ever attempted. The generated report never contains tokens, secrets, database
 * passwords or buyer data; identifiers are reduced to their last four characters while amounts,
 * times, statuses and sub types are kept verbatim.
 */
@Tag("weidian-live")
class WeidianSettlementLiveProbeTest {

  /** The only two methods this probe may ever call; everything else throws. */
  static final Set<String> READ_ONLY_METHODS = Set.of("shop.order.trade.detail", "vdian.order.get");

  /** Fragments no whitelisted method name may contain (write-shaped verbs). */
  static final List<String> FORBIDDEN_FRAGMENTS =
      List.of("add", "delete", "save", "update", "create", "modify", "deliver", "refund", "cancel");

  private static final List<String> REQUIRED_ENV =
      List.of(
          "TCRM_SECRET_KEY",
          "TCRM_DB_PORT",
          "TCRM_DB_NAME",
          "TCRM_DB_USERNAME",
          "TCRM_DB_PASSWORD",
          "WEIDIAN_LIVE_SHOP_ID");
  private static final Set<Integer> TOKEN_REJECTED_CODES = Set.of(10013, 10016, 10021, 10026);
  private static final String API_BASE_URL = "https://api.vdian.com/api";
  private static final int MAX_REQUESTS = 30;
  private static final Duration MIN_GAP = Duration.ofSeconds(1);
  private static final int PAGE_SIZE = 100;
  private static final int PAGES_PER_WINDOW = 2;
  private static final int MAX_WINDOWS = 4;
  private static final int MONTHS_PER_WINDOW = 3;
  private static final int WINDOW_MARGIN_DAYS = 2;
  private static final int MAX_ORDER_DETAILS = 5;
  private static final int MAX_UNIT_MATCHES = 3;
  private static final int MAX_LISTED_GROUPS = 40;
  private static final int MAX_LISTED_COMBOS = 50;
  private static final int MAX_LISTED_FIELDS = 60;

  /** Pure integers with at least this many digits are identifiers, not amounts. */
  private static final int ID_DIGITS = 9;

  private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BEIJING);
  private static final Path REPORT = Path.of("..", "docs", "04-开发任务", "75-微店结算字段-实测结果.md");
  private static final Pattern NUMERIC = Pattern.compile("-?\\d+(\\.\\d+)?");

  /** Leaf field names that look money- or time-shaped (matched lowercase, anywhere in the name). */
  private static final Pattern MONEY_TIME =
      Pattern.compile(
          ".*(fee|price|total|income|amount|pay|refund|commission|discount|time|date).*");

  /** Field names whose subtree is skipped entirely so no buyer data can reach the report. */
  private static final Pattern PII =
      Pattern.compile(".*(buyer|receiver|address|phone|mobile|tel|nick|consignee|name).*");

  /** Order detail fields the production parser already reads; section 6 lists what it misses. */
  private static final Set<String> PARSER_READ_FIELDS =
      Set.of(
          "order_id",
          "status",
          "status_desc",
          "total",
          "price",
          "add_time",
          "pay_time",
          "items",
          "item_id",
          "item_name",
          "total_price",
          "quantity");

  @Test
  void shouldProbeSettlementFieldsOfConfiguredShop() throws Exception {
    List<String> missing =
        REQUIRED_ENV.stream().filter(name -> blank(System.getenv(name))).toList();
    assumeTrue(missing.isEmpty(), () -> "跳过：缺少环境变量 " + missing);
    assertThat(READ_ONLY_METHODS)
        .hasSize(2)
        .containsExactlyInAnyOrder("shop.order.trade.detail", "vdian.order.get");
    assertThat(READ_ONLY_METHODS)
        .allSatisfy(
            method ->
                assertThat(method.toLowerCase(Locale.ROOT))
                    .doesNotContain(FORBIDDEN_FRAGMENTS.toArray(String[]::new)));

    long shopId = Long.parseLong(System.getenv("WEIDIAN_LIVE_SHOP_ID").strip());
    ProbeRun run = new ProbeRun(shopId);
    try {
      run.probe();
    } finally {
      run.write();
    }
  }

  private static AuthRow loadAuth(long shopId) {
    try (Connection conn = connection()) {
      conn.setReadOnly(true);
      try (PreparedStatement ps =
          conn.prepareStatement(
              "SELECT access_token_cipher, access_expires_at, status"
                  + " FROM crm_weidian_shop_auth WHERE shop_id = ?")) {
        ps.setLong(1, shopId);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            return null;
          }
          Timestamp expires = rs.getTimestamp(2);
          return new AuthRow(
              rs.getString(1), expires == null ? null : expires.toInstant(), rs.getString(3));
        }
      }
    } catch (SQLException e) {
      throw new AssertionError("数据库读取失败（SQLState " + e.getSQLState() + "），请检查数据库配置");
    }
  }

  /** All non-deleted orders of the shop, oldest first; used for samples and id matching. */
  private static List<OrderRow> loadOrders(long shopId) {
    try (Connection conn = connection()) {
      conn.setReadOnly(true);
      try (PreparedStatement ps =
          conn.prepareStatement(
              "SELECT weidian_order_id, order_status, raw_status, pay_time, pay_amount"
                  + " FROM crm_order WHERE shop_id = ? AND is_deleted = false ORDER BY pay_time")) {
        ps.setLong(1, shopId);
        try (ResultSet rs = ps.executeQuery()) {
          List<OrderRow> rows = new ArrayList<>();
          while (rs.next()) {
            Timestamp pay = rs.getTimestamp(4);
            rows.add(
                new OrderRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    pay == null ? null : pay.toInstant(),
                    rs.getBigDecimal(5)));
          }
          return rows;
        }
      }
    } catch (SQLException e) {
      throw new AssertionError("数据库读取失败（SQLState " + e.getSQLState() + "），请检查数据库配置");
    }
  }

  private static Connection connection() throws SQLException {
    String url =
        "jdbc:postgresql://127.0.0.1:"
            + System.getenv("TCRM_DB_PORT").strip()
            + "/"
            + System.getenv("TCRM_DB_NAME").strip();
    return DriverManager.getConnection(
        url, System.getenv("TCRM_DB_USERNAME"), System.getenv("TCRM_DB_PASSWORD"));
  }

  private static String decrypt(String cipherText) {
    try {
      return new SecretCipher(System.getenv("TCRM_SECRET_KEY")).decrypt(cipherText);
    } catch (RuntimeException e) {
      throw new AssertionError("令牌解密失败：请核对 TCRM_SECRET_KEY（详情已省略，不输出密钥）");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String mask(String id) {
    return id == null || id.length() <= 4 ? "***" : "***" + id.substring(id.length() - 4);
  }

  private static String cell(String value) {
    return value == null ? "-" : value.replace("|", "/").replace('\n', ' ');
  }

  /**
   * Leaf sample value: numbers, booleans and number/time-shaped strings verbatim, else presence.
   * Long pure-integer values are identifiers, not amounts, so they are masked to their last four
   * characters unless the field name says it is a time.
   */
  private static String sampleValue(String leafName, JsonNode value) {
    String name = leafName.toLowerCase(Locale.ROOT);
    boolean timeNamed = name.contains("time") || name.contains("date");
    if (value.isNumber() || value.isBoolean()) {
      String text = value.asString();
      if (value.isIntegralNumber() && !timeNamed && text.replace("-", "").length() >= ID_DIGITS) {
        return mask(text);
      }
      return text;
    }
    if (value.isString()) {
      String text = value.asString().strip();
      if (NUMERIC.matcher(text).matches()) {
        if (!timeNamed && !text.contains(".") && text.length() >= ID_DIGITS) {
          return mask(text);
        }
        return text;
      }
      if (text.matches("\\d{4}-\\d{2}-\\d{2}[ T].*")) {
        return text;
      }
    }
    return "存在（字符串不输出）";
  }

  private record AuthRow(String tokenCipher, Instant expiresAt, String status) {}

  private record OrderRow(
      String orderId, String status, String rawStatus, Instant payTime, BigDecimal payAmount) {}

  /** One settlement ledger entry, kept verbatim. */
  private record TradeRow(
      String orderId,
      String refundId,
      String tradeType,
      String tradeSubType,
      String amount,
      String tradeTimeRaw,
      String tradeTimeBeijing) {

    BigDecimal amountValue() {
      try {
        return new BigDecimal(amount);
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }

  private record Call(String status, JsonNode result) {
    static Call success(JsonNode result) {
      return new Call("成功", result);
    }

    static Call failure(String status) {
      return new Call(status, null);
    }

    boolean succeeded() {
      return result != null;
    }
  }

  /** One run: enforces the read-only whitelist, pacing, request cap, and writes the report. */
  private static final class ProbeRun {
    private final Instant startedAt = Instant.now();
    private final ZonedDateTime windowAnchor = ZonedDateTime.now(BEIJING);
    private final long shopId;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> lines = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final Map<String, List<TradeRow>> tradesByOrder = new LinkedHashMap<>();
    private final Map<String, Integer> comboCounts = new LinkedHashMap<>();
    private final Set<String> orderIdSuffixes = new LinkedHashSet<>();
    private Instant expiresAt;
    private String token;
    private RestTemplate http;
    private int requests;
    private Instant lastCallEndedAt;
    private boolean tokenRejected;
    private boolean settlementAvailable;
    private int dataWindow;
    private int settledMatchedOrders;

    ProbeRun(long shopId) {
      this.shopId = shopId;
    }

    void probe() {
      AuthRow auth = loadAuth(shopId);
      assumeTrue(auth != null, () -> "跳过：crm_weidian_shop_auth 中无该店铺授权记录");
      expiresAt = auth.expiresAt();
      assumeTrue(auth.tokenCipher() != null, () -> "跳过：该店铺授权记录无访问令牌");
      if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
        note("令牌已过期：未调用任何接口，未刷新令牌，未写数据库");
        assumeTrue(false, () -> "令牌已过期");
      }
      token = decrypt(auth.tokenCipher());
      http = new WeidianHttpConfiguration().weidianRestTemplate();

      List<OrderRow> orders = loadOrders(shopId);
      List<OrderRow> paidOrders =
          orders.stream().filter(row -> row.payTime() != null).limit(MAX_ORDER_DETAILS).toList();
      Set<String> knownOrderIds = new LinkedHashSet<>();
      orders.forEach(row -> knownOrderIds.add(row.orderId()));

      section(
          1,
          "结算明细能否调用（shop.order.trade.detail 1.0，queryType=INCOME_DETAIL，pageSize="
              + PAGE_SIZE
              + "）");
      line("- 查询窗口：" + describe(window(0)) + "（文档上限「不超过 3 个月」；整 3 个日历月跨度曾被接口拒绝，窗口统一缩短 2 天）");
      Call first =
          invoke(
              "shop.order.trade.detail",
              "1.0",
              tradeDetailParam(1, window(0), "INCOME_DETAIL"),
              null);
      line("- 不带 ctx：" + first.status());
      settlementAvailable = first.succeeded();
      Call withCtx = first;
      if (!settlementAvailable) {
        ObjectNode ctx = json.createObjectNode().put("offline_shop_id", String.valueOf(shopId));
        withCtx =
            invoke(
                "shop.order.trade.detail",
                "1.0",
                tradeDetailParam(1, window(0), "INCOME_DETAIL"),
                ctx);
        line(
            "- 带 ctx（offline_shop_id 末 4 位 "
                + mask(String.valueOf(shopId))
                + "）："
                + withCtx.status());
        settlementAvailable = withCtx.succeeded();
      } else {
        line("- 带 ctx：未尝试（不带 ctx 已成功）");
      }
      if (settlementAvailable) {
        section(
            2,
            "结算明细样本统计（INCOME_DETAIL，最多 " + MAX_WINDOWS + " 个窗口，每窗口最多 " + PAGES_PER_WINDOW + " 页）");
        collectSampleWindows(withCtx);
        stats(knownOrderIds);
        preSettleRefund();
        unitEvidence(knownOrderIds);
      } else {
        line("- 两次均失败，跳过第 2～4 步（样本统计、PRE_SETTLE_REFUND、金额单位判断），直接做订单详情。");
        line("- 如错误提示需要权限，请负责人在微店开放平台为该服务型应用申请对应接口权限（错误信息原文见上）。");
      }

      section(5, "订单详情（vdian.order.get 2.0，该店铺已付款订单，最多 " + MAX_ORDER_DETAILS + " 笔）");
      if (paidOrders.isEmpty()) {
        line("- 跳过：crm_order 中该店铺没有已付款订单。");
      }
      JsonNode firstDetail = null;
      for (OrderRow order : paidOrders) {
        Call detail = invoke("vdian.order.get", "2.0", orderParam(order.orderId()));
        if (detail.succeeded()) {
          if (firstDetail == null) {
            firstDetail = detail.result();
          }
          orderDetailLines(order, detail.result());
        } else {
          line("### 订单 " + mask(order.orderId()));
          line("");
          line("- " + detail.status());
          line("");
        }
      }

      section(6, "解析器（WeidianBusinessApiParser.orderDetail）未读取的金额/时间字段");
      if (firstDetail == null) {
        line("- 跳过：没有成功的订单详情响应。");
      } else {
        parserUnreadFields(firstDetail);
      }

      assertThat(requests).isBetween(1, MAX_REQUESTS);
    }

    private record Window(ZonedDateTime start, ZonedDateTime end) {}

    /**
     * Window 0 is the most recent period; window w starts {@code w} windows before. A full calendar
     * three-month span is rejected by the API (查询时间跨度不能超过3个月), so every window is kept two days
     * shorter — under both a calendar-month and a 90-day reading of the documented limit.
     */
    private Window window(int index) {
      return new Window(
          windowAnchor
              .minusMonths((long) MONTHS_PER_WINDOW * (index + 1))
              .plusDays(WINDOW_MARGIN_DAYS),
          windowAnchor.minusMonths((long) MONTHS_PER_WINDOW * index));
    }

    private ObjectNode tradeDetailParam(int pageNo, Window range, String queryType) {
      return json.createObjectNode()
          .put("pageNo", pageNo)
          .put("pageSize", PAGE_SIZE)
          .put("startTime", range.start().toInstant().toEpochMilli())
          .put("endTime", range.end().toInstant().toEpochMilli())
          .put("queryType", queryType);
    }

    /** Numeric order ids go out as numbers (2.0 API), anything else stays a string. */
    private ObjectNode orderParam(String orderId) {
      ObjectNode param = json.createObjectNode();
      if (NUMERIC.matcher(orderId).matches()) {
        return param.put("order_id", Long.parseLong(orderId));
      }
      return param.put("order_id", orderId);
    }

    /**
     * Pages forward through at most {@value #MAX_WINDOWS} three-month windows until one has data;
     * the most recent window's page 1 is the call already made in section 1.
     */
    private void collectSampleWindows(Call window0Page1) {
      boolean found = false;
      for (int w = 0; w < MAX_WINDOWS && !found; w++) {
        Window range = window(w);
        int windowRows = 0;
        for (int page = 1; page <= PAGES_PER_WINDOW; page++) {
          Call call;
          if (w == 0 && page == 1) {
            call = window0Page1;
          } else {
            call =
                invoke(
                    "shop.order.trade.detail",
                    "1.0",
                    tradeDetailParam(page, range, "INCOME_DETAIL"));
            if (!call.succeeded()) {
              line("- 窗口 " + describe(range) + " 第 " + page + " 页：" + call.status());
              break;
            }
          }
          int rows = collectWindow(call.result());
          windowRows += rows;
          if (rows < PAGE_SIZE) {
            break;
          }
        }
        line(
            "- 窗口 "
                + describe(range)
                + "："
                + windowRows
                + " 条"
                + (w == 0 ? "（第 1 页已在第 1 节调用）" : ""));
        found = windowRows > 0;
        if (found) {
          dataWindow = w;
        }
      }
      if (!found) {
        line("- 最近 " + MAX_WINDOWS + " 个 3 个月窗口均无结算明细数据（店铺可能尚无已结算订单）。");
      }
    }

    private String describe(Window range) {
      return TIME.format(range.start().toInstant()) + " ～ " + TIME.format(range.end().toInstant());
    }

    private int collectWindow(JsonNode result) {
      int count = 0;
      for (JsonNode row : rowsOf(result)) {
        TradeRow trade = toTradeRow(row);
        tradesByOrder.computeIfAbsent(trade.orderId(), key -> new ArrayList<>()).add(trade);
        comboCounts.merge(trade.tradeType() + " / " + trade.tradeSubType(), 1, Integer::sum);
        count++;
      }
      return count;
    }

    private List<JsonNode> rowsOf(JsonNode result) {
      JsonNode list = result.get("list");
      List<JsonNode> rows = new ArrayList<>();
      if (list != null && list.isArray()) {
        list.forEach(rows::add);
      }
      return rows;
    }

    private TradeRow toTradeRow(JsonNode row) {
      String orderId = text(row, "orderId") == null ? "-" : text(row, "orderId");
      String suffix = orderIdSuffix(orderId);
      if (!suffix.isEmpty()) {
        orderIdSuffixes.add(suffix);
      }
      String raw = text(row, "tradeTime");
      String beijing = "";
      if (raw != null && NUMERIC.matcher(raw).matches()) {
        beijing = TIME.format(Instant.ofEpochMilli(Long.parseLong(raw)));
      }
      return new TradeRow(
          orderId,
          text(row, "refundId"),
          text(row, "tradeType") == null ? "-" : text(row, "tradeType"),
          text(row, "tradeSubType") == null ? "-" : text(row, "tradeSubType"),
          text(row, "amount") == null ? "-" : text(row, "amount"),
          raw == null ? "-" : raw,
          beijing.isEmpty() ? "-" : beijing);
    }

    /**
     * Suffix token after the first underscore (e.g. "refund"); empty when the id is pure digits.
     */
    private static String orderIdSuffix(String orderId) {
      int underscore = orderId.indexOf('_');
      return underscore < 0 ? "" : orderId.substring(underscore + 1);
    }

    private void stats(Set<String> knownOrderIds) {
      line(
          "- 共收集 "
              + tradesByOrder.values().stream().mapToInt(List::size).sum()
              + " 条流水（窗口翻页记录见上）。");
      line("");
      line("### tradeType × tradeSubType 组合");
      line("");
      line("| tradeType | tradeSubType | 条数 |");
      line("|---|---|---|");
      if (comboCounts.isEmpty()) {
        line("| - | - | 0（无数据） |");
      }
      int combos = 0;
      for (Map.Entry<String, Integer> combo : comboCounts.entrySet()) {
        if (combos++ >= MAX_LISTED_COMBOS) {
          line("| … | … | 其余略 |");
          break;
        }
        String[] parts = combo.getKey().split(" / ", -1);
        line("| " + cell(parts[0]) + " | " + cell(parts[1]) + " | " + combo.getValue() + " |");
      }
      line("");
      line("### orderId 格式与匹配");
      line("");
      long pure = tradesByOrder.keySet().stream().filter(id -> id.matches("\\d+")).count();
      long suffixed = tradesByOrder.size() - pure;
      long exact = tradesByOrder.keySet().stream().filter(knownOrderIds::contains).count();
      line("- 不同 orderId 数：" + tradesByOrder.size() + "（纯数字 " + pure + "，带后缀 " + suffixed + "）");
      line("- 后缀种类：" + (orderIdSuffixes.isEmpty() ? "无" : String.join("、", orderIdSuffixes)));
      line("- 与 crm_order.weidian_order_id 完全相等：" + exact + " 个");
      if (suffixed > 0) {
        long prefix =
            tradesByOrder.keySet().stream()
                .filter(id -> !id.matches("\\d+"))
                .map(ProbeRun::stripSuffix)
                .filter(knownOrderIds::contains)
                .count();
        line("- 去掉后缀后（第一个 _ 之前）能对上：" + prefix + " 个");
      }
      settledMatchedOrders = (int) exact;
      line("");
      line("### 每个订单号所有笔 amount 合计");
      line("");
      line("| 订单号（末 4 位） | 笔数 | amount 合计 |");
      line("|---|---|---|");
      if (tradesByOrder.isEmpty()) {
        line("| - | - | 0（无数据） |");
      }
      int groups = 0;
      for (Map.Entry<String, List<TradeRow>> group : tradesByOrder.entrySet()) {
        if (groups++ >= MAX_LISTED_GROUPS) {
          line("| … | … | 其余略 |");
          break;
        }
        line(
            "| "
                + mask(group.getKey())
                + " | "
                + group.getValue().size()
                + " | "
                + sumOf(group.getValue())
                + " |");
      }
      line("");
    }

    private static String stripSuffix(String orderId) {
      return orderId.substring(0, orderId.indexOf('_'));
    }

    private static BigDecimal sumOf(List<TradeRow> rows) {
      return rows.stream()
          .map(TradeRow::amountValue)
          .filter(value -> value != null)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void preSettleRefund() {
      Window range = window(dataWindow);
      section(3, "结算前全额退款（queryType=PRE_SETTLE_REFUND，窗口 " + describe(range) + "）");
      Call call =
          invoke("shop.order.trade.detail", "1.0", tradeDetailParam(1, range, "PRE_SETTLE_REFUND"));
      if (!call.succeeded()) {
        line("- " + call.status());
        return;
      }
      List<JsonNode> rows = rowsOf(call.result());
      line("- 条数：" + rows.size() + "；result 根字段名：" + keysOf(call.result()));
      if (rows.isEmpty()) {
        line("- 无数据行，未检查字段形态。");
        return;
      }
      TradeRow trade = toTradeRow(rows.get(0));
      line("- 首条字段名：" + keysOf(rows.get(0)));
      line(
          "- 首条样本：tradeType="
              + trade.tradeType()
              + "，tradeSubType="
              + trade.tradeSubType()
              + "，amount="
              + trade.amount()
              + "，orderId="
              + mask(trade.orderId())
              + "，refundId="
              + (trade.refundId() == null ? "-" : mask(trade.refundId()))
              + "，tradeTime="
              + trade.tradeTimeRaw());
    }

    /** Evidence for the amount unit question: ledger income vs order detail vs database. */
    private void unitEvidence(Set<String> knownOrderIds) {
      section(4, "金额单位判断（结算明细流水 vs 订单详情 total/price vs 库内 pay_amount）");
      if (tradesByOrder.isEmpty()) {
        line("- 结算明细无数据，无法比对；单位只能待有结算样本后复测。");
        return;
      }
      int matched = 0;
      for (Map.Entry<String, List<TradeRow>> group : tradesByOrder.entrySet()) {
        if (!knownOrderIds.contains(group.getKey()) || matched >= MAX_UNIT_MATCHES) {
          continue;
        }
        matched++;
        List<String> amounts =
            group.getValue().stream()
                .map(row -> row.amount() + "（" + row.tradeType() + "/" + row.tradeSubType() + "）")
                .toList();
        line("- 订单 " + mask(group.getKey()) + " 各笔流水金额：" + amounts);
      }
      if (matched == 0) {
        line("- 没有能对上本库订单的订单号，单位判断只能按数量级推断（推断，未证实）。");
        return;
      }
      line("- 订单详情 total / price 与库内 pay_amount 见第 5 节各订单；单位结论在文档末尾「结论」一节。");
    }

    private void orderDetailLines(OrderRow order, JsonNode root) {
      line(
          "### 订单 "
              + mask(order.orderId())
              + "（库内 "
              + order.status()
              + " / "
              + order.rawStatus()
              + "，pay_time "
              + (order.payTime() == null ? "-" : TIME.format(order.payTime()))
              + "，pay_amount "
              + order.payAmount()
              + "）");
      line("");
      line("| 字段 | 值 |");
      line("|---|---|");
      line("| status | " + textOrEmpty(root, "status") + " |");
      line("| status_desc | " + textOrEmpty(root, "status_desc") + " |");
      line("| last_income | " + textOrEmpty(root, "last_income") + " |");
      line("| total | " + textOrEmpty(root, "total") + " |");
      line("| price | " + textOrEmpty(root, "price") + " |");
      line("| express_fee | " + textOrEmpty(root, "express_fee") + " |");
      line("| express_fee_num | " + textOrEmpty(root, "express_fee_num") + " |");
      line("");
      List<TradeRow> trades = tradesByOrder.get(order.orderId());
      if (trades != null) {
        BigDecimal sum = sumOf(trades);
        String lastIncome = text(root, "last_income");
        line(
            "- 结算明细合计 "
                + sum
                + " vs last_income "
                + textOrEmpty(root, "last_income")
                + "："
                + (lastIncome != null
                        && NUMERIC.matcher(lastIncome).matches()
                        && new BigDecimal(lastIncome).compareTo(sum) == 0
                    ? "相等"
                    : "不相等"));
        line("");
      }
      JsonNode items = root.get("items");
      if (items == null || !items.isArray() || items.isEmpty()) {
        line("- items 缺失或为空。");
        line("");
        return;
      }
      int index = 0;
      for (JsonNode item : items) {
        JsonNode refund = item.get("refund_info");
        line(
            "- 商品行["
                + index++
                + "]：status="
                + textOrEmpty(item, "status")
                + "，total_price="
                + textOrEmpty(item, "total_price")
                + "，refund_info.refund_fee="
                + scalar(refund, "refund_fee")
                + "，refund_info.refund_item_fee="
                + scalar(refund, "refund_item_fee")
                + "，refund_info.refund_status="
                + scalar(refund, "refund_status")
                + "，refund_info.refund_kind="
                + scalar(refund, "refund_kind"));
        JsonNode fees = item.get("fee_list");
        if (fees != null && fees.isArray()) {
          for (JsonNode fee : fees) {
            line("  - fee_list：" + inline(fee));
          }
        } else {
          line("  - fee_list：" + (fees == null ? "缺失" : "非数组"));
        }
      }
      line("");
    }

    private void parserUnreadFields(JsonNode root) {
      List<String[]> found = new ArrayList<>();
      walk(root, "", found);
      line("- 共 " + found.size() + " 个（数值与时间原样输出，其他字符串只标存在）：");
      line("");
      line("| 字段路径 | 样本值 |");
      line("|---|---|");
      int count = 0;
      for (String[] field : found) {
        if (count++ >= MAX_LISTED_FIELDS) {
          line("| … | 其余略 |");
          break;
        }
        line("| " + cell(field[0]) + " | " + cell(field[1]) + " |");
      }
      line("");
    }

    private static void walk(JsonNode node, String path, List<String[]> found) {
      if (found.size() >= MAX_LISTED_FIELDS) {
        return;
      }
      if (node.isObject()) {
        for (String name : node.propertyNames()) {
          if (PII.matcher(name.toLowerCase(Locale.ROOT)).find()) {
            continue;
          }
          walk(node.get(name), path.isEmpty() ? name : path + "." + name, found);
        }
      } else if (node.isArray()) {
        for (int i = 0; i < node.size(); i++) {
          walk(node.get(i), path + "[" + i + "]", found);
        }
      } else {
        String leaf = path.substring(path.lastIndexOf('.') + 1);
        if (PARSER_READ_FIELDS.contains(leaf) || !MONEY_TIME.matcher(leaf).matches()) {
          return;
        }
        found.add(new String[] {path, sampleValue(leaf, node)});
      }
    }

    private static String inline(JsonNode node) {
      if (node == null || !node.isObject()) {
        return String.valueOf(node);
      }
      List<String> pairs = new ArrayList<>();
      for (String name : node.propertyNames()) {
        JsonNode value = node.get(name);
        pairs.add(name + "=" + (value.isValueNode() ? value.asString() : keysOf(value)));
      }
      return String.join("，", pairs);
    }

    private Call invoke(String method, String version, ObjectNode param) {
      return invoke(method, version, param, null);
    }

    private Call invoke(String method, String version, ObjectNode param, JsonNode ctx) {
      if (!READ_ONLY_METHODS.contains(method)) {
        throw new IllegalStateException("接口不在只读白名单内: " + method);
      }
      if (tokenRejected) {
        return Call.failure("未执行：令牌已被微店拒绝");
      }
      if (requests >= MAX_REQUESTS) {
        throw new IllegalStateException("超过单次运行请求上限 " + MAX_REQUESTS);
      }
      if (lastCallEndedAt != null) {
        Duration elapsed = Duration.between(lastCallEndedAt, Instant.now());
        if (elapsed.compareTo(MIN_GAP) < 0) {
          // Fixed pacing required by the live-probe spec (at least 1 s between requests).
          // This is rate limiting, not waiting for an asynchronous result.
          pause(MIN_GAP.minus(elapsed));
        }
      }
      requests++;
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      ObjectNode publicNode = json.createObjectNode();
      publicNode.put("method", method);
      publicNode.put("access_token", token);
      publicNode.put("version", version);
      publicNode.put("format", "json");
      if (ctx != null) {
        publicNode.set("ctx", ctx);
      }
      form.add("public", json.writeValueAsString(publicNode));
      form.add("param", json.writeValueAsString(param));
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      JsonNode envelope;
      try {
        envelope =
            json.readTree(
                http.postForObject(API_BASE_URL, new HttpEntity<>(form, headers), String.class));
      } catch (HttpServerErrorException | ResourceAccessException e) {
        return Call.failure("网络错误（可重试：是）");
      } catch (RestClientException e) {
        return Call.failure("网络错误（可重试：否）");
      } finally {
        lastCallEndedAt = Instant.now();
      }
      int code = WeidianResponseParser.statusCode(envelope);
      if (code != 0) {
        if (TOKEN_REJECTED_CODES.contains(code)) {
          tokenRejected = true;
        }
        return Call.failure(
            "微店错误码 " + code + "：" + sanitize(WeidianResponseParser.statusReason(envelope)));
      }
      JsonNode result = envelope.get("result");
      return result == null || result.isNull()
          ? Call.failure("成功码但缺少 result")
          : Call.success(result);
    }

    void note(String note) {
      notes.add(note);
    }

    private void section(int number, String title) {
      lines.add("");
      lines.add("## " + number + ". " + title);
      lines.add("");
    }

    private void line(String text) {
      lines.add(text);
    }

    private String sanitize(String reason) {
      String safe = reason == null ? "" : reason;
      if (token != null && !token.isEmpty()) {
        safe = safe.replace(token, "***");
      }
      if (safe.length() > 200) {
        safe = safe.substring(0, 200) + "…";
      }
      return safe;
    }

    private String remaining() {
      if (expiresAt == null) {
        return "未知（无过期时间）";
      }
      Duration left = Duration.between(Instant.now(), expiresAt);
      if (left.isZero() || left.isNegative()) {
        return "已过期";
      }
      return left.toHours() + " 小时 " + left.toMinutesPart() + " 分钟";
    }

    private void write() throws IOException {
      StringBuilder md = new StringBuilder();
      md.append("# 75 微店结算字段实测结果\n\n");
      md.append(
          "手动只读实测，由 `WeidianSettlementLiveProbeTest`（标签 `weidian-live`，默认构建与 docker-it"
              + " 均不运行）生成。报告不含访问令牌、密钥、数据库密码、买家信息；订单号、退款单号、商品 ID 仅显示后 4 位；"
              + "金额、时间、状态、子类型原样输出。\n\n");
      md.append(
          "- 实测命令：`mvn -B test -Dgroups=weidian-live -Dsurefire.excludedGroups="
              + " -Dtest=WeidianSettlementLiveProbeTest`\n");
      md.append("- 运行时间（北京时间）：").append(TIME.format(startedAt)).append('\n');
      md.append("- 店铺 id（末 4 位）：").append(mask(Long.toString(shopId))).append('\n');
      md.append("- 令牌剩余有效时长：").append(remaining()).append('\n');
      md.append("- 微店接口请求次数：")
          .append(requests)
          .append("（上限 ")
          .append(MAX_REQUESTS)
          .append("；相邻请求间隔至少 1 秒）\n");
      md.append(
          settlementAvailable
              ? "- 结算明细可调用：是；能对上本库订单的订单号：" + settledMatchedOrders + " 个\n"
              : "- 结算明细可调用：否（见第 1 节）\n");
      for (String text : lines) {
        md.append(text).append('\n');
      }
      if (!notes.isEmpty()) {
        md.append("\n## 备注\n\n");
        for (String note : notes) {
          md.append("- ").append(note).append('\n');
        }
      }
      Files.createDirectories(REPORT.toAbsolutePath().getParent());
      Files.writeString(REPORT, md.toString(), StandardCharsets.UTF_8);
    }

    private static void pause(Duration duration) {
      try {
        Thread.sleep(duration);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted during request pacing");
      }
    }

    private static String text(JsonNode node, String key) {
      JsonNode value = node == null ? null : node.get(key);
      return value == null || value.isNull() ? null : value.asString();
    }

    private static String textOrEmpty(JsonNode node, String key) {
      String value = text(node, key);
      return value == null ? "-" : value;
    }

    private static String scalar(JsonNode parent, String key) {
      JsonNode value = parent == null ? null : parent.get(key);
      if (value == null) {
        return "缺失";
      }
      if (value.isNull()) {
        return "为空";
      }
      return sampleValue(key, value);
    }

    private static String keysOf(JsonNode node) {
      if (node == null || !node.isObject()) {
        return "非对象";
      }
      List<String> names = new ArrayList<>();
      for (String name : node.propertyNames()) {
        names.add(name);
      }
      return String.join(",", names);
    }
  }
}
