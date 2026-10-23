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
              + "(2,2,4701,'prepay:review',now()),"
              + "(3,2,4701,'prepay:pay',now())");

      flyway(postgres, "202610221500").migrate();
      jdbc.update(
          "INSERT INTO crm_notification(id,tenant_id,user_id,type,params,create_time) VALUES(4701,1,4701,'DEPARTURE_PLAN_SUBMITTED','{}'::jsonb,now())");
      flyway(postgres, "202610221900").migrate();
      assertThat(
              jdbc.queryForObject("SELECT type FROM crm_notification WHERE id=4701", String.class))
          .isEqualTo("DEPARTURE_PLAN_SUBMITTED");

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
          .isEqualTo(1);
      jdbc.update(
          "INSERT INTO crm_kol(id,tenant_id,kol_code,name,email,country,bd_user_id,status,create_time,update_time) "
              + "VALUES(4710,1,'KOL-ABC4710','t47_complete','complete@example.test','US',4701,1,now(),now()),"
              + "(4711,1,'KOL-ABC4711','t47_incomplete','incomplete@example.test','US',4701,1,now(),now()),"
              + "(4712,1,'KOL-ABC4712','t47_single','single@example.test','US',4701,1,now(),now())");
      jdbc.update(
          "INSERT INTO crm_prepay(id,tenant_id,kol_id,prepay_no,request_id,applicant_id,amount_cny,reason,expected_pay_date,deduct_from_commission,payout_account_id,status,approved_by,approved_time,create_time,update_time) "
              + "VALUES(4710,1,4710,'PP0470001',?::uuid,4701,100,'reason',CURRENT_DATE,TRUE,44,'PENDING_APPROVAL',NULL,NULL,now()-interval '1 day',now()),"
              + "(4711,1,4710,'PP0470002',?::uuid,4701,100,'reason',CURRENT_DATE,TRUE,44,'APPROVED',4701,now(),now(),now()),"
              + "(4712,1,4711,'PP0470003',?::uuid,4701,100,NULL,NULL,NULL,NULL,'PENDING_APPROVAL',NULL,NULL,now(),now()),"
              + "(4713,1,4712,'PP0470004',?::uuid,4701,100,'reason',CURRENT_DATE,TRUE,44,'PENDING_APPROVAL',NULL,NULL,now(),now())",
          UUID.randomUUID().toString(),
          UUID.randomUUID().toString(),
          UUID.randomUUID().toString(),
          UUID.randomUUID().toString());
      flyway(postgres, "202610222000").migrate();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM sys_user_permission WHERE tenant_id=2 AND user_id=4701 AND permission_code='prepay:finance-review'",
                  Integer.class))
          .isEqualTo(1);
      assertThat(jdbc.queryForList("SELECT status FROM crm_prepay ORDER BY id", String.class))
          .containsExactly("REJECTED", "APPROVED", "WAITING_DETAILS", "PENDING_OPERATIONS_REVIEW");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM crm_prepay_history WHERE prepay_id=4710 AND action='REJECTED' AND operator_id=0 AND reason LIKE '系统迁移关闭重复申请%'",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT indexdef FROM pg_indexes WHERE indexname='uk_crm_prepay_one_active_per_kol'",
                  String.class))
          .contains("tenant_id, kol_id")
          .contains("WAITING_DETAILS", "PENDING_OPERATIONS_REVIEW");
      flyway(postgres, "202610231100").migrate();
      assertThat(jdbc.queryForList("SELECT status FROM crm_prepay ORDER BY id", String.class))
          .containsExactly("REJECTED", "APPROVED", "WAITING_DETAILS", "PENDING_OPERATIONS_REVIEW");
      assertThat(
              jdbc.queryForObject(
                  "SELECT operator_role FROM crm_prepay_history WHERE prepay_id=4710",
                  String.class))
          .isEqualTo("SYSTEM");
      String largeCipher = "v1:" + "x".repeat(6000);
      jdbc.update(
          "UPDATE crm_prepay SET payout_account_type='MANUAL', payout_account_id=NULL, payout_account_cipher=? WHERE id=4712",
          largeCipher);
      assertThat(
              jdbc.queryForObject(
                  "SELECT payout_account_cipher FROM crm_prepay WHERE id=4712", String.class))
          .isEqualTo(largeCipher);
      flyway(postgres, "202610231000").migrate();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_user_permission", Integer.class))
          .isEqualTo(6);
      flyway(postgres, "202610240517").migrate();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('crm_commission_item_state','crm_commission_item_revision','crm_commission_withdrawal','crm_commission_withdrawal_allocation','crm_commission_withdrawal_payment','crm_commission_withdrawal_history')",
                  Integer.class))
          .isEqualTo(6);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('crm_commission_wallet','crm_commission_ledger')",
                  Integer.class))
          .isEqualTo(2);
    }
  }

  private static Flyway flyway(PostgreSQLContainer postgres, String target) {
    return Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .target(target)
        .load();
  }
}
