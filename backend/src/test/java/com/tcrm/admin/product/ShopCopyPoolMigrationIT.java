package com.tcrm.admin.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("docker")
class ShopCopyPoolMigrationIT {
  @Test
  void shouldBackfillLegacyCopyTargetsAndCompanyCodesInCreationOrder() {
    try (PostgreSQLContainer postgres = newPostgres()) {
      postgres.start();
      JdbcTemplate jdbc = jdbc(postgres);
      flyway(postgres, "202610211001").migrate();
      jdbc.update(
          "INSERT INTO sys_config(id,config_key,config_value,update_time) "
              + "VALUES(1,'shop.max-per-company','3',now())");
      jdbc.update(
          "INSERT INTO crm_shop(id,tenant_id,shop_name,weidian_shop_id,copy_target,create_time,update_time,is_deleted) "
              + "VALUES(371,1,'t37_later','371',TRUE,'2026-10-20T01:00:00Z',now(),FALSE),"
              + "(372,1,'t37_earlier','372',FALSE,'2026-10-19T01:00:00Z',now(),TRUE),"
              + "(373,2,'t37_other_company','373',TRUE,'2026-10-20T01:00:00Z',now(),FALSE)");

      flyway(postgres, "202610221000").migrate();

      assertThat(jdbc.queryForList("SELECT shop_code FROM crm_shop ORDER BY id", String.class))
          .isEqualTo(List.of("SHOP-100001", "SHOP-100000", "SHOP-100000"));
      assertThat(
              jdbc.queryForList("SELECT copy_pool_status FROM crm_shop ORDER BY id", String.class))
          .isEqualTo(List.of("JOINED", "EXITED", "JOINED"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_shop WHERE channel='WEIDIAN' AND max_items=20000 "
                      + "AND current_items=0 AND is_source=FALSE",
                  Integer.class))
          .isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM information_schema.columns WHERE table_name='crm_shop' "
                      + "AND column_name='copy_target'",
                  Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT config_value FROM sys_config WHERE config_key='shop.max-per-company'",
                  String.class))
          .isEqualTo("20");
    }
  }

  @Test
  void shouldPreserveCustomizedShopLimitOverride() {
    try (PostgreSQLContainer postgres = newPostgres()) {
      postgres.start();
      JdbcTemplate jdbc = jdbc(postgres);
      flyway(postgres, "202610211001").migrate();
      jdbc.update(
          "INSERT INTO sys_config(id,config_key,config_value,update_time) "
              + "VALUES(1,'shop.max-per-company','50',now())");

      flyway(postgres, "202610221000").migrate();

      assertThat(
              jdbc.queryForObject(
                  "SELECT config_value FROM sys_config WHERE config_key='shop.max-per-company'",
                  String.class))
          .isEqualTo("50");
    }
  }

  private static PostgreSQLContainer newPostgres() {
    return new PostgreSQLContainer("postgres:17.11")
        .withDatabaseName("tcrm")
        .withUsername("tcrm")
        .withPassword(UUID.randomUUID().toString());
  }

  private static JdbcTemplate jdbc(PostgreSQLContainer postgres) {
    return new JdbcTemplate(
        new SimpleDriverDataSource(
            new org.postgresql.Driver(),
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()));
  }

  private static Flyway flyway(PostgreSQLContainer postgres, String target) {
    return Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target(target)
        .load();
  }
}
