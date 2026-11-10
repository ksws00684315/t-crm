package com.tcrm.admin.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.order.api.OrderStatsApi.RankDimension;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.order.mapper.OrderDailyStatsMapper;
import com.tcrm.admin.order.mapper.OrderStatsMapper;
import com.tcrm.admin.order.service.OrderStatsService;
import com.tcrm.admin.stats.mapper.StatsRollupMapper;
import com.tcrm.admin.stats.service.StatsRollupService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("docker")
@Testcontainers
class OrderStatsIT {
  private static final Logger LOG = LoggerFactory.getLogger(OrderStatsIT.class);

  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer("postgres:17.11").withPassword(UUID.randomUUID().toString());

  private static final Instant FROM = Instant.parse("2026-11-02T16:00:00Z");
  private static final Instant UNTIL = Instant.parse("2026-11-03T16:00:00Z");

  @Test
  void aggregatesCrossPeriodRefundsAndIsolatesTenantAndBusinessOwner() {
    var source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.execute(
        """
        INSERT INTO crm_order (
          id, tenant_id, shop_id, weidian_order_id, bd_user_id, kol_id, settled_time,
          attribution_status, order_status, raw_status, order_time, pay_time, total_amount,
          pay_amount, freight, discount, source, last_sync_time, create_time, update_time
        )
        SELECT id, tenant, 1, 't144-' || id, owner, 11, NULL,
               'ATTRIBUTED', 'PAID', '1', '2026-11-01T00:00:00Z', paid::timestamptz,
               amount, amount, 0, 0, 1, now(), now(), now()
        FROM (VALUES
          (1, 4, 9, '2026-11-02T16:00:00Z', 100),
          (2, 4, 9, '2026-11-02T15:59:59Z', 80),
          (3, 4, 10, '2026-11-03T00:00:00Z', 200),
          (4, 5, 9, '2026-11-03T00:00:00Z', 300),
          (5, 4, 9, '2026-11-03T16:00:00Z', 400),
          (6, 4, NULL, '2026-11-03T00:00:00Z', 50)
        ) fixture(id, tenant, owner, paid, amount)
        """);
    jdbc.execute(
        """
        INSERT INTO crm_order_item (
          id, tenant_id, order_id, sub_order_id, weidian_item_id, sku_id, item_name, sku_name,
          quantity, pay_amount, refund_amount, attribution_type, create_time, update_time
        )
        VALUES
          (1, 4, 1, 't144-a', 'A', '1', 'A', '1', 2, 60, 0, 1, now(), now()),
          (2, 4, 1, 't144-b', 'A', '2', 'A', '2', 1, 40, 0, 1, now(), now()),
          (3, 4, 1, 't144-deleted', 'A', '3', 'A', '3', 20, 0, 0, 1, now(), now())
        """);
    jdbc.update("UPDATE crm_order_item SET is_deleted = TRUE WHERE id = 3");
    jdbc.update("UPDATE crm_order_item SET kol_id = 11 WHERE tenant_id = 4");
    jdbc.execute(
        """
        INSERT INTO crm_refund (
          id, tenant_id, order_id, weidian_refund_no, amount, raw_status,
          refund_status, refund_time, finish_time, create_time, update_time
        )
        SELECT id, 4, order_id, 't144-r' || id, amount, '10', status,
               '2026-11-01T00:00:00Z', finished::timestamptz, now(), now()
        FROM (VALUES
          (1, 1, 10, 'SUCCESS', '2026-11-03T17:00:00Z'),
          (2, 1, 20, 'SUCCESS', '2026-11-03T18:00:00Z'),
          (3, 2, 15, 'SUCCESS', '2026-11-03T01:00:00Z'),
          (4, 2, 5, 'SUCCESS', '2026-11-03T02:00:00Z'),
          (5, 1, 40, 'PROCESSING', NULL),
          (6, 1, 2, 'SUCCESS', '2026-12-02T15:59:59Z'),
          (7, 1, 8, 'SUCCESS', '2026-12-02T16:00:00Z')
        ) fixture(id, order_id, amount, status, finished)
        """);
    jdbc.update("UPDATE crm_refund SET order_item_id = 1 WHERE order_id = 1");

    var configuration =
        new Configuration(new Environment("t144", new JdbcTransactionFactory(), source));
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.addMapper(OrderStatsMapper.class);
    configuration.addMapper(OrderDailyStatsMapper.class);
    configuration.addMapper(StatsRollupMapper.class);
    var factory = new SqlSessionFactoryBuilder().build(configuration);

    try (var session = factory.openSession()) {
      var mapper = session.getMapper(OrderStatsMapper.class);
      var own = mapper.totals(new ScopeDTO(4, 9L, 1L, 11L, 9L), FROM, UNTIL);
      assertThat(own.paidOrders()).isEqualTo(1);
      assertThat(own.units()).isEqualTo(3);
      assertThat(own.sales()).isEqualByComparingTo("100");
      assertThat(own.cohortRefunds()).isEqualByComparingTo("32");
      assertThat(own.completedRefunds()).isEqualByComparingTo("20");
      assertThat(own.refundedOrders()).isEqualTo(1);
      assertThat(own.updatedAt()).isNotNull();

      var all = mapper.totals(new ScopeDTO(4, null, null, null, null), FROM, UNTIL);
      assertThat(all.paidOrders()).isEqualTo(3);
      assertThat(all.sales()).isEqualByComparingTo("350");

      var next =
          mapper.totals(
              new ScopeDTO(4, 9L, null, null, null), UNTIL, Instant.parse("2026-11-04T16:00:00Z"));
      assertThat(next.sales()).isEqualByComparingTo("400");
      assertThat(next.cohortRefunds()).isEqualByComparingTo("0");
      assertThat(next.completedRefunds()).isEqualByComparingTo("30");
      assertThat(next.refundedOrders()).isEqualTo(1);

      var empty = mapper.totals(new ScopeDTO(4, 9L, null, null, 10L), FROM, UNTIL);
      assertThat(empty.paidOrders()).isZero();
      assertThat(empty.sales()).isEqualByComparingTo("0");
      assertThat(empty.updatedAt()).isNull();

      var shop =
          mapper
              .rankFacts(new ScopeDTO(4, 9L, null, null, null), RankDimension.SHOP, FROM, UNTIL)
              .getFirst();
      assertThat(shop.paidOrders()).isEqualTo(1);
      assertThat(shop.spuCount()).isEqualTo(1);
      assertThat(shop.skuCount()).isEqualTo(2);
      assertThat(shop.completedRefunds()).isEqualByComparingTo("20");

      var daily = session.getMapper(OrderDailyStatsMapper.class);
      var productScope = new ScopeDTO(4, 9L, null, 11L, null, java.util.List.of(1L));
      var products = daily.productFacts(productScope, "a", FROM, UNTIL, false);
      assertThat(products).hasSize(2);
      var product = products.stream().filter(row -> row.shopId() != null).findFirst().orElseThrow();
      assertThat(product.spu()).isEqualTo("A");
      assertThat(product.paidOrders()).isEqualTo(1);
      assertThat(product.units()).isEqualTo(3);
      assertThat(product.sales()).isEqualByComparingTo("100");
      assertThat(product.cohortRefunds()).isEqualByComparingTo("32");
      assertThat(product.cohortRefundedOrders()).isEqualTo(1);
      assertThat(product.completedRefunds()).isZero();
      assertThat(daily.productFacts(productScope, "not-matching", FROM, UNTIL, false))
          .singleElement()
          .satisfies(total -> assertThat(total.paidOrders()).isZero());
      assertThat(daily.productFacts(productScope, null, FROM, UNTIL, true))
          .singleElement()
          .satisfies(row -> assertThat(row.day()).isEqualTo(LocalDate.of(2026, 11, 3)));
      assertThat(mapper.sellingCreators(productScope, FROM, UNTIL)).isEqualTo(1);
      assertThat(mapper.rankHighlights(productScope, RankDimension.KOL, FROM, UNTIL))
          .singleElement()
          .satisfies(top -> assertThat(top.name()).isEqualTo("A"));
      assertThat(mapper.rankHighlights(productScope, RankDimension.INVITER, FROM, UNTIL))
          .singleElement()
          .satisfies(top -> assertThat(top.creatorId()).isEqualTo(11L));
      var dailyFacts = daily.rollup(4, FROM, UNTIL);
      assertThat(dailyFacts.stream().filter(row -> "ITEM".equals(row.grain())))
          .extracting(row -> row.skuCode())
          .containsExactlyInAnyOrder("1", "2");
      var owner =
          dailyFacts.stream()
              .filter(row -> "ORDER".equals(row.grain()) && row.ownerId() == 9)
              .findFirst()
              .orElseThrow();
      assertThat(owner.sales()).isEqualByComparingTo("100");
      assertThat(owner.units()).isEqualTo(3);
      assertThat(owner.cohortRefunds()).isEqualByComparingTo("32");
      assertThat(owner.completedRefunds()).isEqualByComparingTo("20");

      var now = Instant.now().plusSeconds(10);
      assertThat(daily.changedDays(4, Instant.EPOCH, now)).contains(LocalDate.of(2026, 11, 3));
      var rollupMapper = session.getMapper(StatsRollupMapper.class);
      var service = new StatsRollupService(new OrderStatsService(mapper, daily), rollupMapper);
      var day = LocalDate.of(2026, 11, 3);
      service.enqueue(4, day, day.plusDays(1), now);
      session.commit();
      assertThat(service.rebuildNext(4, now)).isTrue();
      session.rollback();
      assertThat(service.rebuildNext(4, now)).isTrue();
      session.commit();
      assertThat(service.rebuildNext(4, now)).isFalse();
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM agg_stats_daily WHERE tenant_id = 4 AND stat_date = '2026-11-03'",
                  Integer.class))
          .isEqualTo(dailyFacts.size());
    }
    measureNinetyDayQueries(jdbc, factory);
  }

  private static void measureNinetyDayQueries(JdbcTemplate jdbc, SqlSessionFactory factory) {
    jdbc.execute(
        """
        INSERT INTO crm_order (
          id, tenant_id, shop_id, weidian_order_id, bd_user_id, kol_id, attribution_status,
          order_status, raw_status, order_time, pay_time, total_amount, pay_amount, freight,
          discount, source, last_sync_time, create_time, update_time
        )
        SELECT g+1000000, 20, g%5+1, 't144-perf-'||g, g%10+1, g%2000+1, 'ATTRIBUTED',
               'PAID', '1', '2026-08-06T00:00:00Z', '2026-08-06T00:00:00Z'::timestamptz
                 + (g%90)*interval '1 day', 100, 100, 0, 0, 1, now(), now(), now()
        FROM generate_series(1,100000) g
        """);
    jdbc.execute(
        """
        INSERT INTO crm_order_item (
          id, tenant_id, order_id, sub_order_id, weidian_item_id, sku_id, item_name, sku_name,
          quantity, pay_amount, refund_amount, attribution_type, create_time, update_time
        )
        SELECT g+2000000, 20, (g-1)/2+1000001, 't144-perf-line-'||g,
               'I'||(g%400), 'S'||(g%800), 'Item', 'SKU', 1, 50, 0, 1, now(), now()
        FROM generate_series(1,200000) g
        """);
    jdbc.execute("ANALYZE crm_order");
    jdbc.execute("ANALYZE crm_order_item");
    jdbc.execute("ANALYZE crm_refund");
    var scope = new ScopeDTO(20, null, null, null, null);
    var from = Instant.parse("2026-08-05T16:00:00Z");
    var until = Instant.parse("2026-11-03T16:00:00Z");
    try (var session = factory.openSession()) {
      var mapper = session.getMapper(OrderStatsMapper.class);
      long started = System.nanoTime();
      var totals = mapper.totals(scope, from, until);
      long totalsMillis = (System.nanoTime() - started) / 1_000_000;
      assertThat(totals.paidOrders()).isEqualTo(100000);
      assertThat(totals.units()).isEqualTo(200000);
      started = System.nanoTime();
      var rank = mapper.rankFacts(scope, RankDimension.KOL, from, until);
      long rankMillis = (System.nanoTime() - started) / 1_000_000;
      assertThat(rank).hasSize(2000);
      assertThat(rank.stream().mapToLong(row -> row.paidOrders()).sum()).isEqualTo(100000);
      LOG.info(
          "Task 144 scale fixture: overview {} ms, creator grouping {} ms",
          totalsMillis,
          rankMillis);
    }
    for (String name : new String[] {"totals", "rankFacts"}) {
      var statement =
          factory
              .getConfiguration()
              .getMappedStatement(OrderStatsMapper.class.getName() + "." + name);
      var params =
          Map.of("scope", scope, "from", from, "until", until, "dimension", RankDimension.KOL);
      var bound = statement.getBoundSql(params);
      jdbc.execute(
          (ConnectionCallback<Void>)
              connection -> {
                try (var query =
                    connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + bound.getSql())) {
                  new DefaultParameterHandler(statement, params, bound).setParameters(query);
                  try (var plan = query.executeQuery()) {
                    while (plan.next()) {
                      LOG.info("Task 144 {} plan: {}", name, plan.getString(1));
                    }
                  }
                }
                return null;
              });
    }
  }
}
