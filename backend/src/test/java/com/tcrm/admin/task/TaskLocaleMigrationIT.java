package com.tcrm.admin.task;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Task 06d migration: rows that existed before the {@code crm_task.locale} column (all Chinese
 * error files back then) must become {@code zh-CN}, while the column accepts both languages with no
 * default so new rows must state one. Runs the migrations in two phases with a task row in between,
 * because a fresh database never has legacy rows.
 */
@Tag("docker")
@Testcontainers
class TaskLocaleMigrationIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer("postgres:17.11")
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
  void shouldBackfillExistingTasksAsChinese_andKeepBothLanguagesAllowed() {
    // Phase 1: everything before task 06d.
    flyway("202610111800").migrate();
    JdbcTemplate db = jdbc();
    db.update(
        "INSERT INTO crm_task (id, task_type, status, total, done, failed, create_time,"
            + " update_time, is_deleted, tenant_id) VALUES (91, 'KOL_IMPORT', 'SUCCESS', 1, 1, 0,"
            + " ?, ?, FALSE, 1)",
        OffsetDateTime.now(),
        OffsetDateTime.now());

    // Phase 2: the locale migration runs over the existing row.
    flyway(null).migrate();

    List<Map<String, Object>> rows = db.queryForList("SELECT locale FROM crm_task WHERE id = 91");
    assertThat(rows)
        .singleElement()
        .satisfies(row -> assertThat(row.get("locale")).isEqualTo("zh-CN"));
    assertThat(
            db.queryForObject(
                "SELECT column_default FROM information_schema.columns"
                    + " WHERE table_name = 'crm_task' AND column_name = 'locale'",
                String.class))
        .as("new tasks must state their language explicitly")
        .isNull();
    db.update(
        "INSERT INTO crm_task (id, task_type, status, total, done, failed, locale, create_time,"
            + " update_time, is_deleted, tenant_id) VALUES (92, 'KOL_IMPORT', 'SUCCESS', 1, 1, 0,"
            + " 'en-US', ?, ?, FALSE, 1)",
        OffsetDateTime.now(),
        OffsetDateTime.now());
    db.update(
        "INSERT INTO crm_task (id, task_type, status, total, done, failed, locale, create_time,"
            + " update_time, is_deleted, tenant_id) VALUES (93, 'KOL_IMPORT', 'SUCCESS', 1, 1, 0,"
            + " 'zh-CN', ?, ?, FALSE, 1)",
        OffsetDateTime.now(),
        OffsetDateTime.now());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM crm_task WHERE locale NOT IN ('zh-CN', 'en-US')",
                Integer.class))
        .isZero();
  }
}
