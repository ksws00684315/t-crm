package com.tcrm.admin.shared;

import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds one actionable error line when Flyway fails on a schema this image does not know (#106).
 * Flyway's own text for the case suggests {@code repair}, but the usual cause is a rollback onto a
 * database migrated by a newer build; the operator needs "deploy a newer image", not "repair".
 *
 * <p>Strict checking itself is opt-in per environment: {@code application.yml} defaults {@code
 * spring.flyway.ignore-migration-patterns} to {@code *:future} (lenient — local development and the
 * preview share one database with branches of different ages); only the production template
 * overrides the pattern to strict via {@code TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS}. See {@code
 * deploy/README.md} for the exact variables and the rollback procedure.
 */
@Configuration(proxyBeanMethods = false)
class FlywayStartupCheckConfiguration {
  private static final Logger LOG = LoggerFactory.getLogger(FlywayStartupCheckConfiguration.class);

  @Bean
  FlywayMigrationStrategy flywayMigrationWithSchemaAheadHint() {
    return flyway -> {
      try {
        flyway.migrate();
      } catch (FlywayException e) {
        LOG.error(
            "Flyway failed. When the error above names an applied migration that is 'not resolved"
                + " locally', the database schema is NEWER than this image (typical after a"
                + " rollback): deploy an image that contains every applied migration, or restore"
                + " the database to a version this image knows. To let this image start anyway,"
                + " set TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS='*:future' and restart.",
            e);
        throw e;
      }
    };
  }
}
