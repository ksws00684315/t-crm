package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.Captchas;
import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.TestWait;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.mapper.SysUserMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    r.add("crm.kol-portal.base-url", () -> "https://kol.example.com");
    r.add("crm.link.click-consumer.enabled", () -> "false");
    WeidianTestProperties.register(r);
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final SysUserMapper users;
  private final StringRedisTemplate redis;
  private final DataSource dataSource;
  private final OperLogRetentionJob operLogRetention;

  @Autowired
  SystemIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      SysUserMapper users,
      StringRedisTemplate redis,
      DataSource dataSource,
      OperLogRetentionJob operLogRetention) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.users = users;
    this.redis = redis;
    this.dataSource = dataSource;
    this.operLogRetention = operLogRetention;
  }

  @BeforeEach
  void prepareIndependentData() {
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
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
                "SELECT COUNT(*) FROM sys_user_tenant WHERE role='SUPER_ADMIN'", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE role='PLATFORM_ADMIN'"
                    + " AND last_tenant_id IS NULL AND status=1",
                Integer.class))
        .isEqualTo(1);
    // ADR-022: the platform administrator has no membership.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_tenant m JOIN sys_user u ON u.id = m.user_id"
                    + " WHERE u.role='PLATFORM_ADMIN'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_tenant WHERE id=1 AND tenant_code='default' AND status=1",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void shouldRequireTenantIdOnBusinessTables_whenMigrationsRun() {
    var columns =
        jdbc.queryForList(
            """
            SELECT c.table_name, c.is_nullable
            FROM information_schema.columns c
            JOIN information_schema.tables t
              ON t.table_schema = c.table_schema AND t.table_name = c.table_name
            WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id'
              AND t.table_type = 'BASE TABLE'
              AND NOT EXISTS (
                SELECT 1 FROM pg_inherits i
                JOIN pg_class child ON child.oid = i.inhrelid
                WHERE child.relname = c.table_name)
            ORDER BY c.table_name
            """);
    assertThat(columns)
        .extracting(row -> row.get("table_name") + ":" + row.get("is_nullable"))
        .containsExactlyInAnyOrder(
            "crm_click_log:NO",
            "crm_copy_job:NO",
            "crm_copy_job_item:NO",
            "crm_file:NO",
            "crm_google_credential:NO",
            "crm_kol:NO",
            "crm_kol_auth_token:NO",
            "crm_kol_bd_history:NO",
            "crm_kol_bd_transfer:NO",
            "crm_kol_cleanup_config:NO",
            "crm_kol_copy_token:NO",
            "crm_kol_item:NO",
            "crm_kol_platform:NO",
            "crm_kol_promotion_record:NO",
            "crm_kol_register_code:NO",
            "crm_link_template:NO",
            "crm_notification:NO",
            "crm_order:NO",
            "crm_order_item:NO",
            "crm_order_sync_state:NO",
            "crm_payout_account:NO",
            "crm_product:NO",
            "crm_refund:NO",
            "crm_sheet_source:NO",
            "crm_sheet_source_item:NO",
            "crm_shop:NO",
            "crm_task:NO",
            "crm_task_file:NO",
            "crm_weidian_app:NO",
            "crm_weidian_shop_auth:NO",
            "sys_oper_log:YES",
            "sys_user_permission:NO",
            "sys_user_tenant:NO");
    var without =
        jdbc.queryForList(
            """
            SELECT t.table_name
            FROM information_schema.tables t
            WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
              AND NOT EXISTS (
                SELECT 1 FROM information_schema.columns c
                WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name
                  AND c.column_name = 'tenant_id')
              AND NOT EXISTS (
                SELECT 1 FROM pg_inherits i
                JOIN pg_class child ON child.oid = i.inhrelid
                WHERE child.relname = t.table_name)
            ORDER BY t.table_name
            """,
            String.class);
    // Design §3.4 exceptions; crm_fx_rate holds the platform-level reference rate (§7.5). An
    // account (sys_user) belongs to no company since ADR-022; its companies are sys_user_tenant.
    assertThat(without)
        .contains(
            "sys_tenant",
            "crm_link_index_outbox",
            "crm_fx_rate",
            "flyway_schema_history",
            "sys_user")
        .doesNotContain("crm_kol", "crm_click_log", "sys_user_tenant", "sys_user_permission");
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_sys_user_last_tenant'",
                String.class))
        .contains("MEMBER")
        .contains("last_tenant_id");
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_sys_user_tenant_role'",
                String.class))
        .contains("SUPER_ADMIN")
        .contains("STAFF")
        .doesNotContain("PLATFORM_ADMIN");
  }

  @Test
  void shouldDeleteOperationLogsOlderThan180Days_andKeepTheBoundary() {
    Instant now = Instant.parse("2026-10-07T03:45:00Z");
    Instant cutoff = now.minus(180, ChronoUnit.DAYS);
    jdbc.update("DELETE FROM sys_oper_log WHERE id IN (9101, 9102, 9103)");
    insertOperLog(9101, cutoff.minusMillis(1));
    insertOperLog(9102, cutoff);
    insertOperLog(9103, cutoff.plusMillis(1));
    try {
      operLogRetention.cleanup(now);
      assertThat(
              jdbc.queryForList(
                  "SELECT id FROM sys_oper_log WHERE id IN (9101, 9102, 9103) ORDER BY id",
                  Long.class))
          .containsExactly(9102L, 9103L);
    } finally {
      jdbc.update("DELETE FROM sys_oper_log WHERE id IN (9101, 9102, 9103)");
    }
  }

  private void insertOperLog(long id, Instant operTime) {
    jdbc.update(
        """
        INSERT INTO sys_oper_log (id, module, action, method, request_uri, result, cost_ms, oper_time)
        VALUES (?, 'system', 'Retention', 'GET', '/admin-api/system/operation-logs', 'SUCCESS', 1, ?)
        """,
        id,
        Timestamp.from(operTime));
  }

  @Test
  void shouldFailBootstrap_whenOnlyDisabledPlatformAdministratorExists() {
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
        .andExpect(jsonPath("$.data.role").value("PLATFORM_ADMIN"))
        .andExpect(jsonPath("$.data.tenantId").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.tenantName").value(org.hamcrest.Matchers.nullValue()))
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
  void shouldRejectProtectedLists_whenRoleIsStaff() throws Exception {
    String admin = companyAdmin();
    String username = randomName();
    create(admin, username, Role.STAFF);
    String token = login(username, PASSWORD);
    for (var path : java.util.List.of("/admin-api/system/users", "/admin-api/system/oper-logs")) {
      mvc.perform(get(path).header("X-Admin-Token", token))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    }
  }

  @Test
  void shouldInvalidateTokens_whenAccountIsDisabled() throws Exception {
    String admin = companyAdmin();
    String username = randomName();
    String id = create(admin, username, Role.STAFF);
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
    String admin = companyAdmin();
    String username = randomName();
    String id = create(admin, username, Role.STAFF);
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
    String admin = companyAdmin();
    mvc.perform(
            post("/admin-api/system/users")
                .header("X-Admin-Token", admin)
                .contentType("application/json")
                .content(createBody("BOOTSTRAP", Role.STAFF)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("USER_USERNAME_EXISTS"));
  }

  @Test
  void shouldPreserveInstant_whenOrmRoundTripsUtcTimestamp() throws Exception {
    String admin = companyAdmin();
    String username = randomName();
    Long id = Long.valueOf(create(admin, username, Role.STAFF));
    Instant instant = Instant.parse("2026-10-04T04:30:12.123Z");
    var user = TenantScope.callCompany(1L, () -> users.selectById(id));
    user.setLastLoginTime(instant);
    TenantScope.runCompany(1L, () -> users.updateById(user));
    assertThat(TenantScope.callCompany(1L, () -> users.selectById(id)).getLastLoginTime())
        .isEqualTo(instant);
    assertThat(jdbc.queryForObject("SHOW TIME ZONE", String.class)).isEqualTo("UTC");
  }

  @Test
  void shouldHideLogicalDeletion_whenDeletingThroughMapper() throws Exception {
    String admin = companyAdmin();
    Long id = Long.valueOf(create(admin, randomName(), Role.STAFF));
    assertThat(TenantScope.callCompany(1L, () -> users.deleteById(id))).isEqualTo(1);
    assertThat(TenantScope.callCompany(1L, () -> users.selectById(id))).isNull();
    assertThat(jdbc.queryForObject("SELECT is_deleted FROM sys_user WHERE id=?", Boolean.class, id))
        .isTrue();
  }

  @Test
  void shouldFindUppercaseSearchAndPagingMetadata_whenListingUsers() throws Exception {
    String admin = companyAdmin();
    String username = randomName();
    String id = create(admin, username, Role.STAFF);
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
    String admin = companyAdmin();
    String username = randomName();
    create(admin, username, Role.STAFF);
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
    var body = new LinkedHashMap<String, Object>();
    body.put("username", username);
    body.put("password", PASSWORD);
    body.put("nickname", "Test");
    body.put("role", role.name());
    if (role == Role.STAFF) {
      body.put("postTemplate", "BD");
      body.put("dataScope", "OWN");
    } else {
      body.put("dataScope", "ALL");
    }
    return json.writeValueAsString(body);
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

  @Test
  void shouldRejectToken_whenUnusedForTwoHours() throws Exception {
    String token = login("bootstrap", PASSWORD);
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isOk());
    // Sa-Token keeps the last activity per token; move it back past the 2 h inactivity limit.
    String key = "X-Admin-Token:admin:last-active:" + token;
    assertThat(redis.hasKey(key)).isTrue();
    long twoHoursAgo = System.currentTimeMillis() - 7_201_000L;
    redis.opsForValue().set(key, Long.toString(twoHoursAgo));
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldRejectTokenIssuedDuringConcurrentPasswordReset() throws Exception {
    String admin = companyAdmin();
    String username = randomName();
    String id = create(admin, username, Role.STAFF);
    String[] issued = new String[1];
    // The reset queues first; the login has checked the old password and waits to record the
    // login time, then issues its token after the reset (and its logout) committed.
    runAfterQueuedOnRowLock(
        Long.valueOf(id),
        () ->
            putJson(
                admin,
                "/admin-api/system/users/" + id + "/password",
                Map.of("password", UUID.randomUUID().toString())),
        () -> issued[0] = login(username, PASSWORD));
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", issued[0]))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldRejectOldToken_whenCredentialVersionChangedWithoutLogout() throws Exception {
    String admin = companyAdmin();
    String username = randomName();
    String id = create(admin, username, Role.STAFF);
    String token = login(username, PASSWORD);
    // A password change whose Redis logout failed: the version moved on, the session remains.
    jdbc.update(
        "UPDATE sys_user SET credential_version = credential_version + 1 WHERE id=?",
        Long.valueOf(id));
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isUnauthorized());
    String fresh = login(username, PASSWORD);
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", fresh))
        .andExpect(status().isOk());
  }

  @Test
  void shouldKeepOneSuperAdmin_whenTwoSuperAdminsDemoteEachOtherConcurrently() throws Exception {
    String first = companyAdmin();
    String otherName = randomName();
    String other = create(first, otherName, Role.SUPER_ADMIN);
    String otherToken = login(otherName, PASSWORD);
    Long firstId =
        jdbc.queryForObject("SELECT id FROM sys_user WHERE username='company-admin'", Long.class);
    Map<String, String> demote =
        Map.of(
            "nickname", "Demoted", "role", "STAFF", "postTemplate", "OPERATOR", "dataScope", "ALL");
    int[] codes = new int[2];
    runAfterQueuedOnRowLocks(
        List.of(firstId, Long.valueOf(other)),
        () -> codes[0] = putStatus(first, "/admin-api/system/users/" + other, demote),
        () -> codes[1] = putStatus(otherToken, "/admin-api/system/users/" + firstId, demote));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_tenant WHERE role='SUPER_ADMIN' AND status=1"
                    + " AND tenant_id = 1 AND is_deleted = FALSE",
                Integer.class))
        .isEqualTo(1);
    assertThat(codes).containsExactlyInAnyOrder(200, 422);
  }

  @Test
  void shouldKeepAndRenewToken_whenUsedShortlyBeforeTwoHours() throws Exception {
    String token = login("bootstrap", PASSWORD);
    String key = "X-Admin-Token:admin:last-active:" + token;
    long almostTwoHoursAgo = System.currentTimeMillis() - 7_000_000L;
    redis.opsForValue().set(key, Long.toString(almostTwoHoursAgo));
    mvc.perform(get("/admin-api/auth/me").header("X-Admin-Token", token))
        .andExpect(status().isOk());
    long renewed = Long.parseLong(redis.opsForValue().get(key).split(",")[0]);
    assertThat(renewed).isGreaterThan(System.currentTimeMillis() - 60_000L);
  }

  @Test
  void shouldTreatWildcardsLiterally_whenSearchingUsernames() throws Exception {
    String admin = companyAdmin();
    create(admin, randomName(), Role.STAFF);
    for (String keyword : new String[] {"%", "_", "o_", "\\"}) {
      mvc.perform(
              get("/admin-api/system/users")
                  .header("X-Admin-Token", admin)
                  .param("username", keyword))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.total").value("0"));
    }
  }

  @Test
  void shouldKeepConcurrentDisable_whenAnotherAdministratorEditsTheSameUser() throws Exception {
    String admin = companyAdmin();
    String username = randomName();
    String id = create(admin, username, Role.STAFF);
    // The disable commits first; the edit has already read the row (status 1) and must not write
    // that stale status back.
    runAfterQueuedOnRowLock(
        Long.valueOf(id),
        () -> putJson(admin, "/admin-api/system/users/" + id + "/status", Map.of("status", 0)),
        () -> putJson(admin, "/admin-api/system/users/" + id, editBody()));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM sys_user_tenant WHERE user_id=? AND tenant_id=1",
                Integer.class,
                Long.valueOf(id)))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT nickname FROM sys_user WHERE id=?", String.class, Long.valueOf(id)))
        .isEqualTo("Edited");
    requestLogin(username, PASSWORD, 401);
  }

  @Test
  void shouldKeepConcurrentPasswordReset_whenAnotherAdministratorEditsTheSameUser()
      throws Exception {
    String admin = companyAdmin();
    String username = randomName();
    String id = create(admin, username, Role.STAFF);
    String newPassword = UUID.randomUUID().toString();
    runAfterQueuedOnRowLock(
        Long.valueOf(id),
        () ->
            putJson(
                admin,
                "/admin-api/system/users/" + id + "/password",
                Map.of("password", newPassword)),
        () -> putJson(admin, "/admin-api/system/users/" + id, editBody()));
    requestLogin(username, PASSWORD, 401);
    login(username, newPassword);
  }

  /**
   * Holds a row lock on the user, starts {@code first} and then {@code second} so that both read
   * the row and queue behind the lock in that order, then releases it.
   */
  private void runAfterQueuedOnRowLock(Long userId, Callable<?> first, Callable<?> second)
      throws Exception {
    runAfterQueuedOnRowLocks(List.of(userId), first, second);
  }

  /** As {@link #runAfterQueuedOnRowLock}, holding row locks on several users. */
  private void runAfterQueuedOnRowLocks(List<Long> userIds, Callable<?> first, Callable<?> second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (var lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      for (Long userId : userIds) {
        // The account row (name, password) and its membership rows (role, status).
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

  private Void putJson(String token, String path, Map<String, ?> body) throws Exception {
    mvc.perform(
            put(path)
                .header("X-Admin-Token", token)
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
    return null;
  }

  private int putStatus(String token, String path, Map<String, ?> body) throws Exception {
    return mvc.perform(
            put(path)
                .header("X-Admin-Token", token)
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private Map<String, String> editBody() {
    return Map.of("nickname", "Edited", "role", "STAFF", "postTemplate", "BD", "dataScope", "OWN");
  }

  private String companyAdmin() throws Exception {
    return CompanySessions.companyAdmin(mvc, json, PASSWORD);
  }

  private String login(String username, String password) throws Exception {
    return json.readTree(requestLogin(username, password, 200)).at("/data/token").asText();
  }

  private String requestLogin(String username, String password, int expected) throws Exception {
    return mvc.perform(
            post("/admin-api/auth/login")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(Captchas.loginBody(mvc, json, username, password))))
        .andExpect(status().is(expected))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
