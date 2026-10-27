package com.tcrm.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A stored enum value this build does not know must degrade one field to null instead of failing
 * the whole account list (#106). Only reads are lenient; permission writes keep their validation.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class UnknownEnumReadIT {
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
  private final StringRedisTemplate redis;

  private String admin;
  private long staffId;
  private long intactStaffId;
  private String staffName;
  private String intactStaffName;

  @Autowired
  UnknownEnumReadIT(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json, StringRedisTemplate redis) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
  }

  @BeforeEach
  void prepareAccounts() throws Exception {
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
    admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    staffName = "t73_" + suffix;
    intactStaffName = "t73_intact_" + suffix;
    staffId = CompanySessions.createStaff(mvc, json, admin, PASSWORD, staffName, "BD", null, null);
    intactStaffId =
        CompanySessions.createStaff(mvc, json, admin, PASSWORD, intactStaffName, "BD", null, null);
  }

  /**
   * Simulates a database migrated by a newer build: the CHECK constraint knows the value while this
   * build's enum does not (exactly the FINANCE rollback incident behind #106).
   */
  private void widenPostTemplateCheck() {
    jdbc.update("ALTER TABLE sys_user_tenant DROP CONSTRAINT ck_sys_user_tenant_post_template");
    jdbc.update(
        "ALTER TABLE sys_user_tenant ADD CONSTRAINT ck_sys_user_tenant_post_template"
            + " CHECK (post_template IN ('OPERATOR', 'BD', 'FINANCE', 'NOT_EXIST'))");
  }

  @Test
  void shouldKeepTheListAlive_andWarn_whenAPostTemplateValueIsUnknown(CapturedOutput output)
      throws Exception {
    widenPostTemplateCheck();
    jdbc.update(
        "UPDATE sys_user_tenant SET post_template = 'NOT_EXIST' WHERE user_id = ?", staffId);
    JsonNode row = listRow(staffName);
    JsonNode intact = listRow(intactStaffName);
    // The unknown value degrades this row's field only; the intact row keeps its template.
    assertThat(row.path("postTemplate").isNull()).isTrue();
    assertThat(intact.path("postTemplate").asText()).isEqualTo("BD");
    assertThat(output)
        .contains("NOT_EXIST")
        .contains("post_template")
        .contains("com.tcrm.admin.security.api.PostTemplate");
  }

  @Test
  void shouldKeepTheListAlive_whenARoleOrDataScopeValueIsUnknown() throws Exception {
    // role is VARCHAR(16), data_scope VARCHAR(3): use an unknown scope the column still fits.
    jdbc.update("ALTER TABLE sys_user_tenant DROP CONSTRAINT ck_sys_user_tenant_role");
    jdbc.update(
        "ALTER TABLE sys_user_tenant ADD CONSTRAINT ck_sys_user_tenant_role"
            + " CHECK (role IN ('SUPER_ADMIN', 'STAFF', 'SUPER_DUPER'))");
    jdbc.update("ALTER TABLE sys_user_tenant DROP CONSTRAINT ck_sys_user_tenant_scope");
    jdbc.update(
        "ALTER TABLE sys_user_tenant ADD CONSTRAINT ck_sys_user_tenant_scope"
            + " CHECK (data_scope IN ('ALL', 'OWN', 'XYZ'))");
    // The own-rule (data_scope = 'ALL' OR role = 'STAFF') knows neither of the new values.
    jdbc.update("ALTER TABLE sys_user_tenant DROP CONSTRAINT ck_sys_user_tenant_own");
    jdbc.update(
        "UPDATE sys_user_tenant SET role = 'SUPER_DUPER', data_scope = 'XYZ'"
            + " WHERE user_id = ?",
        staffId);
    JsonNode row = listRow(staffName);
    assertThat(row.path("role").isNull()).isTrue();
    assertThat(row.path("dataScope").isNull()).isTrue();
  }

  @Test
  void shouldKeepPermissionWritesStrict_afterAnUnknownValueWasRead() throws Exception {
    widenPostTemplateCheck();
    jdbc.update(
        "UPDATE sys_user_tenant SET post_template = 'NOT_EXIST' WHERE user_id = ?", staffId);
    assertThat(listRow(staffName).path("postTemplate").isNull()).isTrue();
    // A valid permission update still works after the corrupted read...
    putPermissions(Map.of("permissions", List.of("kol:view"), "dataScope", "OWN"))
        .andExpect(status().isOk());
    // ...and an unknown data scope is still rejected on the write path.
    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put("permissions", List.of("kol:view"));
    bad.put("dataScope", "SOMEWHERE");
    putPermissions(bad).andExpect(status().isBadRequest());
  }

  private JsonNode listRow(String username) throws Exception {
    String body =
        mvc.perform(get("/admin-api/system/users").header(TOKEN, admin).param("username", username))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("1"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/items").get(0);
  }

  private ResultActions putPermissions(Map<String, ?> body) throws Exception {
    return mvc.perform(
        put("/admin-api/system/users/" + staffId + "/permissions")
            .header(TOKEN, admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }
}
