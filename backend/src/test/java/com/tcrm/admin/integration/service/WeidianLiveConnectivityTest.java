package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.shared.SecretCipher;
import java.io.IOException;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual, read-only connectivity check against one real Weidian shop.
 *
 * <p>Tagged {@code weidian-live}: excluded from the default build and from docker-it. Run by hand
 * with the environment listed in docs/04-开发任务/55-微店接口连通性-实测结果.md. The report never contains tokens,
 * secrets, buyer data, and identifiers are reduced to their last four characters.
 */
@Tag("weidian-live")
class WeidianLiveConnectivityTest {
  static final Set<String> READ_ONLY_METHODS =
      Set.of(
          "weidian.cate.get.list",
          "vdian.item.list.get",
          "vdian.item.get",
          "vdian.item.getItemDetail",
          "vdian.order.list.get",
          "vdian.order.get",
          "open.SellerQueryRefundList",
          "open.sellerQueryRefundDetail");
  static final Set<String> WRITE_METHODS =
      Set.of(
          "vdian.item.add",
          "vdian.item.delete",
          "vdian.item.saveItemDetail",
          "weidian.item.onSale");

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
  private static final String OAUTH_BASE_URL = "https://oauth.open.weidian.com";
  private static final int MAX_REQUESTS = 15;
  private static final Duration MIN_GAP = Duration.ofSeconds(1);
  private static final int PAGE_SIZE = 5;
  private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BEIJING);
  private static final Path REPORT = Path.of("..", "docs", "04-开发任务", "55-微店接口连通性-实测结果.md");
  private static final WeidianBusinessApiParser PARSER = new WeidianBusinessApiParser();

  @Test
  void shouldReadOnlyEndpointsOfConfiguredShop() throws Exception {
    List<String> missing =
        REQUIRED_ENV.stream().filter(name -> blank(System.getenv(name))).toList();
    assumeTrue(missing.isEmpty(), () -> "跳过：缺少环境变量 " + missing);
    assertThat(READ_ONLY_METHODS).hasSize(8).doesNotContainAnyElementsOf(WRITE_METHODS);
    assertThat(READ_ONLY_METHODS)
        .allSatisfy(
            method ->
                assertThat(method.toLowerCase(Locale.ROOT))
                    .doesNotContain("add", "delete", "save", "onsale", "update", "create"));

    long shopId = Long.parseLong(System.getenv("WEIDIAN_LIVE_SHOP_ID").strip());
    LiveRun run = new LiveRun(shopId);
    try {
      checkShop(run);
    } finally {
      run.write();
    }
  }

  private static void checkShop(LiveRun run) {
    AuthRow auth = loadAuth(run.shopId);
    assumeTrue(auth != null, () -> "跳过：crm_weidian_shop_auth 中无该店铺授权记录");
    run.expiresAt = auth.expiresAt();
    if (auth.tokenCipher() == null) {
      run.note("该店铺授权记录无访问令牌，未调用任何接口");
    }
    assumeTrue(auth.tokenCipher() != null, () -> "跳过：该店铺无访问令牌");
    if (auth.expiresAt() == null || !auth.expiresAt().isAfter(Instant.now())) {
      run.note("令牌已过期：未调用任何接口，未刷新令牌，未写数据库");
      assumeTrue(false, () -> "令牌已过期");
    }
    run.token = decrypt(auth.tokenCipher());
    run.gateway =
        new WeidianHttpGateway(
            new WeidianHttpConfiguration().weidianRestTemplate(),
            JsonMapper.builder().build(),
            API_BASE_URL,
            OAUTH_BASE_URL);
    checkEndpoints(run);
    assertThat(run.requests).isBetween(1, MAX_REQUESTS);
  }

  private static void checkEndpoints(LiveRun run) {
    String now = TIME.format(run.startedAt);
    String from = TIME.format(run.startedAt.minus(Duration.ofDays(30)));

    // 1. Category list (no shop-specific input required)
    Call cate =
        run.invoke("weidian.cate.get.list", "1.0", new WeidianBusinessApi.CategoryListRequest(1));
    if (cate.succeeded()) {
      JsonNode list = cate.result();
      JsonNode first = firstOf(list);
      run.add(
          "weidian.cate.get.list",
          "1.0",
          cate.status(),
          size(list),
          first == null
              ? "无分类，未检查字段"
              : fields(first, "cate_id", "cate_name", "cate_item_num", "sort_num"),
          parsed(() -> PARSER.categoryList(list)));
    } else {
      run.addStatus("weidian.cate.get.list", "1.0", cate.status());
    }

    // 2. Item list, first page only
    Call items =
        run.invoke(
            "vdian.item.list.get",
            "1.1",
            new WeidianBusinessApi.ItemListRequest(
                null, "1", null, 1, null, String.valueOf(PAGE_SIZE)));
    String itemId = null;
    if (items.succeeded()) {
      JsonNode rows = child(items.result(), "items");
      JsonNode first = firstOf(rows);
      itemId = text(first, "itemid");
      run.add(
          "vdian.item.list.get",
          "1.1",
          items.status(),
          size(rows) + sample(itemId),
          numbers(items.result(), "item_num", "total_num")
              + "；根字段名："
              + keysOf(items.result())
              + "；"
              + firstRow(
                  first,
                  "itemid",
                  "item_name",
                  "seller_id",
                  "price",
                  "stock",
                  "status",
                  "add_time",
                  "update_time"),
          parsed(() -> PARSER.itemList(items.result())));
    } else {
      run.addStatus("vdian.item.list.get", "1.1", items.status());
    }
    String noItem = items.succeeded() ? "跳过：无商品" : "跳过：商品列表调用失败";

    // 3. Item by ID
    if (itemId == null) {
      run.addStatus("vdian.item.get", "1.1", noItem);
    } else {
      Call item =
          run.invoke(
              "vdian.item.get", "1.1", new WeidianBusinessApi.ItemRequest(itemId, null, null));
      if (item.succeeded()) {
        run.add(
            "vdian.item.get",
            "1.1",
            item.status(),
            "1" + sample(itemId),
            fields(item.result(), "itemid", "item_name", "seller_id", "price", "stock", "status"),
            parsed(() -> PARSER.item(item.result())));
      } else {
        run.addStatus("vdian.item.get", "1.1", item.status());
      }
    }

    // 4. Item detail blocks
    Long numericItemId = parseId(itemId);
    if (itemId == null) {
      run.addStatus("vdian.item.getItemDetail", "1.0", noItem);
    } else if (numericItemId == null) {
      run.addStatus("vdian.item.getItemDetail", "1.0", "跳过：商品 ID 非数字");
    } else {
      Call detail =
          run.invoke(
              "vdian.item.getItemDetail",
              "1.0",
              new WeidianBusinessApi.ItemDetailRequest(numericItemId));
      if (detail.succeeded()) {
        // The parser is fed the same result node as production (WeidianBusinessApiClient).
        JsonNode root = detail.result();
        JsonNode blocks = child(child(root, "result"), "detail_content");
        JsonNode first = firstOf(blocks);
        run.add(
            "vdian.item.getItemDetail",
            "1.0",
            detail.status(),
            size(blocks) + sample(itemId),
            first == null
                ? "detail_content 为空，未检查字段"
                : fields(child(root, "result"), "detail_content")
                    + "；首块 "
                    + fields(first, "type", "url", "text", "pos"),
            parsed(() -> PARSER.itemDetail(root)));
      } else {
        run.addStatus("vdian.item.getItemDetail", "1.0", detail.status());
      }
    }

    // 5. Order list, last 30 days, first page only
    Call orders =
        run.invoke(
            "vdian.order.list.get",
            "1.6",
            new WeidianBusinessApi.OrderListRequest(
                1, false, null, "all", PAGE_SIZE, now, null, null, from, "0", null));
    String orderId = null;
    if (orders.succeeded()) {
      JsonNode root = orders.result();
      JsonNode list = child(root, "orders");
      JsonNode first = firstOf(list);
      orderId = text(first, "order_id");
      run.add(
          "vdian.order.list.get",
          "1.6",
          orders.status(),
          size(list) + sample(orderId),
          numbers(root, "order_num", "total_num", "has_more")
              + "；根字段名："
              + keysOf(root)
              + "；"
              + firstRow(first, "order_id", "total", "status", "time", "update_time"),
          parsed(() -> PARSER.orderList(root)));
    } else {
      run.addStatus("vdian.order.list.get", "1.6", orders.status());
    }
    String noOrder = orders.succeeded() ? "跳过：无订单" : "跳过：订单列表调用失败";

    // 6. Order detail
    Long numericOrderId = parseId(orderId);
    if (orderId == null) {
      run.addStatus("vdian.order.get", "2.0", noOrder);
    } else if (numericOrderId == null) {
      run.addStatus("vdian.order.get", "2.0", "跳过：订单号非数字");
    } else {
      Call order =
          run.invoke(
              "vdian.order.get", "2.0", new WeidianBusinessApi.OrderDetailRequest(numericOrderId));
      if (order.succeeded()) {
        JsonNode root = order.result();
        JsonNode lines = child(root, "items");
        JsonNode first = firstOf(lines);
        run.add(
            "vdian.order.get",
            "2.0",
            order.status(),
            size(lines) + sample(orderId),
            fields(
                    root,
                    "order_id",
                    "status",
                    "status_desc",
                    "total",
                    "price",
                    "add_time",
                    "pay_time",
                    "fans1_id",
                    "fans1_money_total",
                    "express_fee_num")
                + "；buyer_info.buyer_id="
                + state(child(root, "buyer_info"), "buyer_id")
                + "；首行 "
                + fields(
                    first,
                    "item_id",
                    "item_name",
                    "price",
                    "total_price",
                    "quantity",
                    "cur_shop_item_id",
                    "cur_shop_sku_id",
                    "sku_id",
                    "sub_order_id",
                    "sku_title")
                + "；fans1_id 当前解析代码未读取，仅记录存在性",
            parsed(() -> PARSER.orderDetail(root)));
      } else {
        run.addStatus("vdian.order.get", "2.0", order.status());
      }
    }

    // 7. Refund list, last 30 days by update time, first page only (0-based, as OrderSync)
    Call refunds =
        run.invoke(
            "open.SellerQueryRefundList",
            "2.0",
            new WeidianBusinessApi.RefundListRequest(
                null, null, null, from, now, 0, PAGE_SIZE, null, null, null));
    String refundNo = null;
    if (refunds.succeeded()) {
      JsonNode root = refunds.result();
      JsonNode list = child(root, "list");
      JsonNode first = firstOf(list);
      refundNo = text(first, "refund_no");
      run.add(
          "open.SellerQueryRefundList",
          "2.0",
          refunds.status(),
          size(list) + sample(refundNo),
          numbers(root, "totalNum")
              + "；根字段名："
              + keysOf(root)
              + "；"
              + firstRow(first, "refund_no", "orderID", "add_time")
              + (first == null
                  ? ""
                  : "，refund_info.refund_fee=" + state(child(first, "refund_info"), "refund_fee")),
          parsed(() -> PARSER.refundList(root)));
    } else {
      run.addStatus("open.SellerQueryRefundList", "2.0", refunds.status());
    }
    String noRefund = refunds.succeeded() ? "跳过：无退款单" : "跳过：退款列表调用失败";

    // 8. Refund detail
    Long numericRefundNo = parseId(refundNo);
    if (refundNo == null) {
      run.addStatus("open.sellerQueryRefundDetail", "2.0", noRefund);
    } else if (numericRefundNo == null) {
      run.addStatus("open.sellerQueryRefundDetail", "2.0", "跳过：退款单号非数字");
    } else {
      Call refund =
          run.invoke(
              "open.sellerQueryRefundDetail",
              "2.0",
              new WeidianBusinessApi.RefundDetailRequest(numericRefundNo));
      if (refund.succeeded()) {
        JsonNode root = refund.result();
        JsonNode basic = child(root, "refundBasicInfo");
        JsonNode lines = child(basic, "itemList");
        JsonNode first = firstOf(lines);
        run.add(
            "open.sellerQueryRefundDetail",
            "2.0",
            refund.status(),
            size(lines) + " 个明细行" + sample(refundNo),
            fields(
                    basic,
                    "addTime",
                    "finishTime",
                    "refundNo",
                    "orderId",
                    "refundFee",
                    "status",
                    "refund_type",
                    "refundType")
                + "；首行 itemList "
                + fields(first, "orderDescId", "curShopItemId", "itemId", "curShopSkuId", "skuId"),
            parsed(() -> PARSER.refundDetail(root)));
      } else {
        run.addStatus("open.SellerQueryRefundDetail", "2.0", refund.status());
      }
    }
  }

  private static AuthRow loadAuth(long shopId) {
    String url =
        "jdbc:postgresql://127.0.0.1:"
            + System.getenv("TCRM_DB_PORT").strip()
            + "/"
            + System.getenv("TCRM_DB_NAME").strip();
    try (Connection conn =
        DriverManager.getConnection(
            url, System.getenv("TCRM_DB_USERNAME"), System.getenv("TCRM_DB_PASSWORD"))) {
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

  private static String parsed(Runnable parse) {
    try {
      parse.run();
      return "成功";
    } catch (RuntimeException e) {
      return "异常（" + e.getClass().getSimpleName() + "）";
    }
  }

  private static JsonNode child(JsonNode node, String key) {
    return node == null ? null : node.get(key);
  }

  private static JsonNode firstOf(JsonNode array) {
    return array != null && array.isArray() && !array.isEmpty() ? array.get(0) : null;
  }

  private static String text(JsonNode node, String key) {
    JsonNode value = child(node, key);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static String size(JsonNode array) {
    if (array == null) {
      return "缺失";
    }
    return array.isArray() ? Integer.toString(array.size()) : "非数组";
  }

  private static String sample(String id) {
    return id == null ? "" : "（样本 " + mask(id) + "）";
  }

  private static String mask(String id) {
    return id == null || id.length() <= 4 ? "***" : "***" + id.substring(id.length() - 4);
  }

  private static Long parseId(String id) {
    if (id == null) {
      return null;
    }
    try {
      return Long.valueOf(id.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Presence of a field: 缺失 (absent), 为空 (null, blank, or empty container), or 存在. */
  private static String state(JsonNode parent, String key) {
    JsonNode value = child(parent, key);
    if (value == null) {
      return "缺失";
    }
    if (value.isNull()) {
      return "为空";
    }
    if (value.isString() && value.asString().isBlank()) {
      return "为空";
    }
    if ((value.isArray() || value.isObject()) && value.isEmpty()) {
      return "为空";
    }
    return "存在";
  }

  /** Root counters and flags: shows the value for numbers and booleans, presence otherwise. */
  private static String numbers(JsonNode parent, String... keys) {
    StringBuilder sb = new StringBuilder();
    for (String key : keys) {
      JsonNode value = child(parent, key);
      if (!sb.isEmpty()) {
        sb.append("，");
      }
      boolean scalar = value != null && (value.isNumber() || value.isBoolean());
      sb.append(key).append('=').append(scalar ? value.asString() : state(parent, key));
    }
    return sb.toString();
  }

  /** Names of the top-level fields of an object node (names only, never values). */
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

  private static String firstRow(JsonNode first, String... keys) {
    return first == null ? "无数据行，未检查行字段" : "首条 " + fields(first, keys);
  }

  private static String fields(JsonNode parent, String... keys) {
    StringBuilder sb = new StringBuilder();
    for (String key : keys) {
      if (!sb.isEmpty()) {
        sb.append("，");
      }
      sb.append(key).append('=').append(state(parent, key));
    }
    return sb.toString();
  }

  private record AuthRow(String tokenCipher, Instant expiresAt, String status) {}

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

  private record Row(
      String api, String version, String result, String count, String fields, String parse) {}

  /** One run: enforces the read-only whitelist, pacing, request cap, and writes the report. */
  private static final class LiveRun {
    private final Instant startedAt = Instant.now();
    private final long shopId;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Row> rows = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private Instant expiresAt;
    private String token;
    private WeidianHttpGateway gateway;
    private int requests;
    private Instant lastCallEndedAt;
    private boolean tokenRejected;

    LiveRun(long shopId) {
      this.shopId = shopId;
    }

    Call invoke(String method, String version, Object param) {
      if (!READ_ONLY_METHODS.contains(method) || WRITE_METHODS.contains(method)) {
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
          // Fixed pacing required by the live-check spec (at least 1 s between requests).
          // This is rate limiting, not waiting for an asynchronous result.
          pause(MIN_GAP.minus(elapsed));
        }
      }
      requests++;
      String paramJson = json.writeValueAsString(param);
      JsonNode envelope;
      try {
        envelope = gateway.apiPost(method, version, token, paramJson);
      } catch (WeidianTransportException e) {
        return Call.failure("网络错误（可重试：" + (e.retryable() ? "是" : "否") + "）");
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

    void add(String api, String version, String result, String count, String fields, String parse) {
      rows.add(new Row(api, version, result, count, fields, parse));
    }

    void addStatus(String api, String version, String status) {
      rows.add(new Row(api, version, status, "-", "-", "-"));
    }

    void note(String note) {
      notes.add(note);
    }

    void write() throws IOException {
      StringBuilder md = new StringBuilder();
      md.append("# 55 微店接口连通性实测结果\n\n");
      md.append(
          "手动只读实测，由 `WeidianLiveConnectivityTest`（标签 `weidian-live`，默认构建与 docker-it"
              + " 均不运行）生成。报告不含访问令牌、密钥、数据库密码、买家信息；订单号、商品 ID、退款单号"
              + "仅显示后 4 位。\n\n");
      md.append("- 运行时间（北京时间）：").append(TIME.format(startedAt)).append('\n');
      md.append("- 店铺 id（末 4 位）：").append(mask(Long.toString(shopId))).append('\n');
      md.append("- 令牌剩余有效时长：").append(remaining()).append('\n');
      md.append("- 微店接口请求次数：").append(requests).append("（上限 15；相邻请求间隔至少 1 秒）\n\n");
      md.append("| 接口 | 版本 | 结果 | 返回条数 | 关键字段（是否存在） | 解析 |\n");
      md.append("|---|---|---|---|---|---|\n");
      for (Row row : rows) {
        md.append("| ")
            .append(cell(row.api()))
            .append(" | ")
            .append(cell(row.version()))
            .append(" | ")
            .append(cell(row.result()))
            .append(" | ")
            .append(cell(row.count()))
            .append(" | ")
            .append(cell(row.fields()))
            .append(" | ")
            .append(cell(row.parse()))
            .append(" |\n");
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

    private String sanitize(String reason) {
      String safe = reason == null ? "" : reason;
      if (token != null && !token.isEmpty()) {
        safe = safe.replace(token, "***");
      }
      if (safe.length() > 80) {
        safe = safe.substring(0, 80) + "…";
      }
      return safe;
    }

    private static String cell(String value) {
      return value == null ? "-" : value.replace("|", "/").replace('\n', ' ');
    }

    private static void pause(Duration duration) {
      try {
        Thread.sleep(duration);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted during request pacing");
      }
    }
  }
}
