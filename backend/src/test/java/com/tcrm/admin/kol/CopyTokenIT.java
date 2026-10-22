package com.tcrm.admin.kol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.kol.api.CopyTokenApi;
import com.tcrm.admin.kol.api.CopyTokenGrantDTO;
import com.tcrm.admin.product.service.ShopService;
import com.tcrm.admin.shared.TenantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 17a against real PostgreSQL and Redis: create / list / reveal / revoke, OWN scope, auth API
 * and concurrent consume.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CopyTokenIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String ADMIN_TOKEN = "X-Admin-Token";

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
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("spring.data.redis.password", () -> "");
    r.add("crm.bootstrap.username", () -> "bootstrap");
    r.add("crm.bootstrap.password", () -> PASSWORD);
    r.add("crm.link.base-url", () -> "https://t.example.com");
    r.add("crm.kol-portal.base-url", () -> "https://kol.example.com");
    r.add("crm.link.click-consumer.enabled", () -> "false");
    WeidianTestProperties.register(r);
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final CopyTokenApi copyTokens;
  private final ShopService shopService;
  private final TransactionTemplate transactions;

  private String admin;

  @Autowired
  CopyTokenIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      StringRedisTemplate redis,
      CopyTokenApi copyTokens,
      ShopService shopService,
      PlatformTransactionManager transactionManager) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.copyTokens = copyTokens;
    this.shopService = shopService;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @BeforeEach
  void prepareIndependentData() throws Exception {
    jdbc.update("DELETE FROM crm_copy_job_item");
    jdbc.update("DELETE FROM crm_copy_job");
    jdbc.update("DELETE FROM crm_kol_copy_token");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM crm_weidian_shop_auth");
    jdbc.update("DELETE FROM crm_shop");
    for (String table :
        List.of("sys_user_permission", "sys_user_tenant", "sys_user", "sys_oper_log")) {
      jdbc.update(
          "DELETE FROM "
              + table
              + (table.equals("sys_user") ? " WHERE username <> 'bootstrap'" : ""));
    }
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
    admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_code,channel,shop_name,weidian_shop_id,status,is_source,copy_pool_status,max_items,current_items,create_time,update_time) "
            + "VALUES(900001,1,'SHOP-900001','WEIDIAN','t37_Copy Shop','390001',1,FALSE,'JOINED',20000,0,now(),now())");
    authorizeShop(900001L);
  }

  @Test
  void shouldCreateListRevealRevoke_authenticateAndConsumeConcurrently() throws Exception {
    long kolId = createKol("Alice", "alice17a@example.com", null);

    MvcResult created =
        mvc.perform(
                post("/admin-api/kol/copy-tokens")
                    .header(ADMIN_TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"kolId\":\""
                            + kolId
                            + "\",\"quota\":5,\"validMinutes\":60,\"targetChannel\":\"WEIDIAN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").isString())
            .andExpect(jsonPath("$.data.tokenHint").isString())
            .andReturn();
    JsonNode data = json.readTree(created.getResponse().getContentAsString()).path("data");
    String plaintext = data.path("token").asString();
    long id = Long.parseLong(data.path("id").asText());
    String hint = data.path("tokenHint").asText();

    assertThat(plaintext).startsWith("TKN-").hasSize(30);
    assertThat(hint).isNotEqualTo(plaintext);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT token_hash, token_cipher, token_hint FROM crm_kol_copy_token WHERE id = ?", id);
    assertThat(row.get("token_hash").toString()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(row.get("token_cipher").toString()).startsWith("v1:");
    assertThat(row.get("token_hint").toString()).isEqualTo(hint);

    mvc.perform(
            get("/admin-api/kol/copy-tokens").header(ADMIN_TOKEN, admin).param("status", "ACTIVE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].tokenHint").value(hint))
        .andExpect(jsonPath("$.data.items[0].token").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].displayStatus").value("ACTIVE"));

    mvc.perform(post("/admin-api/kol/copy-tokens/" + id + "/reveal").header(ADMIN_TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.token").value(plaintext));

    CopyTokenGrantDTO grant = copyTokens.authenticate(plaintext);
    assertThat(grant.kolId()).isEqualTo(kolId);
    assertThat(grant.remaining()).isEqualTo(5);

    AtomicInteger successes = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(20);
    List<Callable<Boolean>> jobs = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      jobs.add(() -> copyTokens.consume(grant.tokenId(), 1));
    }
    for (Future<Boolean> future : pool.invokeAll(jobs)) {
      if (Boolean.TRUE.equals(future.get())) {
        successes.incrementAndGet();
      }
    }
    pool.shutdown();
    assertThat(successes.get()).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT used FROM crm_kol_copy_token WHERE id = ?", Integer.class, id))
        .isEqualTo(5);

    mvc.perform(delete("/admin-api/kol/copy-tokens/" + id).header(ADMIN_TOKEN, admin))
        .andExpect(status().isOk());
    mvc.perform(delete("/admin-api/kol/copy-tokens/" + id).header(ADMIN_TOKEN, admin))
        .andExpect(status().isConflict());

    Integer leaks =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_oper_log WHERE request_params LIKE ?",
            Integer.class,
            "%" + plaintext + "%");
    assertThat(leaks).isZero();
  }

  @Test
  void shouldHideForeignKolTokens_forOwnScopeBd() throws Exception {
    long bdId = staff("bd17a", "OWN", "kol:copy-token", "kol:view", "kol:edit");
    String bdToken = CompanySessions.login(mvc, json, "bd17a", "staff-pass-123");
    long mine = createKol("Mine", "mine17a@example.com", bdId);
    long other = createKol("Other", "other17a@example.com", null);

    mvc.perform(
            post("/admin-api/kol/copy-tokens")
                .header(ADMIN_TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kolId\":\"" + other + "\",\"targetChannel\":\"WEIDIAN\"}"))
        .andExpect(status().isOk());

    MvcResult mineCreated =
        mvc.perform(
                post("/admin-api/kol/copy-tokens")
                    .header(ADMIN_TOKEN, bdToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"kolId\":\"" + mine + "\",\"targetChannel\":\"WEIDIAN\"}"))
            .andExpect(status().isOk())
            .andReturn();
    long mineTokenId =
        Long.parseLong(
            json.readTree(mineCreated.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

    mvc.perform(get("/admin-api/kol/copy-tokens").header(ADMIN_TOKEN, bdToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(mineTokenId)));

    long otherTokenId =
        jdbc.queryForObject(
            "SELECT id FROM crm_kol_copy_token WHERE kol_id = ?", Long.class, other);
    mvc.perform(
            post("/admin-api/kol/copy-tokens/" + otherTokenId + "/reveal")
                .header(ADMIN_TOKEN, bdToken))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/admin-api/kol/copy-tokens/" + otherTokenId).header(ADMIN_TOKEN, bdToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void shouldFilterDisplayStatuses() throws Exception {
    long kolId = createKol("Status", "status17a@example.com", null);
    MvcResult created =
        mvc.perform(
                post("/admin-api/kol/copy-tokens")
                    .header(ADMIN_TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"kolId\":\""
                            + kolId
                            + "\",\"quota\":1,\"validMinutes\":60,\"targetChannel\":\"WEIDIAN\"}"))
            .andExpect(status().isOk())
            .andReturn();
    long id =
        Long.parseLong(
            json.readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

    jdbc.update("UPDATE crm_kol_copy_token SET used = quota WHERE id = ?", id);
    mvc.perform(
            get("/admin-api/kol/copy-tokens")
                .header(ADMIN_TOKEN, admin)
                .param("status", "EXHAUSTED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].displayStatus").value("EXHAUSTED"));

    jdbc.update(
        "UPDATE crm_kol_copy_token SET used = 0, expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?",
        id);
    mvc.perform(
            get("/admin-api/kol/copy-tokens").header(ADMIN_TOKEN, admin).param("status", "EXPIRED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].displayStatus").value("EXPIRED"));
  }

  @Test
  void shouldCreateWithTargetShop_andRejectDisabledOrForeign() throws Exception {
    long kolId = createKol("ShopTok", "shoptok28@example.com", null);
    long shopId =
        Long.parseLong(
            json.readTree(
                    mvc.perform(
                            post("/admin-api/product/shops")
                                .header(ADMIN_TOKEN, admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    "{\"shopName\":\"Copy Shop\",\"channel\":\"WEIDIAN\",\"weidianShopId\":\"28001\",\"maxItems\":20000}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .at("/data/id")
                .asText());
    jdbc.update("UPDATE crm_shop SET copy_pool_status = 'JOINED' WHERE id = ?", shopId);
    authorizeShop(shopId);

    mvc.perform(
            post("/admin-api/kol/copy-tokens")
                .header(ADMIN_TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"kolId\":\""
                        + kolId
                        + "\",\"quota\":1,\"validMinutes\":60,\"targetShopId\":\""
                        + shopId
                        + "\",\"targetChannel\":\"WEIDIAN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.targetShopId").value(String.valueOf(shopId)));

    jdbc.update("UPDATE crm_shop SET status = 0 WHERE id = ?", shopId);
    mvc.perform(
            post("/admin-api/kol/copy-tokens")
                .header(ADMIN_TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"kolId\":\""
                        + kolId
                        + "\",\"quota\":1,\"validMinutes\":60,\"targetShopId\":\""
                        + shopId
                        + "\",\"targetChannel\":\"WEIDIAN\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("SHOP_DISABLED"));

    mvc.perform(
            post("/admin-api/kol/copy-tokens")
                .header(ADMIN_TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"kolId\":\""
                        + kolId
                        + "\",\"quota\":1,\"validMinutes\":60,\"targetShopId\":\"999999\",\"targetChannel\":\"WEIDIAN\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("SHOP_NOT_FOUND"));
  }

  @Test
  void shouldRejectTokenQuotaAboveExplicitShopOrChannelCapacity() throws Exception {
    long kolId = createKol("t37_capacity_kol", "t37_capacity@example.com", null);
    jdbc.update("UPDATE crm_shop SET max_items=5,current_items=2 WHERE id=900001");
    for (String target : List.of("", ",\"targetShopId\":\"900001\"")) {
      mvc.perform(
              post("/admin-api/kol/copy-tokens")
                  .header(ADMIN_TOKEN, admin)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"kolId\":\""
                          + kolId
                          + "\",\"targetChannel\":\"WEIDIAN\",\"quota\":4"
                          + target
                          + "}"))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.errorCode").value("SHOP_COPY_CAPACITY_EXCEEDED"));
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM crm_kol_copy_token", Integer.class))
        .isZero();
  }

  @Test
  void shouldExcludeSourceShopAndRequireExplicitlyRejoiningThePoolAfterUnmarking()
      throws Exception {
    mvc.perform(put("/admin-api/product/shops/900001/source").header(ADMIN_TOKEN, admin))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/product/shops").header(ADMIN_TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].isSource").value(true))
        .andExpect(jsonPath("$.data.items[0].copyPoolStatus").value("NOT_APPLICABLE"))
        .andExpect(jsonPath("$.data.items[0].copyIneligibleReason").value("SOURCE_SHOP"));
    mvc.perform(put("/admin-api/product/shops/900001/copy-pool").header(ADMIN_TOKEN, admin))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("SHOP_SOURCE_CANNOT_JOIN_POOL"));
    assertThat(
            TenantScope.callCompany(1L, shopService::copyChannels).getFirst().remainingCapacity())
        .isZero();
    mvc.perform(delete("/admin-api/product/shops/900001/source").header(ADMIN_TOKEN, admin))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT copy_pool_status FROM crm_shop WHERE id=900001", String.class))
        .isEqualTo("EXITED");
    mvc.perform(put("/admin-api/product/shops/900001/copy-pool").header(ADMIN_TOKEN, admin))
        .andExpect(status().isOk());
    assertThat(
            TenantScope.callCompany(1L, shopService::copyChannels).getFirst().remainingCapacity())
        .isEqualTo(20000);
  }

  @Test
  void shouldChooseLargestRemainingShopAndNeverSplitAnOversizedJob() {
    jdbc.update("UPDATE crm_shop SET max_items=10,current_items=8 WHERE id=900001");
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_code,channel,shop_name,weidian_shop_id,status,is_source,copy_pool_status,max_items,current_items,create_time,update_time) "
            + "VALUES(900002,1,'SHOP-900002','WEIDIAN','t37_larger','390002',1,FALSE,'JOINED',10,1,now(),now())");
    authorizeShop(900002L);

    assertThat(
            TenantScope.callCompany(1L, shopService::copyChannels).getFirst().remainingCapacity())
        .isEqualTo(11);
    assertThat(TenantScope.callCompany(1L, () -> shopService.allocateCopyTarget("WEIDIAN", 8)))
        .isEqualTo(900002L);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> TenantScope.callCompany(1L, () -> shopService.allocateCopyTarget("WEIDIAN", 10)))
        .isInstanceOf(com.tcrm.admin.shared.BusinessException.class)
        .satisfies(
            error ->
                assertThat(((com.tcrm.admin.shared.BusinessException) error).getErrorCode())
                    .isEqualTo(com.tcrm.admin.shared.ErrorCode.COPY_SHOP_CAPACITY_INSUFFICIENT));
  }

  @Test
  void shouldNotReserveSameRemainingCapacityForConcurrentJobs() throws Exception {
    var channels = TenantScope.callCompany(1L, shopService::copyChannels);
    assertThat(channels).hasSize(1);
    assertThat(channels.getFirst().shops()).hasSize(1);
    jdbc.update("UPDATE crm_shop SET max_items = 1, current_items = 0 WHERE id = 900001");
    var limitedChannels = TenantScope.callCompany(1L, shopService::copyChannels);
    assertThat(limitedChannels.getFirst().remainingCapacity()).isEqualTo(1);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results =
          List.of(
              executor.submit(() -> reserveOne(ready, start, 990001L)),
              executor.submit(() -> reserveOne(ready, start, 990002L)));
      ready.await();
      start.countDown();
      boolean firstAllocated = results.get(0).get();
      boolean secondAllocated = results.get(1).get();
      assertThat(firstAllocated ^ secondAllocated)
          .withFailMessage(
              "Expected exactly one allocation to succeed; first=%s, second=%s",
              firstAllocated, secondAllocated)
          .isTrue();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM crm_copy_job_item", Integer.class))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  private boolean reserveOne(CountDownLatch ready, CountDownLatch start, long id) throws Exception {
    ready.countDown();
    start.await();
    try {
      TenantScope.runCompany(
          1L,
          () ->
              transactions.execute(
                  status -> {
                    long shopId = shopService.allocateCopyTarget("WEIDIAN", 1);
                    jdbc.update(
                        "INSERT INTO crm_copy_job(id,tenant_id,kol_id,token_id,job_code,caller_platform,target_shop_id,status,total_count,success_count,fail_count,create_time,update_time) VALUES(?,1,1,1,?,'test',?,'processing',1,0,0,now(),now())",
                        id,
                        "t37_capacity_" + id,
                        shopId);
                    jdbc.update(
                        "INSERT INTO crm_copy_job_item(id,job_id,tenant_id,source_channel,source_product_id,status,update_time) VALUES(?,?,1,'WEIDIAN',?,'pending',now())",
                        id + 100,
                        id,
                        Long.toString(id));
                    return true;
                  }));
      return true;
    } catch (com.tcrm.admin.shared.BusinessException error) {
      assertThat(error.getErrorCode())
          .isEqualTo(com.tcrm.admin.shared.ErrorCode.COPY_SHOP_CAPACITY_INSUFFICIENT);
      return false;
    }
  }

  private long staff(String username, String scope, String... permissions) throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/system/users")
                    .header(ADMIN_TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "username",
                                username,
                                "password",
                                "staff-pass-123",
                                "nickname",
                                username,
                                "role",
                                "STAFF",
                                "postTemplate",
                                "BD",
                                "dataScope",
                                scope,
                                "permissions",
                                List.of(permissions)))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(json.readTree(body).at("/data/id").asText());
  }

  private long createKol(String name, String email, Long bdUserId) throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/kol/kols")
                    .header(ADMIN_TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\""
                            + name
                            + "\",\"email\":\""
                            + email
                            + "\",\"country\":\"US\",\"socialAccounts\":[]"
                            + (bdUserId == null ? "" : ",\"bdUserId\":\"" + bdUserId + "\"")
                            + "}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(json.readTree(body).at("/data/id").asText());
  }

  private void authorizeShop(long shopId) {
    jdbc.update(
        "INSERT INTO crm_weidian_shop_auth(id,tenant_id,shop_id,auth_mode,status,access_expires_at,create_time,update_time) "
            + "VALUES(?,1,?,'SERVICE','AUTHORIZED','2099-01-01T00:00:00Z',now(),now())",
        shopId,
        shopId);
  }
}
