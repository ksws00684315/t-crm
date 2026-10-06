package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.mapper.SysUserMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
class SystemIT {
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
    r.add("crm.link.base-url", () -> "https://t.example.com");
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final SysUserMapper users;
  private final StringRedisTemplate redis;

  @Autowired
  SystemIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      SysUserMapper users,
      StringRedisTemplate redis) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.users = users;
    this.redis = redis;
  }

  @BeforeEach
  void prepareIndependentData() {
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("UPDATE sys_user SET last_login_time=NULL WHERE username='bootstrap'");
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
  }

  @Test
  void shouldInitializeSchemaAndBootstrap_whenApplicationStarts() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('sys_user','sys_oper_log')",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=true AND version='202610041200'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE role='SUPER_ADMIN'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void shouldFailBootstrap_whenOnlyDisabledSuperAdministratorExists() {
    jdbc.update("UPDATE sys_user SET status=0 WHERE username='bootstrap'");
    try {
      assertThatThrownBy(
              () -> new BootstrapAdmin(users, new BCryptPasswordEncoder(12), "", "").run(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TCRM_BOOTSTRAP_ADMIN_USERNAME")
          .hasMessageContaining("TCRM_BOOTSTRAP_ADMIN_PASSWORD");
    } finally {
      jdbc.update("UPDATE sys_user SET status=1 WHERE username='bootstrap'");
    }
  }

  @Test
  void shouldAuthenticateWithoutExposingHash_whenLoggingIn() throws Exception {
    String token = login("BOOTSTRAP", PASSWORD);
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"))
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
  }

  @Test
  void shouldRejectInvalidCredentials_whenAccountDoesNotExist() throws Exception {
    requestLogin("unknown-user", PASSWORD, 401);
  }

  @Test
  void shouldLockCaseInsensitiveAccount_whenFiveFailuresOccur() throws Exception {
    for (int i = 0; i < 4; i++) {
      requestLogin("locked-user", UUID.randomUUID().toString(), 401);
    }
    requestLogin("locked-user", UUID.randomUUID().toString(), 429);
    requestLogin("LOCKED-USER", PASSWORD, 429);
  }

  @Test
  void shouldRejectProtectedLists_whenRoleIsOperatorOrFinance() throws Exception {
    String admin = login("bootstrap", PASSWORD);
    for (var role : java.util.List.of(Role.OPERATOR, Role.FINANCE)) {
      String username = randomName();
      create(admin, username, role);
      String token = login(username, PASSWORD);
      for (var path : java.util.List.of("/admin-api/system/users", "/admin-api/system/oper-logs")) {
        mvc.perform(get(path).header("X-Admin-Token", token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
      }
    }
  }

  @Test
  void shouldInvalidateTokens_whenAccountIsDisabled() throws Exception {
    String admin = login("bootstrap", PASSWORD);
    String username = randomName();
    String id = create(admin, username, Role.OPERATOR);
    String token = login(username, PASSWORD);
    mvc.perform(
            put("/admin-api/system/users/" + id + "/status")
                .header("X-Admin-Token", admin)
                .contentType("application/json")
                .content("{\"status\":0}"))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isUnauthorized());
    requestLogin(username, PASSWORD, 401);
  }

  @Test
  void shouldInvalidateAllTokens_whenAdministratorResetsPassword() throws Exception {
    String admin = login("bootstrap", PASSWORD);
    String username = randomName();
    String id = create(admin, username, Role.OPERATOR);
    String token = login(username, PASSWORD);
    mvc.perform(
            put("/admin-api/system/users/" + id + "/password")
                .header("X-Admin-Token", admin)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("password", UUID.randomUUID().toString()))))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isUnauthorized());
    requestLogin(username, PASSWORD, 401);
  }

  @Test
  void shouldReturnConflict_whenUsernameDiffersOnlyInCase() throws Exception {
    String admin = login("bootstrap", PASSWORD);
    mvc.perform(
            post("/admin-api/system/users")
                .header("X-Admin-Token", admin)
                .contentType("application/json")
                .content(createBody("BOOTSTRAP", Role.OPERATOR)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("USER_USERNAME_EXISTS"));
  }

  @Test
  void shouldPreserveInstant_whenOrmRoundTripsUtcTimestamp() throws Exception {
    String admin = login("bootstrap", PASSWORD);
    String username = randomName();
    Long id = Long.valueOf(create(admin, username, Role.OPERATOR));
    Instant instant = Instant.parse("2026-10-04T04:30:12.123Z");
    var user = users.selectById(id);
    user.setLastLoginTime(instant);
    users.updateById(user);
    assertThat(users.selectById(id).getLastLoginTime()).isEqualTo(instant);
    assertThat(jdbc.queryForObject("SHOW TIME ZONE", String.class)).isEqualTo("UTC");
  }

  @Test
  void shouldHideLogicalDeletion_whenDeletingThroughMapper() throws Exception {
    String admin = login("bootstrap", PASSWORD);
    Long id = Long.valueOf(create(admin, randomName(), Role.OPERATOR));
    assertThat(users.deleteById(id)).isEqualTo(1);
    assertThat(users.selectById(id)).isNull();
    assertThat(jdbc.queryForObject("SELECT is_deleted FROM sys_user WHERE id=?", Boolean.class, id))
        .isTrue();
  }

  @Test
  void shouldFindUppercaseSearchAndPagingMetadata_whenListingUsers() throws Exception {
    String admin = login("bootstrap", PASSWORD);
    String username = randomName();
    String id = create(admin, username, Role.OPERATOR);
    mvc.perform(
            get("/admin-api/system/users")
                .header("X-Admin-Token", admin)
                .param("username", username.toUpperCase(java.util.Locale.ROOT))
                .param("page", "1")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value(id))
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20));
  }

  @Test
  void shouldRedactPasswordInAudit_whenCreatingUser() throws Exception {
    String admin = login("bootstrap", PASSWORD);
    String username = randomName();
    create(admin, username, Role.OPERATOR);
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              var params =
                  jdbc.queryForList(
                      "SELECT request_params FROM sys_oper_log WHERE action='Create' AND request_params LIKE ?",
                      String.class,
                      "%" + username + "%");
              assertThat(params).hasSize(1);
              assertThat(params.getFirst()).contains("[REDACTED]").doesNotContain(PASSWORD);
            });
  }

  @Test
  void shouldAuditAnonymousFailure_whenLoginIsRejected() throws Exception {
    String username = randomName();
    requestLogin(username, PASSWORD, 401);
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sys_oper_log WHERE action='Login' AND result='FAILURE' AND username=?",
                            Integer.class,
                            username))
                    .isEqualTo(1));
  }

  private String randomName() {
    return "op" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String createBody(String username, Role role) {
    return json.writeValueAsString(
        Map.of(
            "username",
            username,
            "password",
            PASSWORD,
            "nickname",
            "Test",
            "role",
            role.name(),
            "dataScope",
            role == Role.OPERATOR ? "OWN" : "ALL"));
  }

  private String create(String admin, String username, Role role) throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/system/users")
                    .header("X-Admin-Token", admin)
                    .contentType("application/json")
                    .content(createBody(username, role)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var id = json.readTree(body).at("/data/id");
    assertThat(id.isString()).isTrue();
    return id.asText();
  }

  private String login(String username, String password) throws Exception {
    return json.readTree(requestLogin(username, password, 200)).at("/data/token").asText();
  }

  private String requestLogin(String username, String password, int expected) throws Exception {
    return mvc.perform(
            post("/admin-api/auth/login")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(Map.of("username", username, "password", password))))
        .andExpect(status().is(expected))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
