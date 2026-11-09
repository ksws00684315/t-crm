package com.tcrm.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.Permission;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Company editions (ADR-038, task 143 §2): the creator-library company reaches only the controllers
 * declared for it, the distribution company is unchanged, a platform administrator is not gated, a
 * CORS preflight still passes, one person switching companies switches editions, and every company
 * that existed before the migration is distribution.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class EditionIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";
  private static final String COMPANY = "X-Company-Id";

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
    r.add("crm.cors.allowed-origins", () -> "https://admin.example.com");
    WeidianTestProperties.register(r);
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private String platform;
  private String libraryCode;
  private String distributionCode;
  private CompanySessions.OpenedCompany library;
  private CompanySessions.OpenedCompany distribution;

  @Autowired
  EditionIT(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json, StringRedisTemplate redis) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
  }

  @BeforeEach
  void prepare() throws Exception {
    jdbc.update("DELETE FROM crm_library_kol_content");
    jdbc.update("DELETE FROM crm_library_kol");
    jdbc.update("DELETE FROM crm_kol_bd_transfer");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM sys_oper_log");
    jdbc.update("DELETE FROM sys_user_permission");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    jdbc.update("DELETE FROM sys_tenant WHERE id <> 1");
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
    platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    String suffix = suffix();
    libraryCode = "ed-b" + suffix;
    distributionCode = "ed-a" + suffix;
    library =
        CompanySessions.openCompany(
            mvc, json, PASSWORD, libraryCode, "ed-b-" + suffix, "KOL_LIBRARY");
    distribution =
        CompanySessions.openCompany(
            mvc, json, PASSWORD, distributionCode, "ed-a-" + suffix, "DISTRIBUTION");
  }

  /** Every controller the creator-library edition must not reach, one path each. */
  private static final List<String> DISTRIBUTION_ONLY =
      List.of(
          "/admin-api/kol/kols",
          "/admin-api/product/shops",
          "/admin-api/order/orders",
          "/admin-api/commission/withdrawals",
          "/admin-api/system/post-templates",
          "/admin-api/system/bd-users",
          "/admin-api/auth/resignation",
          "/admin-api/notifications",
          "/admin-api/system/oper-logs");

  private void expectEditionForbidden(String token, String path) throws Exception {
    var result = mvc.perform(get(path).header(TOKEN, token)).andReturn();
    assertThat(result.getResponse().getStatus()).as("%s is refused", path).isEqualTo(403);
    assertThat(json.readTree(result.getResponse().getContentAsString()).path("errorCode").asText())
        .as("%s error code", path)
        .isEqualTo("AUTH_EDITION_FORBIDDEN");
  }

  @Test
  void shouldRefuseDistributionControllers_inLibraryCompany() throws Exception {
    String token = library.token();
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.edition").value("KOL_LIBRARY"));
    for (String path : DISTRIBUTION_ONLY) {
      expectEditionForbidden(token, path);
    }
    // The library controller and the account screens are open.
    mvc.perform(get("/admin-api/kol-library/kols").header(TOKEN, token)).andExpect(status().isOk());
    mvc.perform(get("/admin-api/system/users").header(TOKEN, token)).andExpect(status().isOk());
    mvc.perform(get("/admin-api/system/permissions").header(TOKEN, token))
        .andExpect(status().isOk());
  }

  @Test
  void shouldRefuseTheLibrary_inDistributionCompany() throws Exception {
    String token = distribution.token();
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.edition").value("DISTRIBUTION"));
    expectEditionForbidden(token, "/admin-api/kol-library/kols");
    // The distribution product still has its own screens.
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token)).andExpect(status().isOk());
  }

  @Test
  void shouldNarrowSuperAdminCodesAndAssignment_inLibraryCompany() throws Exception {
    // A fresh login: the stored session middleware may hold the previous test's data.
    String token = CompanySessions.login(mvc, json, library.username(), PASSWORD);
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.permissions")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        "kol-library:view", "kol-library:edit")))
        .andExpect(jsonPath("$.data.permissions.length()").value(2));
    mvc.perform(get("/admin-api/system/permissions").header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissions.length()").value(2))
        .andExpect(jsonPath("$.data.templates.length()").value(0));
    long staff =
        CompanySessions.createStaff(
            mvc, json, token, PASSWORD, "lib-" + suffix(), null, null, List.of("kol-library:view"));
    mvc.perform(get("/admin-api/system/users/" + staff + "/permissions").header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissions[0]").value("kol-library:view"))
        .andExpect(jsonPath("$.data.dataScope").value("ALL"));
    // A distribution code is not assignable in the library company.
    mvc.perform(
            put("/admin-api/system/users/" + staff + "/permissions")
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("permissions", List.of("kol:view"), "dataScope", "ALL"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("USER_PERMISSION_EDITION_MISMATCH"));

    // The distribution company keeps every code for its super administrator.
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, distribution.token()))
        .andExpect(
            jsonPath("$.data.permissions.length()")
                .value(Permission.codesOf(Edition.DISTRIBUTION).size()));
    mvc.perform(get("/admin-api/system/permissions").header(TOKEN, distribution.token()))
        .andExpect(
            jsonPath("$.data.permissions.length()")
                .value(Permission.codesOf(Edition.DISTRIBUTION).size()))
        .andExpect(jsonPath("$.data.templates.length()").value(3));
  }

  @Test
  void shouldSwitchEdition_whenOnePersonBelongsToBothCompanies() throws Exception {
    String suffix = suffix();
    long staff =
        CompanySessions.createStaff(
            mvc,
            json,
            distribution.token(),
            PASSWORD,
            "both-" + suffix,
            "BD",
            "OWN",
            List.of("kol:view"));
    mvc.perform(
            post("/admin-api/platform/tenants/" + library.tenantId() + "/members")
                .header(TOKEN, platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", staff, "role", "STAFF"))))
        .andExpect(status().isOk());
    String token = CompanySessions.login(mvc, json, "both-" + suffix, PASSWORD);
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, token))
        .andExpect(jsonPath("$.data.edition").value("DISTRIBUTION"))
        .andExpect(
            jsonPath("$.data.companies[?(@.tenantId=='" + library.tenantId() + "')].edition")
                .value("KOL_LIBRARY"));
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token)).andExpect(status().isOk());

    mvc.perform(
            post("/admin-api/auth/company")
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + library.tenantId() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.edition").value("KOL_LIBRARY"));
    // The codes granted in the distribution company do not apply here.
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, token))
        .andExpect(jsonPath("$.data.permissions.length()").value(0));
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_EDITION_FORBIDDEN"));
    mvc.perform(get("/admin-api/kol-library/kols").header(TOKEN, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));

    mvc.perform(
            post("/admin-api/auth/company")
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + distribution.tenantId() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.edition").value("DISTRIBUTION"));
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token)).andExpect(status().isOk());
    mvc.perform(get("/admin-api/kol-library/kols").header(TOKEN, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_EDITION_FORBIDDEN"));
  }

  @Test
  void shouldNotGatePlatformEndpointsOrPreflight() throws Exception {
    // The platform administrator has no company and manages every company, whatever its edition.
    mvc.perform(get("/admin-api/platform/tenants").header(TOKEN, platform))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data[?(@.tenantCode=='" + libraryCode + "')].edition").value("KOL_LIBRARY"))
        .andExpect(
            jsonPath("$.data[?(@.tenantCode=='" + distributionCode + "')].edition")
                .value("DISTRIBUTION"));
    String origin = "https://admin.example.com";
    // A tokenless preflight still passes: it is answered before any interceptor looks at editions.
    mvc.perform(
            options("/admin-api/kol/kols")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", origin));
    mvc.perform(
            options("/admin-api/kol/kols")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-admin-token, x-company-id"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", origin));
  }

  @Test
  void shouldAnswer404_whenUnknownPathIsCalledByDistributionCompany() throws Exception {
    // An unmapped path is not a controller, so the edition check leaves the answer to the endpoint:
    // the known company account gets the plain 404 (a tokenless call is still refused with 401).
    mvc.perform(get("/admin-api/does-not-exist").header(TOKEN, distribution.token()))
        .andExpect(status().isNotFound());
  }

  @Test
  void shouldKeepEveryExistingCompanyAsDistribution_afterMigration() {
    assertThat(
            jdbc.queryForList("SELECT DISTINCT edition FROM sys_tenant WHERE id = 1", String.class))
        .containsExactly("DISTRIBUTION");
    assertThat(
            jdbc.queryForObject(
                "SELECT edition FROM sys_tenant WHERE tenant_code = ?",
                String.class,
                distributionCode))
        .isEqualTo("DISTRIBUTION");
    assertThat(
            jdbc.queryForObject(
                "SELECT edition FROM sys_tenant WHERE id = ?", String.class, library.tenantId()))
        .isEqualTo("KOL_LIBRARY");
  }

  @Test
  void shouldNotChangeEdition_whenCompanyIsEdited() throws Exception {
    mvc.perform(
            put("/admin-api/platform/tenants/" + library.tenantId())
                .header(TOKEN, platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"remark\":null,\"edition\":\"DISTRIBUTION\"}"))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT edition FROM sys_tenant WHERE id = ?", String.class, library.tenantId()))
        .isEqualTo("KOL_LIBRARY");
  }

  private static String suffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
