package com.tcrm.admin;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;

/** Matches the application's session-level locks for online PostgreSQL migrations. */
public final class PostgresTestMigrations {
  private PostgresTestMigrations() {}

  public static FluentConfiguration configure() {
    var config = Flyway.configure();
    config
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    return config;
  }
}
