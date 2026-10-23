package com.tcrm.admin.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.commission.mapper.WithdrawalDetailMapper;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL migration and mapper contract; no shared service or database is modified. */
@Tag("docker")
class WithdrawalDetailIT {
  @Test
  void migratesAndPagesOrdersWithTenantIsolationAndHistoricalNulls() {
    try (var pg =
        new PostgreSQLContainer("postgres:17.11").withPassword(UUID.randomUUID().toString())) {
      pg.start();
      var source =
          new SimpleDriverDataSource(
              new org.postgresql.Driver(), pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
      Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
      var jdbc = new JdbcTemplate(source);
      assertThat(
              jdbc.queryForObject(
                  "SELECT obj_description('crm_withdrawal_order_snapshot'::regclass)",
                  String.class))
          .contains("Immutable");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM pg_attribute WHERE attrelid='crm_withdrawal_order_snapshot'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL",
                  Integer.class))
          .isZero();
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO crm_withdrawal_detail(id,kol_id,detail_json,account_cipher,create_time) VALUES(1,3,'{}','cipher',now())"))
          .isInstanceOf(DataIntegrityViolationException.class);
      jdbc.update(
          "INSERT INTO crm_withdrawal_detail(id,tenant_id,kol_id,detail_json,account_cipher,create_time) VALUES(1,11,3,'{}','cipher',now()),(2,12,4,'{}','cipher',now())");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO crm_withdrawal_detail(id,tenant_id,kol_id,detail_json,account_cipher,create_time) VALUES(3,11,3,'{\"account\":{\"account\":\"plaintext\"}}','cipher',now())"))
          .isInstanceOf(DataIntegrityViolationException.class);
      for (long id = 1; id <= 11; id++) {
        jdbc.update(
            "INSERT INTO crm_withdrawal_order_snapshot(id,tenant_id,withdrawal_id,order_no,products_json,occupied,remaining_snapshot,create_time) VALUES(?,11,1,?,'[{\"id\":\"p1\",\"product\":\"Long name\",\"sku\":\"L\"},{\"id\":\"p2\",\"product\":\"Other\",\"sku\":\"M\"}]',10,?,now())",
            id,
            "t24_ORDER" + id,
            id == 11 ? null : new java.math.BigDecimal("5.00"));
      }
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO crm_withdrawal_order_snapshot(id,tenant_id,withdrawal_id,order_no,products_json,occupied,create_time) VALUES(20,11,1,'t24_ORDER1','[]',10,now())"))
          .isInstanceOf(DataIntegrityViolationException.class);
      var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
      config.setMapUnderscoreToCamelCase(true);
      config.addMapper(WithdrawalDetailMapper.class);
      try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
        var mapper = session.getMapper(WithdrawalDetailMapper.class);
        assertThat(mapper.find(12, 1)).isNull();
        assertThat(mapper.find(11, 1).kolId()).isEqualTo(3L);
        assertThat(mapper.countOrders(11, 1)).isEqualTo(11);
        assertThat(mapper.countOrders(12, 1)).isZero();
        assertThat(mapper.orders(11, 1, 10, 0)).hasSize(10);
        assertThat(mapper.orders(11, 1, 10, 0).getFirst().remainingSnapshot())
            .isEqualByComparingTo("5.00");
        var last = mapper.orders(11, 1, 10, 10);
        assertThat(last).hasSize(1);
        assertThat(last.getFirst().orderNo()).isEqualTo("t24_ORDER11");
        assertThat(last.getFirst().remainingSnapshot()).isNull();
        assertThat(last.getFirst().productsJson()).contains("p1", "p2");
      }
    }
  }
}
