package com.tcrm.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.idev.excel.FastExcel;
import com.tcrm.admin.kol.service.KolImportColumn;
import com.tcrm.admin.link.service.ClickConsumer;
import com.tcrm.admin.link.service.ClickPartitionJob;
import com.tcrm.admin.link.service.ClickStream;
import com.tcrm.admin.link.service.LinkIndexService;
import com.tcrm.admin.link.service.LinkIndexSyncJob;
import com.tcrm.admin.product.service.MappingImportColumn;
import com.tcrm.admin.product.service.ProductImportColumn;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Company isolation for task 06c: each company sees and writes only its own rows. */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";
  private static final String IP_HASH = "ab".repeat(32);

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
  private final DataSource dataSource;
  private final LinkIndexSyncJob sync;
  private final LinkIndexService index;
  private final ClickConsumer clicks;
  private final ClickStream clickStream;
  private final ClickPartitionJob partitions;
  private final SysUserTenantMapper members;
  private final RequestMappingHandlerMapping mappings;

  private String tokenA;
  private String platform;
  private CompanySessions.OpenedCompany companyB;

  @Autowired
  TenantIsolationIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      StringRedisTemplate redis,
      DataSource dataSource,
      LinkIndexSyncJob sync,
      LinkIndexService index,
      ClickConsumer clicks,
      ClickStream clickStream,
      ClickPartitionJob partitions,
      SysUserTenantMapper members,
      @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.dataSource = dataSource;
    this.sync = sync;
    this.index = index;
    this.clicks = clicks;
    this.clickStream = clickStream;
    this.partitions = partitions;
    this.members = members;
    this.mappings = mappings;
  }

  @BeforeEach
  void prepareCompanies() throws Exception {
    jdbc.update("DELETE FROM crm_link_index_outbox");
    jdbc.update("DELETE FROM crm_click_log");
    jdbc.update("DELETE FROM crm_kol_item");
    jdbc.update("DELETE FROM crm_product");
    jdbc.update("DELETE FROM crm_shop");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM crm_task_file");
    jdbc.update("DELETE FROM crm_task");
    jdbc.update("DELETE FROM sys_oper_log");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    jdbc.update("DELETE FROM sys_tenant WHERE id <> 1");
    Set<String> keys = redis.keys("link:*");
    keys.addAll(redis.keys("click:last:*"));
    keys.add("stream:click");
    redis.delete(keys);
    sync.setScheduled(false);
    tokenA = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    companyB = CompanySessions.openCompany(mvc, json, PASSWORD, "b" + suffix, "adm" + suffix);
  }

  @Test
  void shouldHideOtherCompanyRows_whenListingAndReadingById() throws Exception {
    long shopB = createShop(companyB.token(), "ShopB", "61001");
    Kol kolB = createKol(companyB.token(), "Bee", "bee@example.com");
    String productB = importProduct(companyB.token(), shopB, "71001", "Hat", "");
    long productIdB = id("SELECT id FROM crm_product WHERE weidian_item_id = '71001'");
    String taskId = importMapping(companyB.token(), "61001", "81001", kolB.code(), productB);
    long userB = companyB.userId();

    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(get("/admin-api/product/products").header(TOKEN, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(get("/admin-api/product/shops").header(TOKEN, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
    mvc.perform(
            get("/admin-api/product/kol-items")
                .param("weidianItemId", "81001")
                .header(TOKEN, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(get("/admin-api/system/users").header(TOKEN, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"));
    String logs =
        mvc.perform(get("/admin-api/system/oper-logs").header(TOKEN, tokenA))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(logs).doesNotContain("61001");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_oper_log WHERE tenant_id = ?",
                Integer.class,
                companyB.tenantId()))
        .isPositive();

    expectNotFound(get("/admin-api/kol/kols/" + kolB.id()));
    expectNotFound(putJson("/admin-api/kol/kols/" + kolB.id(), kolEdit()));
    expectNotFound(putJson("/admin-api/kol/kols/" + kolB.id() + "/status", Map.of("status", 0)));
    expectNotFound(get("/admin-api/product/products/" + productIdB));
    expectNotFound(putJson("/admin-api/product/products/" + productIdB, productEdit()));
    expectNotFound(
        putJson("/admin-api/product/products/" + productIdB + "/status", Map.of("status", 0)));
    expectNotFound(putJson("/admin-api/product/shops/" + shopB, Map.of("shopName", "Nope")));
    expectNotFound(putJson("/admin-api/product/shops/" + shopB + "/status", Map.of("status", 0)));
    expectNotFound(putJson("/admin-api/system/users/" + userB, userEdit()));
    expectNotFound(putJson("/admin-api/system/users/" + userB + "/status", Map.of("status", 0)));
    expectNotFound(get("/admin-api/task/tasks/" + taskId));
    expectNotFound(get("/admin-api/task/tasks/" + taskId + "/error-file"));
    mvc.perform(get("/admin-api/link/links").param("kolCode", kolB.code()).header(TOKEN, tokenA))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));

    long shopA = createShop(tokenA, "ShopA", "61002");
    Kol kolA = createKol(tokenA, "Ada", "ada@example.com");
    String productA = importProduct(tokenA, shopA, "71002", "Bag", "");
    String missingKol = importMapping(tokenA, "61002", "81002", kolB.code(), productA);
    String missingProduct = importMapping(tokenA, "61002", "81003", kolA.code(), productB);
    assertThat(errorText(tokenA, missingKol)).contains("达人编号不存在或已停用");
    assertThat(errorText(tokenA, missingProduct)).contains("原商品编码不存在");
  }

  @Test
  void shouldShareEmailAndProductCode_andRejectGlobalKolCodeAndShopId() throws Exception {
    createKol(tokenA, "Ada", "shared@example.com");
    createKol(companyB.token(), "Bee", "shared@example.com");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_kol WHERE lower(email) = 'shared@example.com'",
                Integer.class))
        .isEqualTo(2);

    long shopA = createShop(tokenA, "ShopA", "62001");
    long shopB = createShop(companyB.token(), "ShopB", "62002");
    importProduct(tokenA, shopA, "72001", "Bag", "SHARED1");
    importProduct(companyB.token(), shopB, "72002", "Hat", "SHARED1");
    assertThat(
            jdbc.queryForList(
                "SELECT tenant_id FROM crm_product WHERE product_code = 'SHARED1' ORDER BY tenant_id",
                Long.class))
        .containsExactly(1L, companyB.tenantId());

    Kol kolA = createKol(tokenA, "Code", "code-a@example.com");
    String taken = importKol(companyB.token(), kolA.code(), "Other", "code-b@example.com");
    assertThat(errorText(companyB.token(), taken)).contains("达人编号已存在");

    mvc.perform(
            post("/admin-api/product/shops")
                .header(TOKEN, tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopBody("Again", "62001")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("SHOP_WEIDIAN_ID_EXISTS"));
    mvc.perform(
            post("/admin-api/product/shops")
                .header(TOKEN, companyB.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopBody("Borrowed", "62001")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("SHOP_WEIDIAN_ID_EXISTS"));

    createShop(tokenA, "Two", "62003");
    createShop(tokenA, "Three", "62004");
    mvc.perform(
            post("/admin-api/product/shops")
                .header(TOKEN, tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopBody("Four", "62005")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("SHOP_LIMIT_REACHED"));
    createShop(companyB.token(), "BeeShop", "62005");

    mvc.perform(
            post("/admin-api/system/users")
                .header(TOKEN, companyB.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(userBody(CompanySessions.COMPANY_ADMIN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("USER_USERNAME_EXISTS"));
  }

  @Test
  void shouldAttributeImportClicksAndRebuild_toEachCompany() throws Exception {
    long shopA = createShop(tokenA, "ShopA", "63001");
    long shopB = createShop(companyB.token(), "ShopB", "63002");
    Kol kolA = createKol(tokenA, "Ada", "ada-link@example.com");
    Kol kolB = createKol(companyB.token(), "Bee", "bee-link@example.com");
    String productA = importProduct(tokenA, shopA, "73001", "Bag", "");
    String productB = importProduct(companyB.token(), shopB, "73002", "Hat", "");
    assertThat(tenantOf("crm_product", "weidian_item_id", "73002")).isEqualTo(companyB.tenantId());
    importMapping(tokenA, "63001", "83001", kolA.code(), productA);
    importMapping(companyB.token(), "63002", "83002", kolB.code(), productB);
    assertThat(tenantOf("crm_kol_item", "weidian_item_id", "83002")).isEqualTo(companyB.tenantId());
    assertThat(tenantOf("crm_task", "id", taskIdOf(companyB.tenantId())))
        .isEqualTo(companyB.tenantId());

    sync.drain();
    TenantScope.runSystem("rebuild link index for every company", index::rebuildAll);
    assertThat(field(kolA.code(), productA)).contains("targetUrl").contains("\"status\":1");
    assertThat(field(kolB.code(), productB)).contains("targetUrl").contains("\"status\":1");

    partitions.maintain(LocalDate.now(ZoneOffset.UTC));
    clickStream.ensureGroup();
    long itemA = id("SELECT id FROM crm_kol_item WHERE weidian_item_id = '83001'");
    long itemB = id("SELECT id FROM crm_kol_item WHERE weidian_item_id = '83002'");
    appendClick(itemA);
    appendClick(itemB);
    assertThat(clicks.pollOnce()).isEqualTo(2);
    assertThat(tenantOf("crm_click_log", "kol_item_id", itemA)).isEqualTo(1L);
    assertThat(tenantOf("crm_click_log", "kol_item_id", itemB)).isEqualTo(companyB.tenantId());
  }

  @Test
  void shouldForbidTheWrongRole_andValidatePlatformRequests() throws Exception {
    Set<String> business = new HashSet<>();
    Set<String> platformPaths = new HashSet<>();
    Map<String, String> contentTypes = new HashMap<>();
    for (var info : mappings.getHandlerMethods().keySet()) {
      for (String pattern : info.getPatternValues()) {
        if (!pattern.startsWith("/admin-api/")) {
          continue;
        }
        String path = pattern.replaceAll("\\{[^}/]+\\}", "1");
        for (RequestMethod method : info.getMethodsCondition().getMethods()) {
          String key = method + " " + path;
          var types = info.getConsumesCondition().getConsumableMediaTypes();
          if (!types.isEmpty()) {
            contentTypes.put(key, types.iterator().next().toString());
          }
          if (path.startsWith("/admin-api/platform/")) {
            platformPaths.add(key);
          } else if (!authAllowed(path)) {
            business.add(key);
          }
        }
      }
    }
    assertThat(business).contains("GET /admin-api/kol/kols", "POST /admin-api/link/index-rebuilds");
    assertThat(platformPaths).contains("GET /admin-api/platform/tenants");
    for (String key : business) {
      perform(key, platform, contentTypes.get(key))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    }
    mvc.perform(get("/admin-api/system/oper-logs").header(TOKEN, platform))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    for (String key : platformPaths) {
      MvcResult result = perform(key, tokenA, null).andReturn();
      assertThat(result.getResponse().getStatus()).as("platform endpoint %s", key).isEqualTo(403);
      assertThat(
              json.readTree(result.getResponse().getContentAsString()).path("errorCode").asText())
          .as("platform endpoint %s error code", key)
          .isEqualTo("AUTH_FORBIDDEN");
    }

    mvc.perform(
            post("/admin-api/platform/tenants")
                .header(TOKEN, platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantCode\":\"A\",\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.data[?(@.field=='tenantCode')]").exists())
        .andExpect(jsonPath("$.data[?(@.field=='name')]").exists());
    mvc.perform(
            post("/admin-api/system/users")
                .header(TOKEN, tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username",
                            "plat-user",
                            "password",
                            PASSWORD,
                            "nickname",
                            "Plat",
                            "role",
                            "PLATFORM_ADMIN",
                            "dataScope",
                            "ALL"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.data[?(@.field=='role')]").exists());
  }

  @Test
  void shouldDisableCompanyLogin_withoutRemovingRedirectData() throws Exception {
    long shopB = createShop(companyB.token(), "ShopB", "64001");
    Kol kolB = createKol(companyB.token(), "Bee", "bee-off@example.com");
    String productB = importProduct(companyB.token(), shopB, "74001", "Hat", "");
    importMapping(companyB.token(), "64001", "84001", kolB.code(), productB);
    sync.drain();
    String link = field(kolB.code(), productB);
    assertThat(link).contains("targetUrl").contains("\"status\":1");

    String wrong =
        mvc.perform(
                post("/admin-api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(companyB.username(), "wrong-password-1")))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            put("/admin-api/platform/tenants/" + companyB.tenantId() + "/status")
                .header(TOKEN, platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, companyB.token()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_DISABLED"));
    // Task 21a §4.3 as revised after PR #65: with no usable company left, the correct password
    // gets the same answer as a wrong one (401 AUTH_INVALID_CREDENTIALS, no token).
    String disabled =
        mvc.perform(
                post("/admin-api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(companyB.username(), PASSWORD)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(withoutTrace(disabled)).isEqualTo(withoutTrace(wrong));
    assertThat(disabled).doesNotContain("token");
    assertThat(field(kolB.code(), productB)).isEqualTo(link);

    mvc.perform(
            put("/admin-api/platform/tenants/" + companyB.tenantId() + "/status")
                .header(TOKEN, platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":1}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/admin-api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(companyB.username(), PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.user.tenantId").value(Long.toString(companyB.tenantId())));
  }

  @Test
  void shouldKeepLastSuperAdminInsideTheCompany() throws Exception {
    Long adminA =
        jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'company-admin'", Long.class);
    List<Long> locked = TenantScope.callCompany(1L, () -> members.lockEnabledSuperAdmins(1L));
    assertThat(locked).contains(adminA).doesNotContain(companyB.userId());

    String otherName = "second-" + UUID.randomUUID().toString().substring(0, 8);
    long other = createUser(tokenA, otherName, "SUPER_ADMIN");
    String otherToken = CompanySessions.login(mvc, json, otherName, PASSWORD);
    Map<String, String> demote =
        Map.of(
            "nickname", "Demoted", "role", "STAFF", "postTemplate", "OPERATOR", "dataScope", "ALL");
    int[] codes = new int[2];
    runAfterQueuedOnRowLocks(
        List.of(adminA, other),
        () -> codes[0] = putStatus(tokenA, "/admin-api/system/users/" + other, demote),
        () -> codes[1] = putStatus(otherToken, "/admin-api/system/users/" + adminA, demote));
    assertThat(codes).containsExactlyInAnyOrder(200, 422);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_tenant WHERE role = 'SUPER_ADMIN' AND status = 1"
                    + " AND tenant_id = 1 AND is_deleted = FALSE",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM sys_user_tenant WHERE user_id = ? AND tenant_id = ?",
                String.class,
                companyB.userId(),
                companyB.tenantId()))
        .isEqualTo("SUPER_ADMIN");
  }

  @Test
  void shouldInvalidateOnlyTheResetAdministrator() throws Exception {
    String fresh = UUID.randomUUID().toString();
    mvc.perform(
            put("/admin-api/platform/tenants/"
                    + companyB.tenantId()
                    + "/admins/"
                    + companyB.userId()
                    + "/password")
                .header(TOKEN, platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", fresh))))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, companyB.token()))
        .andExpect(status().isUnauthorized());
    Long adminA =
        jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'company-admin'", Long.class);
    mvc.perform(
            put("/admin-api/platform/tenants/"
                    + companyB.tenantId()
                    + "/admins/"
                    + adminA
                    + "/password")
                .header(TOKEN, platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", fresh))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));
    mvc.perform(
            post("/admin-api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(companyB.username(), fresh)))
        .andExpect(status().isOk());
  }

  private org.springframework.test.web.servlet.ResultActions perform(
      String key, String token, String contentType) throws Exception {
    int space = key.indexOf(' ');
    HttpMethod method = HttpMethod.valueOf(key.substring(0, space));
    String path = key.substring(space + 1);
    var request = request(method, path).header(TOKEN, token);
    if (contentType != null) {
      request.contentType(contentType);
    }
    return mvc.perform(request);
  }

  private static boolean authAllowed(String path) {
    return path.equals("/admin-api/auth/login")
        || path.equals("/admin-api/auth/captchas")
        || path.equals("/admin-api/auth/logout")
        || path.equals("/admin-api/auth/me")
        || path.equals("/admin-api/auth/password")
        || path.equals("/admin-api/public/site-config")
        || path.equals("/admin-api/system/oper-logs")
        || path.equals("/admin-api/integration/weidian/oauth/callback")
        || path.startsWith("/admin-api/external/");
  }

  private void expectNotFound(org.springframework.test.web.servlet.RequestBuilder request)
      throws Exception {
    mvc.perform(withToken(request)).andExpect(status().isNotFound());
  }

  private org.springframework.test.web.servlet.RequestBuilder withToken(
      org.springframework.test.web.servlet.RequestBuilder request) {
    if (request
        instanceof
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
      return builder.header(TOKEN, tokenA);
    }
    throw new IllegalArgumentException("unsupported request");
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putJson(
      String path, Map<String, ?> body) throws Exception {
    return put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
  }

  private Map<String, String> kolEdit() {
    return Map.of("name", "Nope", "email", "nope@example.com", "country", "US");
  }

  private Map<String, String> productEdit() {
    return Map.of("name", "Nope", "price", "1.00");
  }

  private Map<String, String> userEdit() {
    return Map.of("nickname", "Nope", "role", "STAFF", "postTemplate", "BD", "dataScope", "OWN");
  }

  private String shopBody(String name, String weidianId) throws Exception {
    return json.writeValueAsString(Map.of("shopName", name, "weidianShopId", weidianId));
  }

  private String userBody(String username) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "username",
            username,
            "password",
            PASSWORD,
            "nickname",
            username,
            "role",
            "STAFF",
            "postTemplate",
            "BD",
            "dataScope",
            "OWN"));
  }

  private String loginBody(String username, String password) throws Exception {
    return json.writeValueAsString(Captchas.loginBody(mvc, json, username, password));
  }

  private String withoutTrace(String body) throws Exception {
    ObjectNode node = (ObjectNode) json.readTree(body);
    node.remove("traceId");
    return node.toString();
  }

  private long createShop(String token, String name, String weidianId) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/admin-api/product/shops")
                    .header(TOKEN, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(shopBody(name, weidianId)))
            .andExpect(status().isOk())
            .andReturn();
    return Long.parseLong(
        json.readTree(result.getResponse().getContentAsString()).at("/data/id").asText());
  }

  private Kol createKol(String token, String name, String email) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/admin-api/kol/kols")
                    .header(TOKEN, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("name", name, "email", email, "country", "US"))))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
    return new Kol(Long.parseLong(data.path("id").asText()), data.path("kolCode").asText());
  }

  private long createUser(String token, String username, String role) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/admin-api/system/users")
                    .header(TOKEN, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "username",
                                username,
                                "password",
                                PASSWORD,
                                "nickname",
                                username,
                                "role",
                                role,
                                "dataScope",
                                "ALL"))))
            .andExpect(status().isOk())
            .andReturn();
    return Long.parseLong(
        json.readTree(result.getResponse().getContentAsString()).at("/data/id").asText());
  }

  private String importProduct(String token, long shopId, String itemId, String name, String code)
      throws Exception {
    String weidianShopId =
        jdbc.queryForObject(
            "SELECT weidian_shop_id FROM crm_shop WHERE id = ?", String.class, shopId);
    byte[] workbook =
        workbook(
            com.tcrm.admin.TestTemplates.render(ProductImportColumn.headerKeys()),
            List.of(List.of(code, weidianShopId, itemId, name, "", "", "10.00", "")));
    awaitTask(token, submit(token, "/admin-api/product/product-imports", workbook));
    return jdbc.queryForObject(
        "SELECT product_code FROM crm_product WHERE weidian_item_id = ?", String.class, itemId);
  }

  private String importMapping(String token, String shop, String item, String kol, String product)
      throws Exception {
    byte[] workbook =
        workbook(
            com.tcrm.admin.TestTemplates.render(MappingImportColumn.headerKeys()),
            List.of(List.of(shop, item, kol, product)));
    return awaitTask(token, submit(token, "/admin-api/product/kol-item-imports", workbook))
        .path("id")
        .asText();
  }

  private String importKol(String token, String code, String name, String email) throws Exception {
    byte[] workbook =
        workbook(
            com.tcrm.admin.TestTemplates.render(KolImportColumn.headerKeys()),
            List.of(List.of(code, name, email, "US", "", "", "", "", "", "")));
    return awaitTask(token, submit(token, "/admin-api/kol/kol-imports", workbook))
        .path("id")
        .asText();
  }

  private String submit(String token, String path, byte[] workbook) throws Exception {
    MvcResult submit =
        mvc.perform(
                multipart(path)
                    .file(
                        new MockMultipartFile(
                            "file", "f.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, workbook))
                    .header("Accept-Language", "zh-CN")
                    .header(TOKEN, token))
            .andExpect(status().isAccepted())
            .andReturn();
    return json.readTree(submit.getResponse().getContentAsString()).at("/data/taskId").asText();
  }

  private JsonNode awaitTask(String token, String taskId) throws Exception {
    JsonNode[] holder = new JsonNode[1];
    TestWait.until(
        java.time.Duration.ofSeconds(30),
        java.time.Duration.ofMillis(100),
        () -> {
          try {
            MvcResult result =
                mvc.perform(get("/admin-api/task/tasks/" + taskId).header(TOKEN, token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
            String state = data.path("status").asText();
            if ("SUCCESS".equals(state) || "PARTIAL".equals(state) || "FAILED".equals(state)) {
              holder[0] = data;
              return true;
            }
            return false;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        },
        "task " + taskId + " did not finish");
    return holder[0];
  }

  private String errorText(String token, String taskId) throws Exception {
    byte[] errors =
        mvc.perform(get("/admin-api/task/tasks/" + taskId + "/error-file").header(TOKEN, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    List<Map<Integer, String>> sheet =
        FastExcel.read(new ByteArrayInputStream(errors)).sheet(0).headRowNumber(0).doReadSync();
    StringBuilder text = new StringBuilder();
    for (Map<Integer, String> row : sheet) {
      text.append(row.values());
    }
    return text.toString();
  }

  private static byte[] workbook(List<String> headers, List<List<String>> rows) {
    List<List<String>> head = headers.stream().map(List::of).toList();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out).head(head).sheet("Sheet1").doWrite(rows);
    return out.toByteArray();
  }

  private void appendClick(long kolItemId) {
    Map<String, String> event = new HashMap<>();
    event.put("kolItemId", Long.toString(kolItemId));
    event.put("ts", Long.toString(System.currentTimeMillis()));
    event.put("ipHash", IP_HASH);
    event.put("ua", "Mozilla/5.0");
    event.put("ref", "");
    event.put("country", "US");
    redis.opsForStream().add(MapRecord.create("stream:click", event));
  }

  private String field(String kolCode, String productCode) {
    Object value = redis.opsForHash().get("link:" + kolCode, productCode);
    return value == null ? null : value.toString();
  }

  private long id(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  private long tenantOf(String table, String column, Object value) {
    return jdbc.queryForObject(
        "SELECT tenant_id FROM " + table + " WHERE " + column + " = ?", Long.class, value);
  }

  private long taskIdOf(long tenantId) {
    return jdbc.queryForObject(
        "SELECT id FROM crm_task WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
        Long.class,
        tenantId);
  }

  private int putStatus(String token, String path, Map<String, ?> body) throws Exception {
    return mvc.perform(
            put(path)
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private void runAfterQueuedOnRowLocks(List<Long> userIds, Callable<?> first, Callable<?> second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (var lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      for (Long userId : userIds) {
        // The account row (name) and its membership rows (role, status).
        for (String sql :
            List.of(
                "SELECT id FROM sys_user WHERE id=? FOR UPDATE",
                "SELECT id FROM sys_user_tenant WHERE user_id=? FOR UPDATE")) {
          try (var statement = lock.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.executeQuery().close();
          }
        }
      }
      Future<?> firstDone = pool.submit(first);
      awaitLockWaiters(1);
      Future<?> secondDone = pool.submit(second);
      awaitLockWaiters(2);
      lock.commit();
      firstDone.get();
      secondDone.get();
    } finally {
      pool.shutdownNow();
    }
  }

  private void awaitLockWaiters(int expected) {
    TestWait.until(
        java.time.Duration.ofSeconds(5),
        java.time.Duration.ofMillis(25),
        () -> {
          Integer waiting =
              jdbc.queryForObject("SELECT COUNT(*) FROM pg_locks WHERE NOT granted", Integer.class);
          return waiting != null && waiting >= expected;
        },
        "Expected " + expected + " requests waiting for the row lock");
  }

  private record Kol(long id, String code) {}
}
