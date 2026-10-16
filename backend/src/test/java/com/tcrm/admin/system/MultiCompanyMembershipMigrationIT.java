package com.tcrm.admin.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Backfill of {@code V202610172300} (task 21a, ADR-022) on accounts that exist before it: every
 * company account gets exactly one membership in its former company, codes keep that company, and
 * platform administrators get none.
 */
@Tag("docker")
@Testcontainers
class MultiCompanyMembershipMigrationIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.11")
          .withDatabaseName("tcrm")
          .withUsername("tcrm")
          .withPassword(UUID.randomUUID().toString());

  private Flyway flyway(String target) {
    var config =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    if (target != null) {
      config.target(target);
    }
    return config.load();
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(
        new SimpleDriverDataSource(
            new org.postgresql.Driver(),
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()));
  }

  @Test
  void shouldMoveCompanyIdentityToMemberships_whenAccountsExist() {
    flyway("202610171800").migrate();
    JdbcTemplate db = jdbc();
    OffsetDateTime now = OffsetDateTime.now();
    db.update(
        "INSERT INTO sys_tenant (id, tenant_code, name, status, create_time, update_time)"
            + " VALUES (2, 'second', 'Second', 1, ?, ?)",
        now,
        now);
    insert(db, 9201, "PLATFORM_ADMIN", "ALL", null, 1, false, null);
    insert(db, 9202, "SUPER_ADMIN", "ALL", null, 1, false, 1L);
    insert(db, 9203, "STAFF", "OWN", "BD", 0, false, 1L);
    insert(db, 9204, "STAFF", "ALL", "OPERATOR", 1, false, 2L);
    insert(db, 9205, "STAFF", "OWN", "BD", 1, true, 1L);
    grant(db, 1, 9203, 1L, "kol:view");
    grant(db, 2, 9203, 1L, "kol:edit");
    grant(db, 3, 9204, 2L, "product:view");

    flyway(null).migrate();

    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM sys_user_tenant WHERE user_id = 9201", Integer.class))
        .isZero();
    assertMember(db, 9202, 1L, "SUPER_ADMIN", null, "ALL", 1, false);
    assertMember(db, 9203, 1L, "STAFF", "BD", "OWN", 0, false);
    assertMember(db, 9204, 2L, "STAFF", "OPERATOR", "ALL", 1, false);
    assertMember(db, 9205, 1L, "STAFF", "BD", "OWN", 1, true);
    assertThat(
            db.queryForList(
                "SELECT user_id FROM sys_user_tenant GROUP BY user_id HAVING count(*) <> 1",
                Long.class))
        .isEmpty();

    assertThat(
            db.queryForList(
                "SELECT role || ':' || coalesce(last_tenant_id::text, '-') || ':' || status"
                    + " FROM sys_user WHERE id BETWEEN 9201 AND 9205 ORDER BY id",
                String.class))
        // 9203 was disabled by its company: the membership keeps status 0, the account is enabled.
        .containsExactly(
            "PLATFORM_ADMIN:-:1", "MEMBER:1:1", "MEMBER:1:1", "MEMBER:2:1", "MEMBER:1:1");
    assertThat(
            db.queryForList(
                "SELECT tenant_id || ':' || permission_code FROM sys_user_permission"
                    + " WHERE user_id IN (9203, 9204) ORDER BY user_id, permission_code",
                String.class))
        .containsExactly("1:kol:edit", "1:kol:view", "2:product:view");

    assertThat(
            db.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'sys_user'",
                String.class))
        .contains("last_tenant_id", "status", "credential_version")
        .doesNotContain("tenant_id", "data_scope", "post_template");
    assertThat(
            db.queryForObject(
                "SELECT indexdef FROM pg_indexes"
                    + " WHERE indexname = 'uk_sys_user_permission_user_tenant_code'",
                String.class))
        .contains("(user_id, tenant_id, permission_code)");
    assertThat(
            db.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_sys_user_tenant_user_tenant'",
                String.class))
        .contains("(user_id, tenant_id)")
        .contains("is_deleted = false");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM information_schema.columns c"
                    + " WHERE c.table_name = 'sys_user_tenant'"
                    + " AND col_description('sys_user_tenant'::regclass, c.ordinal_position)"
                    + " IS NULL",
                Integer.class))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT obj_description('sys_user_tenant'::regclass, 'pg_class')", String.class))
        .isNotBlank();
  }

  private static void insert(
      JdbcTemplate db,
      long id,
      String role,
      String scope,
      String template,
      int status,
      boolean deleted,
      Long tenantId) {
    OffsetDateTime now = OffsetDateTime.now();
    db.update(
        "INSERT INTO sys_user (id, username, password_hash, nickname, role, data_scope,"
            + " post_template, status, create_time, update_time, is_deleted, tenant_id)"
            + " VALUES (?, ?, 'hash', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        "u" + id,
        "u" + id,
        role,
        scope,
        template,
        status,
        now,
        now,
        deleted,
        tenantId);
  }

  private static void grant(JdbcTemplate db, long id, long userId, long tenantId, String code) {
    db.update(
        "INSERT INTO sys_user_permission (id, tenant_id, user_id, permission_code, create_time)"
            + " VALUES (?, ?, ?, ?, ?)",
        id,
        tenantId,
        userId,
        code,
        OffsetDateTime.now());
  }

  private static void assertMember(
      JdbcTemplate db,
      long userId,
      long tenantId,
      String role,
      String template,
      String scope,
      int status,
      boolean deleted) {
    Map<String, Object> row =
        db.queryForMap(
            "SELECT tenant_id, role, post_template, data_scope, status, is_deleted"
                + " FROM sys_user_tenant WHERE user_id = ?",
            userId);
    assertThat(((Number) row.get("tenant_id")).longValue()).isEqualTo(tenantId);
    assertThat(row.get("role")).isEqualTo(role);
    assertThat(row.get("post_template")).isEqualTo(template);
    assertThat(row.get("data_scope")).isEqualTo(scope);
    assertThat(((Number) row.get("status")).intValue()).isEqualTo(status);
    assertThat(row.get("is_deleted")).isEqualTo(deleted);
  }
}
