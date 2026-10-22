package com.tcrm.admin.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.TestWait;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.link.service.LinkIndexSyncJob;
import com.tcrm.admin.product.api.WeidianItemCopyApi;
import com.tcrm.admin.product.api.WeidianItemCopyApi.CopyResult;
import com.tcrm.admin.product.api.WeidianItemCopyApi.ProbedItem;
import com.tcrm.admin.shared.SecretCipher;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 17b against real PostgreSQL/Redis: convert → worker (mocked Weidian) → resolve → job items,
 * plus auth and missing-target errors. Weidian wire format is covered by 09a tests.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ProductLinkIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String ADMIN = "X-Admin-Token";
  private static final String BASE = "/admin-api/external/product-links";

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
    r.add("crm.link.base-url", () -> "https://t.example.test");
    r.add("crm.kol-portal.base-url", () -> "https://kol.example.com");
    r.add("crm.link.click-consumer.enabled", () -> "false");
    WeidianTestProperties.register(r);
  }

  @MockitoBean WeidianItemCopyApi copies;

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final SecretCipher cipher;
  private final LinkIndexSyncJob sync;

  private String admin;

  @Autowired
  ProductLinkIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      StringRedisTemplate redis,
      SecretCipher cipher,
      LinkIndexSyncJob sync) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.cipher = cipher;
    this.sync = sync;
  }

  @BeforeEach
  void prepare() throws Exception {
    jdbc.update("DELETE FROM crm_copy_job_item");
    jdbc.update("DELETE FROM crm_copy_job");
    jdbc.update("DELETE FROM crm_link_index_outbox");
    jdbc.update("DELETE FROM crm_kol_item");
    jdbc.update("DELETE FROM crm_product");
    jdbc.update("DELETE FROM crm_weidian_shop_auth");
    jdbc.update("DELETE FROM crm_kol_copy_token");
    jdbc.update("DELETE FROM crm_shop");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
    admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    when(copies.copy(anyLong(), any(), anyLong())).thenReturn(new CopyResult("90001", null));
    when(copies.isShopAuthorized(anyLong())).thenReturn(true);
    when(copies.probe(anyLong(), any()))
        .thenReturn(Optional.of(new ProbedItem("10001", "Source", new BigDecimal("9.90"))));
  }

  @Test
  void shouldConvertProcessAndResolve_endToEnd() throws Exception {
    long shopId = createShop("1001", "Main");
    mvc.perform(put("/admin-api/product/shops/" + shopId + "/copy-pool").header(ADMIN, admin))
        .andExpect(status().isOk());
    authorizeShop(shopId);
    long productId = insertProduct(shopId, "10001", "Source item");
    long kolId = createKol("CopyKol", "copy17b@example.com");
    String token = createToken(kolId, 10);

    MvcResult converted =
        mvc.perform(
                post(BASE + "/convert")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"token":"%s","callerPlatform":"google_sheet_plugin",
                         "items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"10001","ruleId":"r1"}]}
                        """
                            .formatted(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.jobCode").isString())
            .andReturn();
    String jobCode =
        json.readTree(converted.getResponse().getContentAsString())
            .path("data")
            .path("jobCode")
            .asString();
    assertThat(jobCode).hasSize(22);

    TestWait.until(
        Duration.ofSeconds(10),
        () ->
            "completed"
                .equals(
                    jdbc.queryForObject(
                        "SELECT status FROM crm_copy_job WHERE job_code = ?",
                        String.class,
                        jobCode)),
        "copy job should complete");

    mvc.perform(
            post(BASE + "/jobs/" + jobCode + "/items/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"token":"%s","items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"10001"}]}
                    """
                        .formatted(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ready"))
        .andExpect(jsonPath("$.data.resultVersion").isString())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].status").value("success"))
        .andExpect(jsonPath("$.data.items[0].targetExternalProductId").value("90001"))
        .andExpect(jsonPath("$.data.items[0].targetChannelCode").value("WEIDIAN"))
        .andExpect(jsonPath("$.data.items[0].targetStoreId").value("1001"));

    mvc.perform(get(BASE + "/jobs/" + jobCode))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("completed"))
        .andExpect(jsonPath("$.data.successCount").value(1));

    mvc.perform(get(BASE + "/jobs/" + jobCode + "/items"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].status").value("success"))
        .andExpect(jsonPath("$.data[0].targetExternalProductId").value("90001"));

    Integer mappings =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM crm_kol_item WHERE kol_id = ? AND product_id = ? AND valid_to IS NULL",
            Integer.class,
            kolId,
            productId);
    assertThat(mappings).isEqualTo(1);
    String mappingSource =
        jdbc.queryForObject(
            """
            SELECT source FROM crm_kol_item
            WHERE kol_id = ? AND product_id = ? AND valid_to IS NULL
            """,
            String.class,
            kolId,
            productId);
    assertThat(mappingSource).isEqualTo("COPY");
    assertThat(
            jdbc.queryForObject(
                "SELECT target_channel FROM crm_copy_job WHERE job_code = ?",
                String.class,
                jobCode))
        .isEqualTo("WEIDIAN");
    assertThat(
            jdbc.queryForObject(
                "SELECT finish_time FROM crm_copy_job WHERE job_code = ?",
                Timestamp.class,
                jobCode))
        .isNotNull();
    assertThat(
            jdbc.queryForObject(
                """
                SELECT item.source_shop_id
                FROM crm_copy_job_item item
                JOIN crm_copy_job job ON job.id = item.job_id
                WHERE job.job_code = ?
                """,
                Long.class,
                jobCode))
        .isEqualTo(shopId);
    TestWait.until(
        Duration.ofSeconds(10),
        () ->
            jdbc.queryForObject(
                    "SELECT COUNT(*) FROM crm_link_index_outbox WHERE scope = 'MAPPING' AND scope_id = ? AND product_id = ?",
                    Integer.class,
                    kolId,
                    productId)
                >= 1,
        "copy mapping should register a link-index refresh");

    TestWait.until(
        Duration.ofSeconds(10),
        () -> {
          sync.drain();
          return Boolean.TRUE.equals(redis.opsForHash().hasKey("linkitem:01", "90001"));
        },
        "linkitem bucket should contain the new exclusive item id");
    assertThat(redis.opsForHash().hasKey("linkitem:01", "90001")).isTrue();

    // Re-convert same product: existing mapping → success without another Weidian add
    when(copies.copy(anyLong(), any(), anyLong()))
        .thenThrow(new AssertionError("should not copy again"));
    MvcResult again =
        mvc.perform(
                post(BASE + "/convert")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"token":"%s","callerPlatform":"google_sheet_plugin",
                         "items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"10001"}]}
                        """
                            .formatted(token)))
            .andExpect(status().isOk())
            .andReturn();
    String job2 =
        json.readTree(again.getResponse().getContentAsString())
            .path("data")
            .path("jobCode")
            .asString();
    TestWait.until(
        Duration.ofSeconds(10),
        () ->
            "completed"
                .equals(
                    jdbc.queryForObject(
                        "SELECT status FROM crm_copy_job WHERE job_code = ?", String.class, job2)),
        "second copy job should complete");
    Integer used =
        jdbc.queryForObject(
            "SELECT used FROM crm_kol_copy_token WHERE kol_id = ?", Integer.class, kolId);
    assertThat(used).isEqualTo(1); // only first real copy consumed
  }

  @Test
  void shouldRejectInvalidToken_andMissingTarget_withChineseMsg() throws Exception {
    mvc.perform(
            post(BASE + "/convert")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"token":"TKN-AAAAAAAAAAAAAAAAAAAAAAAAAA","callerPlatform":"google_sheet_plugin",
                     "items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"1"}]}
                    """))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("COPY_TOKEN_INVALID"))
        .andExpect(jsonPath("$.msg").value("复制令牌无效或已作废"));

    long kolId = createKol("NoShop", "noshop17b@example.com");
    long shopId = createShop("1001", "Temporary pool member");
    mvc.perform(put("/admin-api/product/shops/" + shopId + "/copy-pool").header(ADMIN, admin))
        .andExpect(status().isOk());
    authorizeShop(shopId);
    String token = createToken(kolId, 5);
    mvc.perform(delete("/admin-api/product/shops/" + shopId + "/copy-pool").header(ADMIN, admin))
        .andExpect(status().isOk());
    mvc.perform(
            post(BASE + "/convert")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"token":"%s","callerPlatform":"google_sheet_plugin",
                     "items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"1"}]}
                    """
                        .formatted(token)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("COPY_SHOP_CAPACITY_INSUFFICIENT"))
        .andExpect(jsonPath("$.msg").value("自动复制池没有足够容量的可用店铺"));
  }

  @Test
  void shouldRejectSpecifiedTargetShopCapacity_withChineseMsg() throws Exception {
    long kolId = createKol("CapShop", "capshop17b@example.com");
    long shopId = createShop("371002", "Capacity limited pool member");
    mvc.perform(put("/admin-api/product/shops/" + shopId + "/copy-pool").header(ADMIN, admin))
        .andExpect(status().isOk());
    authorizeShop(shopId);
    jdbc.update("UPDATE crm_shop SET max_items = 1, current_items = 0 WHERE id = ?", shopId);
    String token = createTokenForShop(kolId, 1, shopId);
    jdbc.update("UPDATE crm_shop SET current_items = 1 WHERE id = ?", shopId);
    mvc.perform(
            post(BASE + "/convert")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"token":"%s","callerPlatform":"google_sheet_plugin",
                     "items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"1"}]}
                    """
                        .formatted(token)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("SHOP_COPY_CAPACITY_EXCEEDED"))
        .andExpect(jsonPath("$.msg").value("复制数量超过可用店铺容量"));
  }

  @Test
  void shouldReturnTemplates_withRelayFirst() throws Exception {
    mvc.perform(get(BASE + "/templates").param("channelCode", "WEIDIAN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].templateCode").value("TCRM_RELAY"))
        .andExpect(
            jsonPath("$.data[0].templateUrl").value("https://t.example.test/i/{PRODUCT_ID}"));
  }

  @Test
  void shouldReturn404_forUnknownJob() throws Exception {
    mvc.perform(get(BASE + "/jobs/does-not-exist-job-code-xx"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.msg").value("任务编号不存在"));
  }

  @Test
  void shouldResolveAlreadyApplied_forOwnExclusiveItem() throws Exception {
    long shopId = createShop("1001", "Main");
    mvc.perform(put("/admin-api/product/shops/" + shopId + "/copy-pool").header(ADMIN, admin))
        .andExpect(status().isOk());
    authorizeShop(shopId);
    long productId = insertProduct(shopId, "10001", "Source item");
    long kolId = createKol("AppliedKol", "applied17b@example.com");
    String token = createToken(kolId, 10);
    // Seed an exclusive mapping whose weidian id will be sent as "source"
    jdbc.update(
        """
        INSERT INTO crm_kol_item(
          id, kol_id, product_id, shop_id, weidian_item_id, valid_from,
          tenant_id, create_time, update_time)
        VALUES (17001, ?, ?, ?, '90001', now(), 1, now(), now())
        """,
        kolId,
        productId,
        shopId);
    // Create an empty completed job so resolve has a jobCode
    jdbc.update(
        """
        INSERT INTO crm_copy_job(
          id, tenant_id, kol_id, token_id, job_code, caller_platform, target_shop_id,
          target_channel, status, total_count, success_count, fail_count, create_time, update_time)
        VALUES (17001, 1, ?, (SELECT id FROM crm_kol_copy_token WHERE kol_id = ? LIMIT 1),
                'alreadyAppliedJobCode17bxx', 'test', ?, 'WEIDIAN', 'completed', 0, 0, 0, now(), now())
        """,
        kolId,
        kolId,
        shopId);

    mvc.perform(
            post(BASE + "/jobs/alreadyAppliedJobCode17bxx/items/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"token":"%s","items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"90001"}]}
                    """
                        .formatted(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ready"))
        .andExpect(jsonPath("$.data.items[0].status").value("already_applied"));
  }

  @Test
  void shouldListOnlyAssignedKolItems_forOwnScopeBd() throws Exception {
    long bdId =
        CompanySessions.createStaff(
            mvc, json, admin, PASSWORD, "bd-kol-items", "BD", "OWN", List.of("product:view"));
    long otherBdId =
        CompanySessions.createStaff(
            mvc, json, admin, PASSWORD, "other-kol-items", "BD", "OWN", List.of("product:view"));
    long shopId = createShop("1003", "Items");
    long productId = insertProduct(shopId, "10003", "Mapped product");
    long ownKolId = createKol("Own item KOL", "own-item@example.com");
    long otherKolId = createKol("Other item KOL", "other-item@example.com");
    String otherKolCode =
        jdbc.queryForObject("SELECT kol_code FROM crm_kol WHERE id = ?", String.class, otherKolId);
    jdbc.update("UPDATE crm_kol SET bd_user_id = ? WHERE id = ?", bdId, ownKolId);
    jdbc.update("UPDATE crm_kol SET bd_user_id = ? WHERE id = ?", otherBdId, otherKolId);
    jdbc.update(
        "INSERT INTO crm_kol_item(id, tenant_id, kol_id, product_id, shop_id, weidian_item_id,"
            + " source, valid_from, create_time, update_time)"
            + " VALUES (17011, 1, ?, ?, ?, '91003', 'IMPORT', now(), now(), now()),"
            + " (17012, 1, ?, ?, ?, '91004', 'IMPORT', now(), now(), now())",
        ownKolId,
        productId,
        shopId,
        otherKolId,
        productId,
        shopId);
    String bdToken = CompanySessions.login(mvc, json, "bd-kol-items", PASSWORD);

    mvc.perform(get("/admin-api/product/kol-items").header(ADMIN, bdToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].kolId").value(Long.toString(ownKolId)))
        .andExpect(
            jsonPath("$.data.items[0].kolCode").value(org.hamcrest.Matchers.startsWith("KOL-")));
    mvc.perform(
            get("/admin-api/product/kol-items")
                .param("kolCode", otherKolCode)
                .header(ADMIN, bdToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
  }

  @Test
  void shouldRejectResolve_withOtherKolToken() throws Exception {
    long shopId = createShop("1002", "Other");
    mvc.perform(put("/admin-api/product/shops/" + shopId + "/copy-pool").header(ADMIN, admin))
        .andExpect(status().isOk());
    authorizeShop(shopId);
    long kolA = createKol("KolA", "kola17b@example.com");
    long kolB = createKol("KolB", "kolb17b@example.com");
    String tokenA = createToken(kolA, 5);
    String tokenB = createToken(kolB, 5);
    MvcResult converted =
        mvc.perform(
                post(BASE + "/convert")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"token":"%s","callerPlatform":"google_sheet_plugin",
                         "items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"10001"}]}
                        """
                            .formatted(tokenA)))
            .andExpect(status().isOk())
            .andReturn();
    String jobCode =
        json.readTree(converted.getResponse().getContentAsString())
            .path("data")
            .path("jobCode")
            .asString();

    mvc.perform(
            post(BASE + "/jobs/" + jobCode + "/items/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"token":"%s","items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"10001"}]}
                    """
                        .formatted(tokenB)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("COPY_TOKEN_INVALID"));
  }

  @Test
  void shouldKeepFailedJob_whenTargetShopIsNotAuthorized() throws Exception {
    long shopId = createShop("1001", "Main");
    mvc.perform(put("/admin-api/product/shops/" + shopId + "/copy-target").header(ADMIN, admin))
        .andExpect(status().isOk());
    long kolId = createKol("FailKol", "fail38@example.com");
    String token = createToken(kolId, 10);
    when(copies.isShopAuthorized(anyLong())).thenReturn(false);

    mvc.perform(
            post(BASE + "/convert")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"token":"%s","callerPlatform":"google_sheet_plugin",
                     "items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"10001"}]}
                    """
                        .formatted(token)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("WEIDIAN_SHOP_NOT_AUTHORIZED"));

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crm_copy_job", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT status FROM crm_copy_job", String.class))
        .isEqualTo("failed");
    assertThat(jdbc.queryForObject("SELECT error_message FROM crm_copy_job", String.class))
        .contains("未完成微店授权");
    assertThat(jdbc.queryForObject("SELECT finish_time FROM crm_copy_job", Timestamp.class))
        .isNotNull();
    assertThat(jdbc.queryForObject("SELECT target_channel FROM crm_copy_job", String.class))
        .isEqualTo("WEIDIAN");
    assertThat(jdbc.queryForObject("SELECT total_count FROM crm_copy_job", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crm_copy_job_item", Integer.class))
        .isZero();
  }

  @Test
  void shouldListCopyJobs_forBdScopeCompanyAndItems() throws Exception {
    long shopId = createShop("1001", "Main");
    long bdId =
        CompanySessions.createStaff(mvc, json, admin, PASSWORD, "bd38copy", "BD", null, null);
    String bdToken = CompanySessions.login(mvc, json, "bd38copy", PASSWORD);
    long ownKol = createKol("OwnKol", "own38@example.com", bdId);
    long otherKol = createKol("OtherKol", "other38@example.com", null);
    Instant older = Instant.parse("2026-10-21T01:00:00Z");
    Instant newer = Instant.parse("2026-10-21T02:00:00Z");
    insertJob(38001L, 1L, ownKol, shopId, "t38ownjob", "completed", 3, 2, 1, null, older);
    insertJob(38002L, 1L, otherKol, shopId, "t38otherjob", "failed", 0, 0, 0, "店铺未授权", newer);
    insertItem(38011L, 38001L, 1L, "10001", shopId, null, "failed");
    insertItem(38012L, 38001L, 1L, "10002", shopId, null, "success");
    insertItem(38013L, 38001L, 1L, "10003", shopId, "90001", "success");

    mvc.perform(get("/admin-api/product/copy-jobs").header(ADMIN, bdToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].jobCode").value("t38ownjob"))
        .andExpect(jsonPath("$.data.items[0].kolId").value(Long.toString(ownKol)));
    mvc.perform(get("/admin-api/product/copy-jobs/38002/items").header(ADMIN, bdToken))
        .andExpect(status().isNotFound());

    mvc.perform(get("/admin-api/product/copy-jobs").param("jobCode", "t38own").header(ADMIN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].id").value("38001"));
    mvc.perform(get("/admin-api/product/copy-jobs").param("jobCode", "t38_").header(ADMIN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(
            get("/admin-api/product/copy-jobs")
                .param("status", "completed")
                .param("targetChannel", "WEIDIAN")
                .header(ADMIN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].jobCode").value("t38ownjob"))
        .andExpect(jsonPath("$.data.items[0].failCount").value(1))
        .andExpect(jsonPath("$.data.items[0].targetShopCode").value(nullValue()));
    mvc.perform(get("/admin-api/product/copy-jobs").header(ADMIN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].jobCode").value("t38otherjob"));
    mvc.perform(get("/admin-api/product/copy-jobs").param("status", "nope").header(ADMIN, admin))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/admin-api/product/copy-jobs")
                .param("targetChannel", "weidian")
                .header(ADMIN, admin))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/admin-api/product/copy-jobs")
                .param("targetChannel", "TIKTOK")
                .header(ADMIN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));

    mvc.perform(
            get("/admin-api/product/copy-jobs/38001/items")
                .param("page", "1")
                .param("size", "2")
                .header(ADMIN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("3"))
        .andExpect(jsonPath("$.data.items[0].seq").value(1))
        .andExpect(jsonPath("$.data.items[0].sourceProductId").value("10001"))
        .andExpect(jsonPath("$.data.items[0].sourceShopName").value("Main"))
        .andExpect(jsonPath("$.data.items[0].sourceMerchantNo").value("1001"))
        .andExpect(jsonPath("$.data.items[0].sourceShopId").value(Long.toString(shopId)))
        .andExpect(jsonPath("$.data.items[1].seq").value(2));
    mvc.perform(
            get("/admin-api/product/copy-jobs/38001/items")
                .param("page", "2")
                .param("size", "2")
                .header(ADMIN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].seq").value(3))
        .andExpect(
            jsonPath("$.data.items[0].targetItemUrl")
                .value("https://weidian.com/item.html?itemID=90001"));

    String code = "t38" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    CompanySessions.OpenedCompany other =
        CompanySessions.openCompany(mvc, json, PASSWORD, code, code + "adm");
    insertJob(38021L, other.tenantId(), 1L, 1L, "t38foreignjob", "completed", 1, 1, 0, null, newer);
    mvc.perform(get("/admin-api/product/copy-jobs/38021/items").header(ADMIN, admin))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/admin-api/product/copy-jobs").param("jobCode", "t38foreign").header(ADMIN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
  }

  @Test
  void shouldBackfillFinishTimeSourceShopAndChannel() throws Exception {
    long shopId = createShop("1001", "Main");
    long kolId = createKol("Backfill", "back38@example.com");
    String token = createToken(kolId, 1);
    Long tokenId =
        jdbc.queryForObject(
            "SELECT id FROM crm_kol_copy_token WHERE kol_id = ?", Long.class, kolId);
    assertThat(token).isNotBlank();
    jdbc.update("ALTER TABLE crm_copy_job ALTER COLUMN target_channel DROP NOT NULL");
    try {
      jdbc.update(
          """
          INSERT INTO crm_copy_job(
            id, tenant_id, kol_id, token_id, job_code, caller_platform, target_shop_id,
            status, total_count, success_count, fail_count, create_time, update_time)
          VALUES (38031, 1, ?, ?, 't38backfilljob', 'test', ?, 'completed', 1, 1, 0, now(), now())
          """,
          kolId,
          tokenId,
          shopId);
      jdbc.update(
          """
          UPDATE crm_copy_job AS job
          SET target_channel = token.target_channel
          FROM crm_kol_copy_token AS token
          WHERE job.token_id = token.id
            AND job.target_channel IS NULL
            AND token.target_channel IS NOT NULL
          """);
      jdbc.update(
          """
          UPDATE crm_copy_job
          SET target_channel = 'WEIDIAN'
          WHERE target_channel IS NULL
          """);
      assertThat(
              jdbc.queryForObject(
                  "SELECT target_channel FROM crm_copy_job WHERE id = 38031", String.class))
          .isEqualTo("WEIDIAN");
    } finally {
      jdbc.update(
          "UPDATE crm_copy_job SET target_channel = 'WEIDIAN' WHERE target_channel IS NULL");
      jdbc.update("ALTER TABLE crm_copy_job ALTER COLUMN target_channel SET NOT NULL");
    }

    jdbc.update("UPDATE crm_copy_job SET finish_time = NULL WHERE id = 38031");
    jdbc.update(
        """
        UPDATE crm_copy_job
        SET finish_time = update_time
        WHERE status IN ('completed', 'failed', 'partial_failed')
          AND finish_time IS NULL
        """);
    assertThat(
            jdbc.queryForObject(
                "SELECT finish_time FROM crm_copy_job WHERE id = 38031", Timestamp.class))
        .isNotNull();

    insertProduct(shopId, "10001", "Source");
    jdbc.update(
        """
        INSERT INTO crm_copy_job_item(
          id, job_id, tenant_id, source_channel, source_product_id, status, update_time)
        VALUES (38041, 38031, 1, 'WEIDIAN', '10001', 'success', now())
        """);
    jdbc.update(
        """
        UPDATE crm_copy_job_item AS item
        SET source_shop_id = product.shop_id
        FROM (
          SELECT tenant_id, weidian_item_id, MIN(shop_id) AS shop_id
          FROM crm_product
          WHERE is_deleted = FALSE
          GROUP BY tenant_id, weidian_item_id
          HAVING COUNT(DISTINCT shop_id) = 1
        ) AS product
        WHERE item.source_shop_id IS NULL
          AND item.tenant_id = product.tenant_id
          AND item.source_product_id = product.weidian_item_id
        """);
    assertThat(
            jdbc.queryForObject(
                "SELECT source_shop_id FROM crm_copy_job_item WHERE id = 38041", Long.class))
        .isEqualTo(shopId);
  }

  private long createShop(String weidianId, String name) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/admin-api/product/shops")
                    .header(ADMIN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"shopName\":\""
                            + name
                            + "\",\"channel\":\"WEIDIAN\",\"weidianShopId\":\""
                            + weidianId
                            + "\",\"maxItems\":20000}"))
            .andExpect(status().isOk())
            .andReturn();
    return Long.parseLong(
        json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText());
  }

  private void authorizeShop(long shopId) {
    jdbc.update(
        """
        INSERT INTO crm_weidian_shop_auth(
          id, tenant_id, shop_id, auth_mode, access_token_cipher, refresh_token_cipher,
          access_expires_at, refresh_expires_at, status, create_time, update_time)
        VALUES (?, 1, ?, 'SERVICE', ?, ?, ?, ?, 'AUTHORIZED', now(), now())
        """,
        shopId + 1000,
        shopId,
        cipher.encrypt("access-token"),
        cipher.encrypt("refresh-token"),
        Timestamp.from(Instant.now().plusSeconds(3600)),
        Timestamp.from(Instant.now().plusSeconds(86400)));
  }

  private long insertProduct(long shopId, String itemId, String name) {
    long id = 17_001L;
    jdbc.update(
        """
        INSERT INTO crm_product(
          id, product_code, shop_id, weidian_item_id, name, price, price_currency, status,
          tenant_id, create_time, update_time, is_deleted)
        VALUES (?, ?, ?, ?, ?, 9.90, 'CNY', 1, 1, now(), now(), FALSE)
        """,
        id,
        "P17B001",
        shopId,
        itemId,
        name);
    return id;
  }

  private long createKol(String name, String email) throws Exception {
    return createKol(name, email, null);
  }

  private long createKol(String name, String email, Long bdUserId) throws Exception {
    String bd = bdUserId == null ? "" : ",\"bdUserId\":\"" + bdUserId + "\"";
    MvcResult result =
        mvc.perform(
                post("/admin-api/kol/kols")
                    .header(ADMIN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\""
                            + name
                            + "\",\"email\":\""
                            + email
                            + "\",\"country\":\"US\",\"socialAccounts\":[]"
                            + bd
                            + "}"))
            .andExpect(status().isOk())
            .andReturn();
    return Long.parseLong(
        json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText());
  }

  private String createTokenForShop(long kolId, int quota, long shopId) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/admin-api/kol/copy-tokens")
                    .header(ADMIN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"kolId\":\""
                            + kolId
                            + "\",\"quota\":"
                            + quota
                            + ",\"validMinutes\":60,\"targetChannel\":\"WEIDIAN\",\"targetShopId\":\""
                            + shopId
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString())
        .path("data")
        .path("token")
        .asString();
  }

  private String createToken(long kolId, int quota) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/admin-api/kol/copy-tokens")
                    .header(ADMIN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"kolId\":\""
                            + kolId
                            + "\",\"quota\":"
                            + quota
                            + ",\"validMinutes\":60,\"targetChannel\":\"WEIDIAN\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString())
        .path("data")
        .path("token")
        .asString();
  }

  private void insertJob(
      long id,
      long tenantId,
      long kolId,
      long shopId,
      String jobCode,
      String status,
      int total,
      int success,
      int fail,
      String error,
      Instant createTime) {
    Timestamp at = Timestamp.from(createTime);
    jdbc.update(
        """
        INSERT INTO crm_copy_job(
          id, tenant_id, kol_id, token_id, job_code, caller_platform, target_shop_id,
          target_channel, status, total_count, success_count, fail_count, error_message,
          create_time, update_time, finish_time)
        VALUES (?, ?, ?, 1, ?, 'test', ?, 'WEIDIAN', ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        tenantId,
        kolId,
        jobCode,
        shopId,
        status,
        total,
        success,
        fail,
        error,
        at,
        at,
        "processing".equals(status) ? null : at);
  }

  private void insertItem(
      long id,
      long jobId,
      long tenantId,
      String sourceProductId,
      Long sourceShopId,
      String targetItemId,
      String status) {
    jdbc.update(
        """
        INSERT INTO crm_copy_job_item(
          id, job_id, tenant_id, source_channel, source_product_id, source_shop_id,
          status, target_item_id, update_time)
        VALUES (?, ?, ?, 'WEIDIAN', ?, ?, ?, ?, now())
        """,
        id,
        jobId,
        tenantId,
        sourceProductId,
        sourceShopId,
        status,
        targetItemId);
  }
}
