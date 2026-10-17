package com.tcrm.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.integration.service.WeidianRateLimiter;
import com.tcrm.admin.integration.service.WeidianToken;
import com.tcrm.admin.integration.service.WeidianTokenCenter;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** End-to-end Weidian authorization and token lifecycle checks against PostgreSQL and Redis. */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WeidianIntegrationIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN_HEADER = "X-Admin-Token";
  private static final AtomicReference<String> TOKEN_RESPONSE =
      new AtomicReference<>(serviceToken("1001", "service-access-one", "service-refresh-one"));
  private static final AtomicReference<String> SELF_TOKEN_RESPONSE =
      new AtomicReference<>(selfToken("self-access-one"));
  private static final AtomicReference<String> REFRESH_RESPONSE =
      new AtomicReference<>(serviceToken("1001", "service-access-two", "service-refresh-two"));
  private static final Map<String, AtomicInteger> OAUTH_CALLS = new ConcurrentHashMap<>();
  private static final AtomicReference<CountDownLatch> REFRESH_ENTERED = new AtomicReference<>();
  private static final AtomicReference<CountDownLatch> REFRESH_RELEASE = new AtomicReference<>();
  private static final HttpServer WEIDIAN = startServer();
  private static final String BASE_URL = "http://127.0.0.1:" + WEIDIAN.getAddress().getPort();

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
    registry.add("crm.weidian.qps", () -> "2");
    WeidianTestProperties.register(registry, BASE_URL + "/api", BASE_URL);
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final WeidianTokenCenter tokens;
  private final SecretCipher cipher;
  private final WeidianRateLimiter limiter;

  private String adminToken;

  @Autowired
  WeidianIntegrationIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      StringRedisTemplate redis,
      WeidianTokenCenter tokens,
      SecretCipher cipher,
      WeidianRateLimiter limiter) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.tokens = tokens;
    this.cipher = cipher;
    this.limiter = limiter;
  }

  @BeforeEach
  void resetDatabaseAndStub() throws Exception {
    jdbc.update("DELETE FROM crm_weidian_shop_auth");
    jdbc.update("DELETE FROM crm_weidian_app");
    jdbc.update("DELETE FROM crm_shop WHERE tenant_id=1");
    var stateKeys = redis.keys("weidian:oauth:state:*");
    if (stateKeys != null && !stateKeys.isEmpty()) {
      redis.delete(stateKeys);
    }
    OAUTH_CALLS.clear();
    REFRESH_ENTERED.set(null);
    REFRESH_RELEASE.set(null);
    TOKEN_RESPONSE.set(serviceToken("1001", "service-access-one", "service-refresh-one"));
    SELF_TOKEN_RESPONSE.set(selfToken("self-access-one"));
    REFRESH_RESPONSE.set(serviceToken("1001", "service-access-two", "service-refresh-two"));
    adminToken = CompanySessions.companyAdmin(mvc, json, PASSWORD);
  }

  @AfterAll
  static void stopWeidianStub() {
    WEIDIAN.stop(0);
  }

  @Test
  void shouldConsumeServiceStateOnceAndPersistOnlyEncryptedMatchingToken() throws Exception {
    long shopId = createShop(adminToken, "1001", "Shop 1001");
    saveServiceApp(adminToken);
    String state = startAuthorization(adminToken, shopId);

    assertThat(state).matches("[A-Za-z0-9]{32}");
    MvcResult success = callback(state, "one-time-code");
    assertThat(success.getResponse().getStatus()).isEqualTo(302);
    assertThat(success.getResponse().getHeader("Location"))
        .isEqualTo("/product/shops?weidianAuth=success");
    assertCallbackResponseIsRedacted(success);
    Map<String, Object> saved = authRow(shopId);
    assertThat(saved.get("status")).isEqualTo("AUTHORIZED");
    assertThat(saved.get("access_token_cipher").toString()).startsWith("v1:");
    assertThat(cipher.decrypt(saved.get("access_token_cipher").toString()))
        .isEqualTo("service-access-one");
    assertThat(saved.get("refresh_token_cipher").toString()).startsWith("v1:");
    assertThat(saved.get("refresh_token_cipher").toString()).doesNotContain("service-refresh-one");

    MvcResult replay = callback(state, "one-time-code");
    assertThat(replay.getResponse().getStatus()).isEqualTo(302);
    assertThat(replay.getResponse().getHeader("Location"))
        .isEqualTo("/product/shops?weidianAuth=failed&reason=STATE_INVALID");
    assertCallbackResponseIsRedacted(replay);
    assertThat(OAUTH_CALLS.get("/oauth2/access_token").get()).isEqualTo(1);
  }

  @Test
  void shouldRejectMalformedMissingExpiredDeniedAndMismatchedServiceStates() throws Exception {
    long shopId = createShop(adminToken, "1001", "Shop 1001");
    saveServiceApp(adminToken);

    assertReason(callback("invalid-state", "code"), "STATE_INVALID");
    assertReason(callback("A".repeat(32), "code"), "STATE_INVALID");

    String deniedState = startAuthorization(adminToken, shopId);
    assertReason(callback(deniedState, null), "ACCESS_DENIED");

    String expiredState = startAuthorization(adminToken, shopId);
    String stateKey = "weidian:oauth:state:" + expiredState;
    assertThat(redis.expire(stateKey, Duration.ofMillis(1))).isTrue();
    // Redis TTL must elapse before the state is treated as expired.
    Thread.sleep(20);
    assertReason(callback(expiredState, "code"), "STATE_INVALID");

    TOKEN_RESPONSE.set(serviceToken("different-shop", "must-not-save", "must-not-save-refresh"));
    String mismatchState = startAuthorization(adminToken, shopId);
    MvcResult mismatch = callback(mismatchState, "mismatch-code");
    assertReason(mismatch, "SHOP_MISMATCH");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_weidian_shop_auth WHERE shop_id=?",
                Integer.class,
                shopId))
        .isZero();
    assertCallbackResponseIsRedacted(mismatch);
  }

  @Test
  void shouldConfigureCheckAndRejectInvalidSelfUseCredentialsWithoutAuthorizing() throws Exception {
    long shopId = createShop(adminToken, "1001", "Shop 1001");
    putSelfUse(shopId, "self-app", "self-secret").andExpect(status().isOk());
    Map<String, Object> saved = authRow(shopId);
    assertThat(saved.get("status")).isEqualTo("AUTHORIZED");
    assertThat(saved.get("auth_mode")).isEqualTo("SELF_USE");
    assertThat(saved.get("self_app_secret_cipher").toString()).startsWith("v1:");
    assertThat(saved.get("self_app_secret_cipher").toString()).doesNotContain("self-secret");
    assertThat(cipher.decrypt(saved.get("self_app_secret_cipher").toString()))
        .isEqualTo("self-secret");
    assertThat(saved.get("refresh_token_cipher")).isNull();
    assertThat(saved.get("last_check_ok")).isEqualTo(true);

    MvcResult checked =
        mvc.perform(
                post("/admin-api/integration/weidian/shops/{shopId}/checks", shopId)
                    .header(TOKEN_HEADER, adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("AUTHORIZED"))
            .andReturn();
    assertThat(checked.getResponse().getContentAsString()).doesNotContain("self-secret");

    long invalidShopId = createShop(adminToken, "1002", "Shop 1002");
    SELF_TOKEN_RESPONSE.set("{\"status\":{\"status_code\":10022,\"status_reason\":\"invalid\"}}");
    putSelfUse(invalidShopId, "bad-app", "bad-secret")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("WEIDIAN_CREDENTIAL_INVALID"));
    Map<String, Object> failed = authRow(invalidShopId);
    assertThat(failed.get("status")).isEqualTo("FAILED");
    assertThat(failed.get("access_token_cipher")).isNull();
    assertThat(failed.get("self_app_secret_cipher").toString()).startsWith("v1:");
  }

  @Test
  void shouldHideOtherCompanyShopOnEveryShopEndpointAndBindCallbackToSavedCompany()
      throws Exception {
    CompanySessions.OpenedCompany companyB =
        CompanySessions.openCompany(
            mvc,
            json,
            PASSWORD,
            "weidian-" + UUID.randomUUID().toString().substring(0, 8),
            "weidian-admin-" + UUID.randomUUID().toString().substring(0, 8));
    long shopA = createShop(adminToken, "1001", "Company A shop");
    long shopB = createShop(companyB.token(), "2002", "Company B shop");
    saveServiceApp(adminToken);
    saveServiceApp(companyB.token());

    mvc.perform(
            put("/admin-api/integration/weidian/shops/{shopId}/self-use", shopB)
                .header(TOKEN_HEADER, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"appKey\":\"a\",\"appSecret\":\"secret\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/admin-api/integration/weidian/shops/{shopId}/authorizations", shopB)
                .header(TOKEN_HEADER, adminToken))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/admin-api/integration/weidian/shops/{shopId}/checks", shopB)
                .header(TOKEN_HEADER, adminToken))
        .andExpect(status().isNotFound());

    String stateB = startAuthorization(companyB.token(), shopB);
    TOKEN_RESPONSE.set(serviceToken("2002", "company-b-access", "company-b-refresh"));
    MvcResult result = callback(stateB, "company-b-code");
    assertThat(result.getResponse().getHeader("Location"))
        .isEqualTo("/product/shops?weidianAuth=success");
    assertThat(
            jdbc.queryForObject(
                "SELECT tenant_id FROM crm_weidian_shop_auth WHERE shop_id=?", Long.class, shopB))
        .isEqualTo(companyB.tenantId());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_weidian_shop_auth WHERE shop_id=?", Integer.class, shopA))
        .isZero();
  }

  @Test
  void shouldRenewExpiringTokenAndRefreshPassivelyOnlyOnce() throws Exception {
    long shopId = createShop(adminToken, "1001", "Shop 1001");
    saveServiceApp(adminToken);
    String state = startAuthorization(adminToken, shopId);
    callback(state, "code");
    jdbc.update(
        "UPDATE crm_weidian_shop_auth SET access_expires_at=? WHERE shop_id=?",
        Timestamp.from(Instant.now().plus(Duration.ofHours(2))),
        shopId);

    WeidianToken proactivelyRenewed = TenantScope.callCompany(1, () -> tokens.accessToken(shopId));
    assertThat(proactivelyRenewed.accessToken()).isEqualTo("service-access-two");
    assertThat(OAUTH_CALLS.get("/oauth2/refresh_token").get()).isEqualTo(1);

    long passiveShopId = createShop(adminToken, "1002", "Shop 1002");
    TOKEN_RESPONSE.set(serviceToken("1002", "passive-access-one", "passive-refresh-one"));
    String passiveState = startAuthorization(adminToken, passiveShopId);
    callback(passiveState, "code");
    REFRESH_RESPONSE.set(serviceToken("1002", "passive-access-two", "passive-refresh-two"));
    WeidianToken first =
        TenantScope.callCompany(
            1, () -> tokens.refreshAfterInvalid(passiveShopId, "passive-access-one"));
    WeidianToken second =
        TenantScope.callCompany(
            1, () -> tokens.refreshAfterInvalid(passiveShopId, "passive-access-one"));
    assertThat(first.accessToken()).isEqualTo("passive-access-two");
    assertThat(second.accessToken()).isEqualTo("passive-access-two");
    assertThat(OAUTH_CALLS.get("/oauth2/refresh_token").get()).isEqualTo(2);
  }

  @Test
  void shouldLeaveStatusAuthorizedDuringPassiveRefreshCooldownAndAllowRefreshAfterExpiry()
      throws Exception {
    long shopId = createShop(adminToken, "1001", "Shop 1001");
    putSelfUse(shopId, "self-app", "self-secret").andExpect(status().isOk());
    String key = "weidian:token:passive:" + shopId;
    redis.opsForValue().set(key, "1", Duration.ofMinutes(10));
    int callsBefore = oauthCalls("/token");

    assertThatThrownBy(
            () ->
                TenantScope.runCompany(
                    1, () -> tokens.refreshAfterInvalid(shopId, "self-access-one")))
        .isInstanceOf(com.tcrm.admin.integration.service.WeidianApiException.class)
        .hasMessage("Weidian access token refresh is in cooldown");
    assertThat(authRow(shopId).get("status")).isEqualTo("AUTHORIZED");

    assertThat(redis.expire(key, Duration.ofMillis(1))).isTrue();
    // Redis TTL must elapse before the refresh cooldown key disappears.
    Thread.sleep(20);
    SELF_TOKEN_RESPONSE.set(selfToken("self-access-after-cooldown"));
    WeidianToken refreshed =
        TenantScope.callCompany(1, () -> tokens.refreshAfterInvalid(shopId, "self-access-one"));
    assertThat(refreshed.accessToken()).isEqualTo("self-access-after-cooldown");
    assertThat(authRow(shopId).get("status")).isEqualTo("AUTHORIZED");
    assertThat(oauthCalls("/token")).isEqualTo(callsBefore + 1);
  }

  @Test
  void shouldSerializeConcurrentRenewalAndPassiveRefreshWithoutOverwritingLatestToken()
      throws Exception {
    long shopId = createShop(adminToken, "1001", "Shop 1001");
    saveServiceApp(adminToken);
    callback(startAuthorization(adminToken, shopId), "code");
    jdbc.update(
        "UPDATE crm_weidian_shop_auth SET access_expires_at=? WHERE shop_id=?",
        Timestamp.from(Instant.now().plus(Duration.ofHours(2))),
        shopId);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    REFRESH_ENTERED.set(entered);
    REFRESH_RELEASE.set(release);
    REFRESH_RESPONSE.set(serviceToken("1001", "latest-access-token", "latest-refresh-token"));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> proactive =
          pool.submit(() -> TenantScope.runCompany(1, () -> tokens.renewIfDue(shopId)));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      CountDownLatch passiveStarted = new CountDownLatch(1);
      Future<WeidianToken> passive =
          pool.submit(
              () -> {
                passiveStarted.countDown();
                return TenantScope.callCompany(
                    1, () -> tokens.refreshAfterInvalid(shopId, "service-access-one"));
              });
      assertThat(passiveStarted.await(5, TimeUnit.SECONDS)).isTrue();
      // Give the passive refresher time to enter the cooldown wait before releasing the lock.
      Thread.sleep(100);
      release.countDown();
      proactive.get(10, TimeUnit.SECONDS);
      assertThat(passive.get(10, TimeUnit.SECONDS).accessToken()).isEqualTo("latest-access-token");
    } finally {
      release.countDown();
      pool.shutdownNow();
      REFRESH_ENTERED.set(null);
      REFRESH_RELEASE.set(null);
    }
    assertThat(OAUTH_CALLS.get("/oauth2/refresh_token").get()).isEqualTo(1);
    Map<String, Object> saved = authRow(shopId);
    assertThat(cipher.decrypt(saved.get("access_token_cipher").toString()))
        .isEqualTo("latest-access-token");
    assertThat(cipher.decrypt(saved.get("refresh_token_cipher").toString()))
        .isEqualTo("latest-refresh-token");
  }

  @Test
  void shouldLimitConcurrentRequestsToConfiguredAppQps() throws Exception {
    int workers = 5;
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<Long> acquiredAt = new CopyOnWriteArrayList<>();
    String appKey = "same-app-key-" + UUID.randomUUID();
    try {
      List<Future<?>> calls = new ArrayList<>();
      for (int i = 0; i < workers; i++) {
        calls.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  limiter.acquire(appKey);
                  acquiredAt.add(System.nanoTime());
                  return null;
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      long startedAt = System.nanoTime();
      start.countDown();
      for (Future<?> call : calls) {
        call.get(12, TimeUnit.SECONDS);
      }
      assertThat(System.nanoTime() - startedAt)
          .as("five concurrent acquisitions with a two-per-second limit")
          .isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
    assertThat(acquiredAt).hasSize(workers);
  }

  @Test
  void shouldMarkServiceAuthorizationExpiredWhenRefreshTokenIsRejected() throws Exception {
    long shopId = createShop(adminToken, "1001", "Shop 1001");
    saveServiceApp(adminToken);
    callback(startAuthorization(adminToken, shopId), "code");
    jdbc.update(
        "UPDATE crm_weidian_shop_auth SET access_expires_at=? WHERE shop_id=?",
        Timestamp.from(Instant.now().plus(Duration.ofHours(2))),
        shopId);
    REFRESH_RESPONSE.set("{\"status\":{\"status_code\":10023,\"status_reason\":\"expired\"}}");

    assertThatThrownBy(() -> TenantScope.callCompany(1, () -> tokens.accessToken(shopId)))
        .isInstanceOf(com.tcrm.admin.integration.service.WeidianApiException.class)
        .hasMessage("Weidian API error 10023");
    assertThat(authRow(shopId).get("status")).isEqualTo("EXPIRED");
  }

  private MvcResult createShopResult(String token, String weidianId, String name) throws Exception {
    return mvc.perform(
            post("/admin-api/product/shops")
                .header(TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("shopName", name, "weidianShopId", weidianId))))
        .andExpect(status().isOk())
        .andReturn();
  }

  private long createShop(String token, String weidianId, String name) throws Exception {
    return Long.parseLong(
        json.readTree(createShopResult(token, weidianId, name).getResponse().getContentAsString())
            .path("data")
            .path("id")
            .asText());
  }

  private void saveServiceApp(String token) throws Exception {
    mvc.perform(
            put("/admin-api/integration/weidian/app")
                .header(TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"appKey\":\"service-app\",\"appSecret\":\"service-secret\"}"))
        .andExpect(status().isOk());
  }

  private String startAuthorization(String token, long shopId) throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/integration/weidian/shops/{shopId}/authorizations", shopId)
                    .header(TOKEN_HEADER, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String url = json.readTree(body).path("data").path("authorizeUrl").asText();
    assertThat(url).contains("response_type=code");
    return query(url).get("state");
  }

  private MvcResult callback(String state, String code) throws Exception {
    var request = get("/admin-api/integration/weidian/oauth/callback").param("state", state);
    if (code != null) {
      request.param("code", code);
    }
    return mvc.perform(request).andReturn();
  }

  private org.springframework.test.web.servlet.ResultActions putSelfUse(
      long shopId, String appKey, String secret) throws Exception {
    return mvc.perform(
        put("/admin-api/integration/weidian/shops/{shopId}/self-use", shopId)
            .header(TOKEN_HEADER, adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("appKey", appKey, "appSecret", secret))));
  }

  private Map<String, Object> authRow(long shopId) {
    return jdbc.queryForMap("SELECT * FROM crm_weidian_shop_auth WHERE shop_id=?", shopId);
  }

  private void assertReason(MvcResult result, String reason) {
    assertThat(result.getResponse().getStatus()).isEqualTo(302);
    assertThat(result.getResponse().getHeader("Location"))
        .isEqualTo("/product/shops?weidianAuth=failed&reason=" + reason);
  }

  private void assertCallbackResponseIsRedacted(MvcResult result) throws Exception {
    String response = result.getResponse().getContentAsString();
    assertThat(response).doesNotContain("service-access", "service-refresh", "service-secret");
    assertThat(result.getResponse().getHeader("Location"))
        .doesNotContain("service-access", "service-refresh", "service-secret");
  }

  private int oauthCalls(String path) {
    AtomicInteger calls = OAUTH_CALLS.get(path);
    return calls == null ? 0 : calls.get();
  }

  private static HttpServer startServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/oauth2/authorize", WeidianIntegrationIT::respond);
      server.createContext("/oauth2/access_token", WeidianIntegrationIT::respond);
      server.createContext("/oauth2/refresh_token", WeidianIntegrationIT::respond);
      server.createContext("/token", WeidianIntegrationIT::respond);
      server.createContext("/api", WeidianIntegrationIT::respond);
      server.start();
      return server;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void respond(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String body;
    if ("/api".equals(path)) {
      body = "{\"status\":{\"status_code\":0},\"result\":{\"items\":[{\"seller_id\":\"1001\"}]}}";
    } else if ("/oauth2/refresh_token".equals(path)) {
      OAUTH_CALLS.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
      CountDownLatch entered = REFRESH_ENTERED.get();
      CountDownLatch release = REFRESH_RELEASE.get();
      if (entered != null && release != null) {
        entered.countDown();
        try {
          if (!release.await(8, TimeUnit.SECONDS)) {
            body = "{\"status\":{\"status_code\":10023}}";
            writeResponse(exchange, body);
            return;
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          body = "{\"status\":{\"status_code\":10023}}";
          writeResponse(exchange, body);
          return;
        }
      }
      body = REFRESH_RESPONSE.get();
    } else if ("/token".equals(path)) {
      OAUTH_CALLS.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
      body = SELF_TOKEN_RESPONSE.get();
    } else if ("/oauth2/access_token".equals(path)) {
      OAUTH_CALLS.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
      body = TOKEN_RESPONSE.get();
    } else {
      body = "{\"status\":{\"status_code\":0},\"result\":{}}";
    }
    writeResponse(exchange, body);
  }

  private static void writeResponse(HttpExchange exchange, String body) throws IOException {
    byte[] response = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }

  private static Map<String, String> query(String url) {
    int question = url.indexOf('?');
    if (question < 0 || question == url.length() - 1) {
      return Map.of();
    }
    Map<String, String> result = new ConcurrentHashMap<>();
    for (String pair : url.substring(question + 1).split("&")) {
      String[] parts = pair.split("=", 2);
      result.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  private static String serviceToken(String openid, String accessToken, String refreshToken) {
    return "{\"status\":{\"status_code\":0},\"result\":{"
        + "\"access_token\":\""
        + accessToken
        + "\",\"expire_in\":14400,"
        + "\"refresh_token\":\""
        + refreshToken
        + "\",\"refreshExpireTime\":2592000000,"
        + "\"openid\":\""
        + openid
        + "\"}}";
  }

  private static String selfToken(String accessToken) {
    return "{\"status\":{\"status_code\":0},\"result\":{"
        + "\"access_token\":\""
        + accessToken
        + "\",\"expire_in\":14400}}";
  }
}
