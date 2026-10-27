package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A schema version newer than the image must fail startup in the strict (deployment) setting and
 * only warn in the lenient default that local development and the preview share (#106). The
 * application.yml default {@code *:future} and the production override (empty pattern) are both
 * exercised through the real Flyway auto-configuration.
 */
@Tag("docker")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class FlywayFutureMigrationIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.11")
          .withDatabaseName("tcrm")
          .withUsername("tcrm")
          .withPassword(UUID.randomUUID().toString());

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
          .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
          .withUserConfiguration(FlywayStartupCheckConfiguration.class)
          .withPropertyValues(
              "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
              "spring.datasource.username=" + POSTGRES.getUsername(),
              "spring.datasource.password=" + POSTGRES.getPassword(),
              "spring.flyway.locations=classpath:db/migration");

  @Test
  void shouldStartLeniently_byDefault_andFailStartup_whenStrict(CapturedOutput output)
      throws Exception {
    // First boot migrates the empty database (lenient default from application.yml).
    runner.run(context -> assertThat(context).hasNotFailed());

    insertFutureSchemaVersion();

    // The default (*:future) still boots: shared local databases outlive individual branches.
    runner.run(context -> assertThat(context).hasNotFailed());

    // The production override (empty ignore pattern) fails startup, and the log explains the
    // likely cause and the way out instead of Flyway's "run repair" suggestion.
    runner
        .withPropertyValues("spring.flyway.ignore-migration-patterns=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("not resolved locally");
            });
    assertThat(output)
        .contains("database schema is NEWER than this image")
        .contains("TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS='*:future'");
  }

  private void insertFutureSchemaVersion() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      c.createStatement()
          .execute(
              "INSERT INTO flyway_schema_history (installed_rank, version, description, type,"
                  + " script, checksum, installed_by, installed_on, execution_time, success)"
                  + " VALUES (9999, '209912312359', 'future schema', 'SQL',"
                  + " 'V209912312359__future_schema.sql', NULL, 't73', now(), 0, true)");
    }
  }
}
