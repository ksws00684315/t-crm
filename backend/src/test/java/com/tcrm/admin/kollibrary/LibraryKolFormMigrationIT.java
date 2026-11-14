package com.tcrm.admin.kollibrary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Task 146 migration: a creator entered under task 143 keeps its categories and cooperation stage,
 * gains an empty follow-up remark, and the table then accepts an empty category array. Runs the
 * migrations in two phases with a creator row in between, because a fresh database has no old rows.
 */
@Tag("docker")
@Testcontainers
class LibraryKolFormMigrationIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
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

  private static String insert(long id, String username, String categories, String stage) {
    return "INSERT INTO crm_library_kol (id, tenant_id, platform, kol_username, display_name,"
        + " country_code, categories, cooperation_stage, tags, outreach_status, follow_up_owner,"
        + " sample_status, create_time, update_time) VALUES ("
        + id
        + ", 1, 'TIKTOK', '"
        + username
        + "', 'Old', 'US', '"
        + categories
        + "'::jsonb, "
        + (stage == null ? "NULL" : "'" + stage + "'")
        + ", '[]'::jsonb, 'REPLIED', 'Amy BD', 'NOT_SENT', ?, ?)";
  }

  @Test
  void shouldKeepOldRowsAndAllowEmptyCategories_afterTheFormRevision() {
    // Phase 1: everything up to the task 143 tables.
    flyway("202611091701").migrate();
    JdbcTemplate db = jdbc();
    db.update(
        insert(91, "olduser", "[\"BEAUTY_PERSONAL_CARE\",\"PET\"]", "LONG_TERM_PARTNERSHIP"),
        OffsetDateTime.now(),
        OffsetDateTime.now());

    // Phase 2: the task 146 revision runs over the existing row.
    flyway(null).migrate();

    Map<String, Object> old =
        db.queryForMap(
            "SELECT categories::text AS categories, cooperation_stage, follow_up_remark,"
                + " followers, profile_url FROM crm_library_kol WHERE id = 91");
    assertThat(old.get("categories").toString()).isEqualTo("[\"BEAUTY_PERSONAL_CARE\", \"PET\"]");
    assertThat(old.get("cooperation_stage")).isEqualTo("LONG_TERM_PARTNERSHIP");
    assertThat(old.get("follow_up_remark")).isNull();
    assertThat(old.get("followers")).isNull();
    assertThat(old.get("profile_url")).isNull();

    db.update(insert(92, "newuser", "[]", null), OffsetDateTime.now(), OffsetDateTime.now());
    assertThat(
            db.queryForObject(
                "SELECT categories::text FROM crm_library_kol WHERE id = 92", String.class))
        .isEqualTo("[]");
    assertThat(
            db.queryForObject(
                "SELECT col_description('crm_library_kol'::regclass, attnum) FROM pg_attribute"
                    + " WHERE attrelid = 'crm_library_kol'::regclass"
                    + " AND attname = 'follow_up_remark'",
                String.class))
        .contains("跟进备注");
  }
}
