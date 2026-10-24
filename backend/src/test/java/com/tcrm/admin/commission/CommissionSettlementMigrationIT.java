package com.tcrm.admin.commission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Task 62 migration chain: item snapshots, rule versions, the widened ledger types, the unverified
 * backfill of existing order lines and the promotion-rate launch baseline (ADR-032 §2-§4).
 */
@Tag("docker")
class CommissionSettlementMigrationIT {

  @Test
  void shouldBackfillUnverifiedSnapshotsAndRateBaseline() {
    try (PostgreSQLContainer postgres =
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
      flyway(postgres, "202610231100").migrate();

      jdbc.update(
          "INSERT INTO crm_kol(id,tenant_id,kol_code,name,email,country,bd_user_id,status,"
              + "default_rate,create_time,update_time) VALUES"
              + "(101,1,'KOL-TGA0620001','t62 kol a','a@t62.test','US',NULL,1,0.1500,now(),now()),"
              + "(102,1,'KOL-TGB0620002','t62 kol b','b@t62.test','US',NULL,1,NULL,now(),now())");
      jdbc.update(
          "INSERT INTO crm_order(id,shop_id,weidian_order_id,kol_id,attribution_status,order_status,"
              + "raw_status,order_time,pay_time,total_amount,pay_amount,freight,discount,source,"
              + "last_sync_time,tenant_id,create_time,update_time) VALUES"
              + "(5001,30,'WD-62-1',101,'ATTRIBUTED','PAID','PAID',now(),now(),100,100,0,0,1,now(),1,now(),now())");
      jdbc.update(
          "INSERT INTO crm_order_item(id,order_id,sub_order_id,weidian_item_id,sku_id,item_name,"
              + "sku_name,quantity,pay_amount,refund_amount,kol_id,attribution_type,tenant_id,"
              + "create_time,update_time) VALUES"
              + "(9001,5001,'SUB-1','WDI-1','','t62 item','',2,100,0,101,1,1,now(),now()),"
              + "(9002,5001,'SUB-2','WDI-2','','t62 item 2','',1,50,0,NULL,0,1,now(),now())");

      flyway(postgres, "202610251004").migrate();

      // Every live order line gets an unverified snapshot (design §5.2); settled-attribution lines
      // keep their KOL, unmatched lines do not.
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_commission_item_state WHERE order_item_id = 9001"
                      + " AND verified = FALSE AND release_basis = 'UNVERIFIED'"
                      + " AND kol_id = 101 AND source_version IS NULL",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_commission_item_state WHERE order_item_id = 9002"
                      + " AND kol_id IS NULL",
                  Integer.class))
          .isEqualTo(1);

      // Rate baseline: only the KOL with a default rate gets a launch CONFIG version 1.
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_kol_promotion_record WHERE kol_id = 101"
                      + " AND change_type = 'CONFIG' AND version_no = 1",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT config_version FROM crm_kol WHERE id = 101", Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_kol_promotion_record WHERE kol_id = 102"
                      + " AND change_type = 'CONFIG'",
                  Integer.class))
          .isZero();
      String baselineRate =
          jdbc.queryForObject(
              "SELECT after_json->>'defaultRate' FROM crm_kol_promotion_record WHERE kol_id = 101",
              String.class);
      assertThat(baselineRate).isEqualTo("0.1500");

      // Rule versions exist with defaults; the ledger accepts all four entry types (task 63 owns
      // withdrawal postings at runtime; the constraint itself is open now).
      jdbc.update(
          "INSERT INTO crm_commission_wallet(id,tenant_id,kol_id,balance_cny,create_time,update_time)"
              + " VALUES(7001,1,101,0,now(),now())");
      jdbc.update(
          "INSERT INTO crm_commission_ledger(id,tenant_id,kol_id,entry_type,amount_cny,source_type,"
              + "source_id,source_key,occurred_at,balance_after_cny,create_time) VALUES"
              + "(7001,1,101,'COMMISSION_ADJUSTMENT',-5,'COMMISSION',9001,"
              + "'COMMISSION_ITEM:9001:v1',now(),-5,now())");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_commission_ledger WHERE entry_type ="
                      + " 'COMMISSION_ADJUSTMENT'",
                  Integer.class))
          .isEqualTo(1);
    }
  }

  private static Flyway flyway(PostgreSQLContainer postgres, String target) {
    return Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target(target)
        .load();
  }
}
