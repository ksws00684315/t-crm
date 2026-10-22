package com.tcrm.admin.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.security.api.PostTemplate;
import java.time.OffsetDateTime;
import java.util.List;
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
 * Converts OPERATOR and FINANCE accounts that already exist before {@code V202610171000}. A fresh
 * database has no such rows, so the migration is applied in two phases, the second one up to that
 * version only.
 */
@Tag("docker")
@Testcontainers
class ConfigurablePermissionsMigrationIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.11")
          .withDatabaseName("tcrm")
          .withUsername("tcrm")
          .withPassword(UUID.randomUUID().toString());

  /**
   * Codes that {@code V202610171000} wrote for legacy OPERATOR/ALL rows. Kept as a fixed list so
   * later changes to {@link PostTemplate#OPERATOR} (for example adding {@code kol:edit}) do not
   * rewrite history.
   */
  private static final List<String> OPERATOR_ALL =
      List.of(
          "kol:view",
          "product:view",
          "product:edit",
          "product:status",
          "order:view",
          "order:manage",
          "dashboard:shop",
          "dashboard:kol",
          "data:export");

  private static final List<String> FINANCE =
      List.of(
          "kol:view",
          "product:view",
          "order:view",
          "commission:view",
          "dashboard:shop",
          "dashboard:team",
          "dashboard:kol",
          "data:export");

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
  void shouldConvertLegacyRoles_andAssignRowNumberIds() {
    flyway("202610121800").migrate();
    JdbcTemplate db = jdbc();
    insert(db, 9101, "OPERATOR", "ALL", false, 1L);
    insert(db, 9102, "OPERATOR", "OWN", false, 1L);
    insert(db, 9103, "FINANCE", "ALL", false, 1L);
    insert(db, 9104, "SUPER_ADMIN", "ALL", false, 1L);
    insert(db, 9105, "PLATFORM_ADMIN", "ALL", false, null);
    insert(db, 9106, "OPERATOR", "OWN", true, 1L);

    // Stop at this migration: V202610172300 (ADR-022) later moves role, scope and template to
    // sys_user_tenant; MultiCompanyMembershipMigrationIT covers that step.
    flyway("202610171000").migrate();

    assertUser(db, 9101, "STAFF", "OPERATOR", "ALL");
    assertUser(db, 9102, "STAFF", "BD", "OWN");
    assertUser(db, 9103, "STAFF", null, "ALL");
    assertUser(db, 9104, "SUPER_ADMIN", null, "ALL");
    assertUser(db, 9105, "PLATFORM_ADMIN", null, "ALL");
    assertUser(db, 9106, "STAFF", "BD", "OWN");
    assertThat(flag(db, 9106)).isTrue();

    assertThat(codes(db, 9101))
        .containsExactlyInAnyOrderElementsOf(OPERATOR_ALL);
    assertThat(codes(db, 9102))
        .containsExactlyInAnyOrderElementsOf(PostTemplate.BD.permissionCodes());
    assertThat(codes(db, 9103)).containsExactlyInAnyOrderElementsOf(FINANCE);
    assertThat(codes(db, 9104)).isEmpty();
    assertThat(codes(db, 9105)).isEmpty();
    assertThat(codes(db, 9106))
        .containsExactlyInAnyOrderElementsOf(PostTemplate.BD.permissionCodes());

    Map<String, Object> ids =
        db.queryForMap(
            "SELECT min(id) AS min_id, max(id) AS max_id, count(*) AS n"
                + " FROM sys_user_permission");
    assertThat(((Number) ids.get("min_id")).longValue()).isEqualTo(1L);
    assertThat(((Number) ids.get("max_id")).longValue()).isEqualTo(43L);
    assertThat(((Number) ids.get("n")).longValue()).isEqualTo(43L);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM sys_user WHERE role IN ('OPERATOR', 'FINANCE')",
                Integer.class))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relkind = 'S' AND relname LIKE '%permission%'",
                Integer.class))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_sys_user_permission_user_code'",
                String.class))
        .contains("(user_id, permission_code)")
        .doesNotContain("tenant_id");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_sys_user_permission_tenant_code'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_sys_user_role'",
                String.class))
        .contains("STAFF")
        .doesNotContain("OPERATOR")
        .doesNotContain("FINANCE");
  }

  private static void insert(
      JdbcTemplate db, long id, String role, String scope, boolean deleted, Long tenantId) {
    OffsetDateTime now = OffsetDateTime.now();
    if (tenantId == null) {
      db.update(
          "INSERT INTO sys_user (id, username, password_hash, nickname, role, data_scope, status,"
              + " create_time, update_time, is_deleted, tenant_id) VALUES (?, ?, 'hash', ?, ?, ?,"
              + " 1, ?, ?, ?, NULL)",
          id,
          "u" + id,
          "u" + id,
          role,
          scope,
          now,
          now,
          deleted);
      return;
    }
    db.update(
        "INSERT INTO sys_user (id, username, password_hash, nickname, role, data_scope, status,"
            + " create_time, update_time, is_deleted, tenant_id) VALUES (?, ?, 'hash', ?, ?, ?, 1,"
            + " ?, ?, ?, ?)",
        id,
        "u" + id,
        "u" + id,
        role,
        scope,
        now,
        now,
        deleted,
        tenantId);
  }

  private static void assertUser(
      JdbcTemplate db, long id, String role, String template, String scope) {
    Map<String, Object> row =
        db.queryForMap("SELECT role, post_template, data_scope FROM sys_user WHERE id = ?", id);
    assertThat(row.get("role")).isEqualTo(role);
    assertThat(row.get("post_template")).isEqualTo(template);
    assertThat(row.get("data_scope")).isEqualTo(scope);
  }

  private static boolean flag(JdbcTemplate db, long id) {
    return Boolean.TRUE.equals(
        db.queryForObject("SELECT is_deleted FROM sys_user WHERE id = ?", Boolean.class, id));
  }

  private static List<String> codes(JdbcTemplate db, long userId) {
    return db.queryForList(
        "SELECT permission_code FROM sys_user_permission WHERE user_id = ?", String.class, userId);
  }
}
