package com.tcrm.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tcrm.admin.system.*;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class BackendDockerTest {
  private static final String PASSWORD = UUID.randomUUID().toString();

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
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  @Test
  void migrationBootstrapAuthLockDisablePermissionsAndAudit() throws Exception {
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND"
                + " table_name IN ('sys_user','sys_oper_log')",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE role = 'SUPER_ADMIN'", Integer.class));
    String admin = login("bootstrap", PASSWORD);
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"))
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    String username = "op" + UUID.randomUUID().toString().substring(0, 8);
    String created =
        mvc.perform(
                post("/admin-api/system/user")
                    .header("X-Admin-Token", admin)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            java.util.Map.of(
                                "username",
                                username,
                                "password",
                                PASSWORD,
                                "nickname",
                                "Operator",
                                "role",
                                "OPERATOR",
                                "dataScope",
                                "OWN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(created).at("/data/id").asText();
    assertTrue(json.readTree(created).at("/data/id").isString());
    String operator = login(username, PASSWORD);
    mvc.perform(get("/admin-api/system/user").header("X-Admin-Token", operator))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/admin-api/system/user/" + id + "/status")
                .header("X-Admin-Token", admin)
                .contentType("application/json")
                .content("{\"status\":0}"))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", operator))
        .andExpect(status().isUnauthorized());
    requestLogin(username, PASSWORD, 401);
    for (int i = 0; i < 4; i++) requestLogin("locked-user", UUID.randomUUID().toString(), 401);
    requestLogin("locked-user", UUID.randomUUID().toString(), 429);
    requestLogin("LOCKED-USER", PASSWORD, 429);
    long deadline = System.nanoTime() + 5_000_000_000L;
    Integer count;
    do {
      count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM sys_oper_log WHERE action = 'Create' AND result = 'SUCCESS'",
              Integer.class);
      if (count == 0) Thread.sleep(50);
    } while (count == 0 && System.nanoTime() < deadline);
    assertTrue(count > 0);
    String params =
        jdbc.queryForObject(
            "SELECT request_params FROM sys_oper_log WHERE action = 'Create' LIMIT 1",
            String.class);
    assertTrue(params.contains("[REDACTED]"));
    assertFalse(params.contains(PASSWORD));
    deadline = System.nanoTime() + 5_000_000_000L;
    do {
      count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM sys_oper_log WHERE action = 'Login' AND result = 'FAILURE'",
              Integer.class);
      if (count == 0) Thread.sleep(50);
    } while (count == 0 && System.nanoTime() < deadline);
    assertTrue(count > 0);
  }

  @Test
  void shouldRoundTripUtcInstant_whenSavingUser() {
    java.time.Instant instant = java.time.Instant.parse("2026-10-04T04:30:12.123Z");
    Long id = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'bootstrap'", Long.class);
    jdbc.update("UPDATE sys_user SET last_login_time = ? WHERE id = ?", java.sql.Timestamp.from(instant), id);
    assertEquals(instant, jdbc.queryForObject("SELECT last_login_time FROM sys_user WHERE id = ?", java.sql.Timestamp.class, id).toInstant());
    assertEquals("UTC", jdbc.queryForObject("SHOW TIME ZONE", String.class));
  }

  @Autowired SysUserMapper userMapper;

  @Test
  void shouldHideDeletedUser_whenDeletingThroughMapper() {
    SysUser user = new SysUser();
    user.username = "delete" + UUID.randomUUID().toString().substring(0, 8);
    user.passwordHash = "not-a-login-credential";
    user.nickname = "Deleted";
    user.role = Role.OPERATOR;
    user.dataScope = DataScope.OWN;
    user.status = 1;
    user.deleted = false;
    userMapper.insert(user);
    assertEquals(1, userMapper.deleteById(user.id));
    assertNull(userMapper.selectById(user.id));
    assertTrue(jdbc.queryForObject("SELECT is_deleted FROM sys_user WHERE id = ?", Boolean.class, user.id));
  }

  @Test
  void shouldReturnConflict_whenCreatingCaseInsensitiveDuplicate() throws Exception {
    String admin = login("BOOTSTRAP", PASSWORD);
    mvc.perform(post("/admin-api/system/user").header("X-Admin-Token", admin)
        .contentType("application/json").content(json.writeValueAsString(java.util.Map.of(
            "username", "BOOTSTRAP", "password", PASSWORD, "nickname", "Duplicate",
            "role", "OPERATOR", "dataScope", "OWN"))))
        .andExpect(status().isConflict());
  }

  private String login(String username, String password) throws Exception {
    String value = requestLogin(username, password, 200);
    return json.readTree(value).at("/data/token").asText();
  }

  private String requestLogin(String username, String password, int expected) throws Exception {
    return mvc.perform(
            post("/admin-api/auth/login")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        java.util.Map.of("username", username, "password", password))))
        .andExpect(status().is(expected))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
