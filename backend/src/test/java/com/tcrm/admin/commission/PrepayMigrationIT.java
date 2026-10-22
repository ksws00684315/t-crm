package com.tcrm.admin.commission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("docker")
class PrepayMigrationIT {
  @Test
  void shouldPreserveLegacyPermissionsAndGrantReviewWithoutCollidingWithExistingIds() {
    try (var postgres =
        new PostgreSQLContainer("postgres:17.11")
            .withDatabaseName("tcrm")
            .withUsername("tcrm")
            .withPassword(UUID.randomUUID().toString())) {
      postgres.start();
      var jdbc =
          new JdbcTemplate(
              new SimpleDriverDataSource(
                  new org.postgresql.Driver(),
                  postgres.getJdbcUrl(),
                  postgres.getUsername(),
                  postgres.getPassword()));
      flyway(postgres, "202610221304").migrate();
      jdbc.update(
          "INSERT INTO sys_user(id,username,password_hash,nickname,role,status,create_time,update_time) "
              + "VALUES(4701,'t47_migration_operator','unused','t47_operator','MEMBER',1,now(),now())");
      jdbc.update(
          "INSERT INTO sys_user_tenant(id,tenant_id,user_id,role,post_template,data_scope,status,create_time,update_time) "
              + "VALUES(4701,1,4701,'STAFF','OPERATOR','ALL',1,now(),now()),"
              + "(4702,2,4701,'STAFF','OPERATOR','ALL',1,now(),now()),"
              + "(4703,3,4701,'STAFF','OPERATOR','ALL',0,now(),now()),"
              + "(4704,4,4701,'STAFF','BD','OWN',1,now(),now())");
      jdbc.update(
          "INSERT INTO sys_user_permission(id,tenant_id,user_id,permission_code,create_time) "
              + "VALUES(1,1,4701,'kol:view',now()),"
              + "(900000000000000000,1,4701,'data:export',now()),"
              + "(2,2,4701,'prepay:review',now())");

      flyway(postgres, "202610221900").migrate();

      assertThat(
              jdbc.queryForList(
                  "SELECT permission_code FROM sys_user_permission WHERE id IN (1,2,900000000000000000) ORDER BY id",
                  String.class))
          .containsExactly("kol:view", "prepay:review", "data:export");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_user_permission WHERE tenant_id=1 AND user_id=4701 AND permission_code='prepay:review' AND id>900000000000000000",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_user_permission WHERE permission_code='prepay:review'",
                  Integer.class))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_user_permission WHERE permission_code='prepay:pay'",
                  Integer.class))
          .isZero();
      flyway(postgres, "202610221900").migrate();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_user_permission", Integer.class))
          .isEqualTo(4);
    }
  }

  private static Flyway flyway(PostgreSQLContainer postgres, String target) {
    return Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target(target)
        .load();
  }
}
