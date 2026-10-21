package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.PostTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Permission codes, user grants, and super-admin-only endpoints against PostgreSQL and Redis. */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PermissionIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";

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

  @Autowired
  PermissionIT(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @Test
  void shouldRejectWithoutTheCode_andAllowWithIt() throws Exception {
    String admin = companyAdmin();
    long productId = product(admin);
    List<Probe> probes =
        List.of(
            new Probe("kol:view", "GET", "/admin-api/kol/kols", null),
            new Probe("kol:edit", "GET", "/admin-api/kol/kol-imports/template", null),
            new Probe("product:view", "GET", "/admin-api/product/shops", null),
            new Probe("product:edit", "GET", "/admin-api/product/product-imports/template", null),
            new Probe(
                "product:status",
                "PUT",
                "/admin-api/product/products/" + productId + "/status",
                "{\"status\":0}"));
    String denied = staff(admin, "OPERATOR", "ALL", List.of());
    for (Probe probe : probes) {
      call(denied, probe.method(), probe.path(), probe.body())
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    }
    for (Probe probe : probes) {
      String allowed = staff(admin, "OPERATOR", "ALL", List.of(probe.code()));
      call(allowed, probe.method(), probe.path(), probe.body()).andExpect(status().isOk());
    }
  }

  @Test
  void shouldRejectSuperAdminEndpoints_whenStaffHoldsEveryCode() throws Exception {
    String admin = companyAdmin();
    String token = staff(admin, "OPERATOR", "ALL", Permission.codes());
    for (var request : superAdminOnly()) {
      mvc.perform(request.header(TOKEN, token))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    }
  }

  @Test
  void shouldRejectTheNextRequest_whenTheCodeIsRevoked() throws Exception {
    String admin = companyAdmin();
    String name = name();
    long id =
        CompanySessions.createStaff(
            mvc, json, admin, PASSWORD, name, "OPERATOR", "ALL", List.of("kol:view"));
    String token = CompanySessions.login(mvc, json, name, PASSWORD);
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token)).andExpect(status().isOk());
    mvc.perform(
            put("/admin-api/system/users/" + id + "/permissions")
                .header(TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[],\"dataScope\":\"ALL\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT request_params FROM sys_oper_log"
                                + " WHERE action = 'Replace permissions' AND request_uri LIKE ?"
                                + " ORDER BY oper_time DESC LIMIT 1",
                            String.class,
                            "%" + id + "%"))
                    .contains("previousPermissions")
                    .contains("kol:view"));
  }

  @Test
  void shouldGrantTemplateDefaults_andExplicitPermissions() throws Exception {
    String admin = companyAdmin();
    long templated =
        CompanySessions.createStaff(mvc, json, admin, PASSWORD, name(), "BD", null, null);
    assertThat(granted(admin, templated))
        .containsExactlyElementsOf(PostTemplate.BD.permissionCodes());
    assertThat(scope(admin, templated)).isEqualTo("OWN");

    long explicit =
        CompanySessions.createStaff(
            mvc,
            json,
            admin,
            PASSWORD,
            name(),
            "OPERATOR",
            "ALL",
            List.of("product:view", "kol:view"));
    assertThat(granted(admin, explicit)).containsExactly("kol:view", "product:view");
    assertThat(scope(admin, explicit)).isEqualTo("ALL");

    mvc.perform(get("/admin-api/system/permissions").header(TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissions[0].code").value("kol:view"))
        .andExpect(jsonPath("$.data.permissions[0].group").value("KOL"))
        .andExpect(jsonPath("$.data.permissions.length()").value(Permission.values().length))
        .andExpect(jsonPath("$.data.templates[0].code").value("OPERATOR"))
        .andExpect(jsonPath("$.data.templates[0].dataScope").value("ALL"))
        .andExpect(jsonPath("$.data.templates[1].code").value("BD"))
        .andExpect(jsonPath("$.data.templates[1].dataScope").value("OWN"));
  }

  @Test
  void shouldRejectBadCode_superAdminTarget_andCrossCompany() throws Exception {
    String admin = companyAdmin();
    long staff =
        CompanySessions.createStaff(
            mvc, json, admin, PASSWORD, name(), "OPERATOR", "ALL", List.of("kol:view"));
    mvc.perform(
            put("/admin-api/system/users/" + staff + "/permissions")
                .header(TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"nope\"],\"dataScope\":\"ALL\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("USER_PERMISSION_INVALID"));

    long adminId =
        jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'company-admin'", Long.class);
    mvc.perform(
            put("/admin-api/system/users/" + adminId + "/permissions")
                .header(TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"kol:view\"],\"dataScope\":\"ALL\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));

    String suffix = UUID.randomUUID().toString().substring(0, 8);
    var other = CompanySessions.openCompany(mvc, json, PASSWORD, "c" + suffix, "b" + suffix);
    mvc.perform(
            get("/admin-api/system/users/" + staff + "/permissions").header(TOKEN, other.token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));
    mvc.perform(
            put("/admin-api/system/users/" + staff + "/permissions")
                .header(TOKEN, other.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[],\"dataScope\":\"ALL\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));
  }

  @Test
  void shouldKeepOwnScopeRules_forKolsItemsAndLinks() throws Exception {
    String admin = companyAdmin();
    String bdName = name();
    long bd = CompanySessions.createStaff(mvc, json, admin, PASSWORD, bdName, "BD", null, null);
    String token = CompanySessions.login(mvc, json, bdName, PASSWORD);
    String own = kol(admin, "own-" + bdName + "@example.com", bd);
    String other = kol(admin, "other-" + bdName + "@example.com", null);

    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"));
    mvc.perform(get("/admin-api/product/kol-items").param("kolKeyword", own).header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(get("/admin-api/link/links").param("kolCode", own).header(TOKEN, token))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/link/links").param("kolCode", other).header(TOKEN, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
  }

  @Test
  void shouldRejectBusinessApis_whenCallerIsPlatformAdmin() throws Exception {
    String platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    for (String path :
        List.of(
            "/admin-api/kol/kols",
            "/admin-api/product/shops",
            "/admin-api/product/products",
            "/admin-api/integration/weidian/shops",
            "/admin-api/system/bd-users")) {
      mvc.perform(get(path).header(TOKEN, platform))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    }
  }

  private List<MockHttpServletRequestBuilder> superAdminOnly() {
    return List.of(
        put("/admin-api/kol/kols/1/bd-user")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bdUserId\":null}"),
        post("/admin-api/product/shops")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"shopName\":\"Nope\",\"weidianShopId\":\"1\"}"),
        put("/admin-api/product/shops/1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"shopName\":\"Nope\"}"),
        put("/admin-api/product/shops/1/status")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":0}"),
        post("/admin-api/link/index-rebuilds"),
        get("/admin-api/integration/weidian/app"),
        put("/admin-api/integration/weidian/app")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"),
        put("/admin-api/integration/weidian/shops/1/self-use")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"),
        post("/admin-api/integration/weidian/shops/1/authorizations"),
        post("/admin-api/integration/weidian/shops/1/checks"),
        get("/admin-api/system/users"),
        get("/admin-api/system/oper-logs"),
        get("/admin-api/system/permissions"));
  }

  private long product(String admin) throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/product/shops")
                    .header(TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"shopName\":\"Perm\",\"weidianShopId\":\"910001\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long shopId = Long.parseLong(json.readTree(body).at("/data/id").asText());
    jdbc.update(
        "INSERT INTO crm_product (id, product_code, shop_id, weidian_item_id, name, price,"
            + " status, create_time, update_time, is_deleted, tenant_id) VALUES (880001,"
            + " 'P880001', ?, '910001', 'Bag', 10.00, 1, now(), now(), FALSE, 1)",
        shopId);
    return 880001L;
  }

  private String kol(String admin, String email, Long bdUserId) throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("name", email);
    body.put("email", email);
    body.put("country", "US");
    if (bdUserId != null) {
      body.put("bdUserId", Long.toString(bdUserId));
    }
    String response =
        mvc.perform(
                post("/admin-api/kol/kols")
                    .header(TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).at("/data/kolCode").asText();
  }

  private String staff(String admin, String template, String scope, List<String> permissions)
      throws Exception {
    String username = name();
    CompanySessions.createStaff(mvc, json, admin, PASSWORD, username, template, scope, permissions);
    return CompanySessions.login(mvc, json, username, PASSWORD);
  }

  private org.springframework.test.web.servlet.ResultActions call(
      String token, String method, String path, String body) throws Exception {
    MockHttpServletRequestBuilder builder =
        switch (method) {
          case "GET" -> get(path);
          case "POST" -> post(path);
          case "PUT" -> put(path);
          default -> throw new IllegalArgumentException(method);
        };
    if (body != null) {
      builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mvc.perform(builder.header(TOKEN, token));
  }

  private List<String> granted(String admin, long userId) throws Exception {
    JsonNode node = json.readTree(permissions(admin, userId)).at("/data/permissions");
    List<String> codes = new ArrayList<>();
    node.forEach(item -> codes.add(item.asText()));
    return codes;
  }

  private String scope(String admin, long userId) throws Exception {
    return json.readTree(permissions(admin, userId)).at("/data/dataScope").asText();
  }

  private String permissions(String admin, long userId) throws Exception {
    return mvc.perform(
            get("/admin-api/system/users/" + userId + "/permissions").header(TOKEN, admin))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String companyAdmin() throws Exception {
    return CompanySessions.companyAdmin(mvc, json, PASSWORD);
  }

  private static String name() {
    return "p" + UUID.randomUUID().toString().substring(0, 8);
  }

  private record Probe(String code, String method, String path, String body) {}
}
