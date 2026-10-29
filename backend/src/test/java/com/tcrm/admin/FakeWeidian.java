package com.tcrm.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Local Weidian stand-in for the business-chain tests. One JDK HTTP server, no extra dependency.
 * Responses are full envelopes ({@code status} + {@code result}); an unstubbed method returns a
 * non-retryable error and fails {@link #assertProtocol()}.
 */
public final class FakeWeidian {
  static final String TRADE = "shop.order.trade.detail";
  private static final String ERROR =
      "{\"status\":{\"status_code\":10003,\"status_reason\":\"unstubbed\"}}";
  private static final Set<String> WRITE_METHODS =
      Set.of(
          "vdian.item.add",
          "vdian.item.delete",
          "vdian.item.saveItemDetail",
          "weidian.item.onSale");
  private static final Set<String> SECRET_QUERY =
      Set.of(
          "code",
          "access_token",
          "refresh_token",
          "appsecret",
          "app_secret",
          "secret",
          "sign",
          "signature");
  private static final long MAX_WINDOW_MILLIS = Duration.ofDays(89).toMillis();

  private final HttpServer server;
  private final ExecutorService executor;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Map<String, String> sticky = new ConcurrentHashMap<>();
  private final Map<String, ConcurrentLinkedDeque<String>> once = new ConcurrentHashMap<>();
  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private final Set<String> unexpected = ConcurrentHashMap.newKeySet();
  private volatile String openid = "0";

  private FakeWeidian(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  /** Binds {@code 127.0.0.1} on an ephemeral port and starts. */
  public static FakeWeidian start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      FakeWeidian fake = new FakeWeidian(server, executor);
      server.setExecutor(executor);
      server.createContext("/oauth2/authorize", fake::respond);
      server.createContext("/oauth2/access_token", fake::respond);
      server.createContext("/oauth2/refresh_token", fake::respond);
      server.createContext("/token", fake::respond);
      server.createContext("/api", fake::respond);
      server.start();
      return fake;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to start the fake Weidian server", e);
    }
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  /** Drops recorded calls and stubs. The server keeps listening. */
  public void reset() {
    sticky.clear();
    once.clear();
    calls.clear();
    unexpected.clear();
    openid = "0";
  }

  /** Openid returned by the token endpoint. It must equal the shop's Weidian id. */
  public void openid(String value) {
    openid = value;
  }

  /** Repeating envelope for a business method. */
  public void stub(String method, String envelope) {
    sticky.put(method, envelope);
  }

  /** Repeating envelope for one {@code shop.order.trade.detail} query type. */
  public void stubTrade(String queryType, String envelope) {
    sticky.put(TRADE + "|" + queryType, envelope);
  }

  /** One-shot envelope consumed before the repeating trade stub. */
  public void onceTrade(String queryType, String envelope) {
    once.computeIfAbsent(TRADE + "|" + queryType, key -> new ConcurrentLinkedDeque<>())
        .add(envelope);
  }

  public List<Call> calls() {
    return List.copyOf(calls);
  }

  public long count(String method) {
    return calls.stream().filter(call -> method.equals(call.method())).count();
  }

  /**
   * Fails when an unstubbed method was called, a write method was called, {@code ctx} was sent, a
   * page was larger than 100, or a settlement window was longer than 89 days.
   */
  public void assertProtocol() {
    assertThat(unexpected).as("unstubbed Weidian methods").isEmpty();
    for (Call call : calls) {
      if (!"/api".equals(call.path()) || call.param() == null) {
        continue;
      }
      assertThat(call.method()).isNotIn(WRITE_METHODS);
      assertThat(call.param().toString()).doesNotContain("\"ctx\"");
      assertPage(call);
      assertWindow(call);
    }
  }

  private void assertPage(Call call) {
    for (String key : List.of("pageSize", "page_size")) {
      JsonNode node = call.param().get(key);
      if (node != null && !node.isNull()) {
        long size = node.isNumber() ? node.longValue() : Long.parseLong(node.asText());
        assertThat(size).as(call.method() + " " + key).isLessThanOrEqualTo(100);
      }
    }
  }

  private void assertWindow(Call call) {
    JsonNode start = call.param().get("startTime");
    JsonNode end = call.param().get("endTime");
    if (start == null || end == null || start.isNull() || end.isNull()) {
      return;
    }
    long span = end.longValue() - start.longValue();
    assertThat(span)
        .as(call.method() + " settlement window")
        .isLessThanOrEqualTo(MAX_WINDOW_MILLIS);
  }

  private void respond(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String body;
    try {
      body = bodyFor(exchange, path);
    } catch (RuntimeException e) {
      unexpected.add(path + ":handler");
      body = ERROR;
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private String bodyFor(HttpExchange exchange, String path) throws IOException {
    if ("/api".equals(path)) {
      return apiBody(exchange);
    }
    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
    calls.add(new Call(path, path, safeQuery(query)));
    if ("/oauth2/access_token".equals(path) || "/oauth2/refresh_token".equals(path)) {
      return token(openid);
    }
    if ("/token".equals(path)) {
      return token(openid);
    }
    return "{\"status\":{\"status_code\":0},\"result\":{}}";
  }

  private String apiBody(HttpExchange exchange) throws IOException {
    String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> fields = query(form);
    JsonNode pub = json.readTree(fields.getOrDefault("public", "{}"));
    String method = pub.path("method").asText();
    JsonNode param = json.readTree(fields.getOrDefault("param", "{}"));
    calls.add(new Call("/api", method, param.deepCopy()));
    String key = TRADE.equals(method) ? TRADE + "|" + param.path("queryType").asText() : method;
    String body = poll(key);
    if (body == null) {
      body = sticky.get(key);
    }
    if (body == null) {
      body = sticky.get(method);
    }
    if (body == null) {
      unexpected.add(method);
      return ERROR;
    }
    if ("vdian.order.get".equals(method)) {
      String expected = json.readTree(body).at("/result/order_id").asText();
      String requested = param.path("order_id").asText();
      if (!expected.isEmpty() && !expected.equals(requested)) {
        unexpected.add("vdian.order.get:" + requested);
        return ERROR;
      }
    }
    return body;
  }

  private String poll(String key) {
    ConcurrentLinkedDeque<String> queue = once.get(key);
    return queue == null ? null : queue.poll();
  }

  private JsonNode safeQuery(Map<String, String> query) {
    ObjectNode node = json.createObjectNode();
    query.forEach(
        (key, value) -> {
          if (!SECRET_QUERY.contains(key)) {
            node.put(key, value);
          }
        });
    return node;
  }

  private static String token(String shopOpenid) {
    return "{\"status\":{\"status_code\":0},\"result\":{"
        + "\"access_token\":\"t79-access\",\"expire_in\":14400,"
        + "\"refresh_token\":\"t79-refresh\",\"refreshExpireTime\":2592000000,"
        + "\"openid\":\""
        + shopOpenid
        + "\"}}";
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> result = new ConcurrentHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return result;
    }
    String text = raw.startsWith("?") ? raw.substring(1) : raw;
    for (String pair : text.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      String[] parts = pair.split("=", 2);
      result.put(decode(parts[0]), parts.length == 1 ? "" : decode(parts[1]));
    }
    return result;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  /** One recorded call. Business params only; tokens and signatures are not stored. */
  public record Call(String path, String method, JsonNode param) {}
}
