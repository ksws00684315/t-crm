package com.tcrm.admin.kol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.kol.api.CopyTokenApi;
import com.tcrm.admin.kol.api.CopyTokenGrantDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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

  private String admin;

  @Autowired
  CopyTokenIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      StringRedisTemplate redis,
      CopyTokenApi copyTokens) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.copyTokens = copyTokens;
  }

  @BeforeEach
  void prepareIndependentData() throws Exception {
    jdbc.update("DELETE FROM crm_kol_copy_token");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
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
  }

  @Test
  void shouldCreateListRevealRevoke_authenticateAndConsumeConcurrently() throws Exception {
    long kolId = createKol("Alice", "alice17a@example.com", null);

    MvcResult created =
        mvc.perform(
                post("/admin-api/kol/copy-tokens")
                    .header(ADMIN_TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"kolId\":\"" + kolId + "\",\"quota\":5,\"validMinutes\":60}"))
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
                .content("{\"kolId\":\"" + other + "\"}"))
        .andExpect(status().isOk());

    MvcResult mineCreated =
        mvc.perform(
                post("/admin-api/kol/copy-tokens")
                    .header(ADMIN_TOKEN, bdToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"kolId\":\"" + mine + "\"}"))
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
                    .content("{\"kolId\":\"" + kolId + "\",\"quota\":1,\"validMinutes\":60}"))
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
}
