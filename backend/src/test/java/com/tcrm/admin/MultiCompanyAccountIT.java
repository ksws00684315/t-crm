package com.tcrm.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.PostTemplate;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 21a (ADR-022): one account in two companies with a different role in each, the current
 * company bound to the token session, switching, the {@code X-Company-Id} check, per-company
 * account management, and the platform account endpoints.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class MultiCompanyAccountIT {
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
    WeidianTestProperties.register(r);
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;

  private String adminA;
  private String platform;
  private CompanySessions.OpenedCompany companyB;
  private String multiName;
  private long multiId;

  @Autowired
  MultiCompanyAccountIT(
      MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json, StringRedisTemplate redis) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
  }

  /**
   * Company A is the default company (id 1); company B is new and sorts first by name. {@code
   * multi} is a super administrator in A and a BD staff member in B.
   */
  @BeforeEach
  void prepareCompanies() throws Exception {
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
    adminA = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    companyB = CompanySessions.openCompany(mvc, json, PASSWORD, "b" + suffix, "adm" + suffix);
    multiName = "multi" + suffix;
    multiId = createSuperAdmin(adminA, multiName);
    addMember(companyB.tenantId(), multiId, "STAFF", "BD").andExpect(status().isOk());
  }

  @Test
  void shouldSwitchRoleCodesAndData_betweenTheTwoCompaniesOfOneAccount() throws Exception {
    String token = CompanySessions.login(mvc, json, multiName, PASSWORD);
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantId").value("1"))
        .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"))
        .andExpect(jsonPath("$.data.companies.length()").value(2))
        .andExpect(jsonPath("$.data.companies[0].tenantId").value(id(companyB.tenantId())))
        .andExpect(jsonPath("$.data.companies[0].role").value("STAFF"))
        .andExpect(jsonPath("$.data.companies[1].tenantId").value("1"))
        .andExpect(jsonPath("$.data.companies[1].role").value("SUPER_ADMIN"));
    createKol(token, null, "Ada", "ada@example.com").andExpect(status().isOk());

    switchTo(token, companyB.tenantId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantId").value(id(companyB.tenantId())))
        .andExpect(jsonPath("$.data.role").value("STAFF"))
        .andExpect(jsonPath("$.data.dataScope").value("OWN"))
        .andExpect(
            jsonPath("$.data.permissions.length()").value(PostTemplate.BD.permissionCodes().size()))
        .andExpect(jsonPath("$.data.companies.length()").value(2));
    // A second tab uses the same token, so it is in company B as well.
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, token))
        .andExpect(jsonPath("$.data.tenantId").value(id(companyB.tenantId())));
    createKol(token, companyB.tenantId(), "Bee", "bee@example.com").andExpect(status().isOk());
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].name").value("Bee"));
    mvc.perform(get("/admin-api/system/users").header(TOKEN, token))
        .andExpect(status().isForbidden());
    assertThat(jdbc.queryForObject("SELECT bd_user_id FROM crm_kol WHERE name = 'Bee'", Long.class))
        .isEqualTo(multiId);

    switchTo(token, 1L)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"));
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token))
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].name").value("Ada"));
    assertThat(
            jdbc.queryForList(
                "SELECT tenant_id || ':' || name FROM crm_kol ORDER BY name", String.class))
        .containsExactly("1:Ada", companyB.tenantId() + ":Bee");
    assertThat(
            jdbc.queryForObject(
                "SELECT last_tenant_id FROM sys_user WHERE id = ?", Long.class, multiId))
        .isEqualTo(1L);

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForList(
                            "SELECT tenant_id || ':' || request_params FROM sys_oper_log"
                                + " WHERE action = 'Switch company' AND user_id = ?"
                                + " ORDER BY oper_time",
                            String.class,
                            multiId))
                    .hasSize(2)
                    .satisfies(
                        rows -> {
                          assertThat(rows.get(0))
                              .startsWith(companyB.tenantId() + ":")
                              .contains("\"fromTenantId\"");
                          assertThat(rows.get(1))
                              .startsWith("1:")
                              .contains(Long.toString(companyB.tenantId()));
                        }));
  }

  @Test
  void shouldRejectMismatchedDisplayedCompany_withoutRunningTheRequest() throws Exception {
    String token = CompanySessions.login(mvc, json, multiName, PASSWORD);
    switchTo(token, companyB.tenantId()).andExpect(status().isOk());

    createKol(token, 1L, "Cee", "cee@example.com")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("AUTH_COMPANY_SWITCHED"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM crm_kol", Integer.class)).isZero();
    // The header only confirms: it cannot be used to read company A while the token is in B.
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token).header(COMPANY, "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("AUTH_COMPANY_SWITCHED"));
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token).header(COMPANY, "not-a-number"))
        .andExpect(status().isConflict());
    mvc.perform(
            get("/admin-api/kol/kols")
                .header(TOKEN, token)
                .header(COMPANY, Long.toString(companyB.tenantId())))
        .andExpect(status().isOk());
    // Without the header the request is not checked; auth endpoints are never checked.
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, token)).andExpect(status().isOk());
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, token).header(COMPANY, "1"))
        .andExpect(status().isOk());
  }

  @Test
  void shouldRejectSwitch_toAForeignCompanyOrForPlatformAdministrator() throws Exception {
    String token = CompanySessions.login(mvc, json, multiName, PASSWORD);
    var other = CompanySessions.openCompany(mvc, json, PASSWORD, "c" + suffix(), "c" + suffix());
    switchTo(token, other.tenantId())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_COMPANY_NOT_MEMBER"));
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, token))
        .andExpect(jsonPath("$.data.tenantId").value("1"));
    switchTo(platform, 1L)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, platform))
        .andExpect(jsonPath("$.data.companies.length()").value(0));
    mvc.perform(get("/admin-api/kol/kols").header(TOKEN, platform))
        .andExpect(status().isForbidden());

    // A disabled membership is not a usable company either.
    jdbc.update(
        "UPDATE sys_user_tenant SET status = 0 WHERE user_id = ? AND tenant_id = ?",
        multiId,
        companyB.tenantId());
    switchTo(token, companyB.tenantId())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_COMPANY_NOT_MEMBER"));
  }

  @Test
  void shouldManageMembershipsPerCompany_andLockPersonalFieldsOfMultiCompanyAccounts()
      throws Exception {
    mvc.perform(get("/admin-api/system/users").header(TOKEN, adminA).param("username", multiName))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].companyCount").value(2))
        .andExpect(jsonPath("$.data.items[0].role").value("SUPER_ADMIN"));
    mvc.perform(get("/admin-api/system/users").header(TOKEN, companyB.token()))
        .andExpect(jsonPath("$.data.total").value("2"));

    editUser(adminA, multiId, "Renamed", "SUPER_ADMIN")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("USER_MULTI_COMPANY_LOCKED"));
    putJson(
            adminA,
            "/admin-api/system/users/" + multiId + "/password",
            Map.of("password", PASSWORD + "x"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("USER_MULTI_COMPANY_LOCKED"));
    // Role changes stay possible while name and e-mail are sent unchanged.
    editUser(adminA, multiId, multiName, "SUPER_ADMIN").andExpect(status().isOk());
    // A company super administrator cannot pull an existing account into its company.
    postJson(companyB.token(), "/admin-api/system/users", newUser(multiName, "STAFF"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("USER_USERNAME_EXISTS"));

    String inA = CompanySessions.login(mvc, json, multiName, PASSWORD);
    String inB = CompanySessions.login(mvc, json, multiName, PASSWORD);
    switchTo(inB, companyB.tenantId()).andExpect(status().isOk());
    putJson(companyB.token(), "/admin-api/system/users/" + multiId + "/status", Map.of("status", 0))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, inB)).andExpect(status().isUnauthorized());
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, inA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantId").value("1"))
        .andExpect(jsonPath("$.data.companies.length()").value(1));
    assertThat(
            jdbc.queryForObject("SELECT status FROM sys_user WHERE id = ?", Integer.class, multiId))
        .isEqualTo(1);
    // The last company (B) is no longer usable, so the next login enters A.
    String again = CompanySessions.login(mvc, json, multiName, PASSWORD);
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, again))
        .andExpect(jsonPath("$.data.tenantId").value("1"));
  }

  @Test
  void shouldJudgeBdsAndUnassignKolsPerCompany_whenStaffIsDisabledInOneCompany() throws Exception {
    String bdName = "bd" + suffix();
    long bd = CompanySessions.createStaff(mvc, json, adminA, PASSWORD, bdName, "BD", null, null);
    addMember(companyB.tenantId(), bd, "STAFF", "BD").andExpect(status().isOk());
    assertThat(
            jdbc.queryForList(
                "SELECT permission_code FROM sys_user_permission WHERE user_id = ? AND tenant_id = ?",
                String.class,
                bd,
                companyB.tenantId()))
        .containsExactlyInAnyOrderElementsOf(PostTemplate.BD.permissionCodes());
    String token = CompanySessions.login(mvc, json, bdName, PASSWORD);
    createKol(token, null, "Kay", "kay@example.com").andExpect(status().isOk());
    switchTo(token, companyB.tenantId()).andExpect(status().isOk());
    createKol(token, null, "Kim", "kim@example.com").andExpect(status().isOk());
    mvc.perform(get("/admin-api/system/bd-users").header(TOKEN, companyB.token()))
        .andExpect(jsonPath("$.data[?(@.username=='" + bdName + "')]").exists());

    putJson(companyB.token(), "/admin-api/system/users/" + bd + "/status", Map.of("status", 0))
        .andExpect(status().isOk());

    assertThat(jdbc.queryForObject("SELECT bd_user_id FROM crm_kol WHERE name = 'Kim'", Long.class))
        .isNull();
    assertThat(jdbc.queryForObject("SELECT bd_user_id FROM crm_kol WHERE name = 'Kay'", Long.class))
        .isEqualTo(bd);
    mvc.perform(get("/admin-api/system/bd-users").header(TOKEN, companyB.token()))
        .andExpect(jsonPath("$.data[?(@.username=='" + bdName + "')]").doesNotExist());
    mvc.perform(get("/admin-api/system/bd-users").header(TOKEN, adminA))
        .andExpect(jsonPath("$.data[?(@.username=='" + bdName + "')]").exists());
  }

  @Test
  void shouldLookUpExactlyAndManageWholeAccounts_whenPlatformAdministrator() throws Exception {
    mvc.perform(
            get("/admin-api/platform/users")
                .header(TOKEN, platform)
                .param("username", multiName.toUpperCase(java.util.Locale.ROOT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(id(multiId)))
        .andExpect(jsonPath("$.data.status").value(1))
        .andExpect(jsonPath("$.data.companies.length()").value(2))
        .andExpect(jsonPath("$.data.companies[0].role").value("STAFF"))
        .andExpect(jsonPath("$.data.companies[0].status").value(1))
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    mvc.perform(
            get("/admin-api/platform/users")
                .header(TOKEN, platform)
                .param("username", multiName.substring(0, 6)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").doesNotExist());
    mvc.perform(get("/admin-api/platform/users").header(TOKEN, adminA).param("username", multiName))
        .andExpect(status().isForbidden());
    addMember(companyB.tenantId(), multiId, "SUPER_ADMIN", null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("USER_ALREADY_MEMBER"));
    addMember(companyB.tenantId(), 987654321L, "STAFF", "BD").andExpect(status().isNotFound());

    putJson(platform, "/admin-api/platform/users/" + multiId, Map.of("nickname", "Shared Name"))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT nickname FROM sys_user WHERE id = ?", String.class, multiId))
        .isEqualTo("Shared Name");

    String before = CompanySessions.login(mvc, json, multiName, PASSWORD);
    String fresh = UUID.randomUUID().toString();
    putJson(
            platform,
            "/admin-api/platform/users/" + multiId + "/password",
            Map.of("password", fresh))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, before))
        .andExpect(status().isUnauthorized());
    String after = CompanySessions.login(mvc, json, multiName, fresh);

    putJson(platform, "/admin-api/platform/users/" + multiId + "/status", Map.of("status", 0))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, after))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/admin-api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Captchas.loginBody(mvc, json, multiName, fresh))))
        .andExpect(status().isUnauthorized());
    putJson(platform, "/admin-api/platform/users/" + multiId + "/status", Map.of("status", 1))
        .andExpect(status().isOk());
    CompanySessions.login(mvc, json, multiName, fresh);
  }

  @Test
  void shouldBindLastCompany_whenTokenWasIssuedWithoutOne() throws Exception {
    String token = CompanySessions.login(mvc, json, multiName, PASSWORD);
    switchTo(token, companyB.tenantId()).andExpect(status().isOk());
    // A token from before ADR-022 has no current company in its session.
    StpUtil.getStpLogic()
        .getTokenSessionByToken(token, false)
        .delete(CurrentUserContext.CURRENT_TENANT);
    mvc.perform(get("/admin-api/auth/me").header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantId").value(id(companyB.tenantId())))
        .andExpect(jsonPath("$.data.role").value("STAFF"));
    assertThat(
            ((Number)
                    StpUtil.getStpLogic()
                        .getTokenSessionByToken(token, false)
                        .get(CurrentUserContext.CURRENT_TENANT))
                .longValue())
        .isEqualTo(companyB.tenantId());
  }

  private static String suffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private static String id(long value) {
    return Long.toString(value);
  }

  private long createSuperAdmin(String token, String username) throws Exception {
    String body =
        postJson(token, "/admin-api/system/users", newUser(username, "SUPER_ADMIN"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(json.readTree(body).at("/data/id").asText());
  }

  private static Map<String, Object> newUser(String username, String role) {
    return "STAFF".equals(role)
        ? Map.of(
            "username", username,
            "password", PASSWORD,
            "nickname", username,
            "role", role,
            "postTemplate", "BD")
        : Map.of("username", username, "password", PASSWORD, "nickname", username, "role", role);
  }

  private ResultActions addMember(long tenantId, long userId, String role, String template)
      throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("userId", Long.toString(userId));
    body.put("role", role);
    if (template != null) {
      body.put("postTemplate", template);
    }
    return postJson(platform, "/admin-api/platform/tenants/" + tenantId + "/members", body);
  }

  private ResultActions switchTo(String token, long tenantId) throws Exception {
    return postJson(token, "/admin-api/auth/company", Map.of("tenantId", Long.toString(tenantId)));
  }

  private ResultActions editUser(String token, long userId, String nickname, String role)
      throws Exception {
    return putJson(
        token,
        "/admin-api/system/users/" + userId,
        Map.of("nickname", nickname, "role", role, "dataScope", "ALL"));
  }

  private ResultActions createKol(String token, Long shownCompany, String name, String email)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post("/admin-api/kol/kols")
            .header(TOKEN, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(Map.of("name", name, "email", email, "country", "US")));
    if (shownCompany != null) {
      request.header(COMPANY, Long.toString(shownCompany));
    }
    return mvc.perform(request);
  }

  private ResultActions postJson(String token, String path, Map<String, ?> body) throws Exception {
    return mvc.perform(
        post(path)
            .header(TOKEN, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private ResultActions putJson(String token, String path, Map<String, ?> body) throws Exception {
    return mvc.perform(
        put(path)
            .header(TOKEN, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }
}
