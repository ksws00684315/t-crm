package com.tcrm.admin.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.order.api.OrderEvidenceApi.Kind;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductBreakdown;
import com.tcrm.admin.order.api.OrderEvidenceApi.Selection;
import com.tcrm.admin.order.api.OrderStatsApi.RankDimension;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.order.mapper.OrderDailyStatsMapper;
import com.tcrm.admin.order.mapper.OrderEvidenceMapper;
import com.tcrm.admin.order.mapper.OrderSettlementFlowMapper;
import com.tcrm.admin.order.mapper.OrderStatsMapper;
import com.tcrm.admin.order.service.OrderStatsService;
import com.tcrm.admin.stats.mapper.StatsRollupMapper;
import com.tcrm.admin.stats.service.StatsRollupService;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
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
    jdbc.update("UPDATE crm_order_item SET kol_id = 11, kol_item_id = 101 WHERE tenant_id = 4");
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

    jdbc.execute(
        """
        INSERT INTO crm_order_settlement_flow (
          id, tenant_id, shop_id, weidian_order_id, refund_id, trade_sub_type, trade_time,
          amount_raw, query_type, fingerprint, first_pulled_at, last_pulled_at, create_time, update_time
        )
        SELECT id, tenant, shop, CASE WHEN id IN (1, 2) THEN 't144-1' ELSE 't144-flow-' || id END,
               CASE WHEN id = 2 THEN 't144-r1' END, subtype, posted::timestamptz,
               amount, query_type, lpad(id::text, 64, '0'), now(), now(), now(), now()
        FROM (VALUES
          (1, 4, 1, '货款收入', '2026-11-02T16:00:00Z', 100.75, 'INCOME_DETAIL'),
          (2, 4, 1, '退款', '2026-11-03T15:59:59Z', -20.25, 'INCOME_DETAIL'),
          (3, 4, 1, '交易手续费', '2026-11-03T09:00:00Z', -10, 'INCOME_DETAIL'),
          (4, 4, 1, '分润', '2026-11-03T09:00:00Z', -30, 'INCOME_DETAIL'),
          (5, 4, 1, '退款', '2026-11-03T09:00:00Z', -50, 'PRE_SETTLE_REFUND'),
          (6, 4, 1, '货款收入', '2026-11-03T16:00:00Z', 200, 'INCOME_DETAIL'),
          (7, 5, 1, '货款收入', '2026-11-03T09:00:00Z', 300, 'INCOME_DETAIL'),
          (8, 4, 2, '货款收入', '2026-11-03T09:00:00Z', 400, 'INCOME_DETAIL'),
          (9, 4, 3, '货款收入', '2026-11-03T09:00:00Z', NULL, 'INCOME_DETAIL')
        ) fixture(id, tenant, shop, subtype, posted, amount, query_type)
        """);
    var configuration =
        new Configuration(new Environment("t144", new JdbcTransactionFactory(), source));
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.addMapper(OrderStatsMapper.class);
    configuration.addMapper(OrderSettlementFlowMapper.class);
    configuration.addMapper(OrderDailyStatsMapper.class);
    configuration.addMapper(OrderEvidenceMapper.class);
    configuration.addMapper(KolMapper.class);
    configuration.addMapper(SysUserTenantMapper.class);
    configuration.addMapper(StatsRollupMapper.class);
    var factory = new SqlSessionFactoryBuilder().build(configuration);

    try (var session = factory.openSession()) {
      var mapper = session.getMapper(OrderStatsMapper.class);
      var settlements = session.getMapper(OrderSettlementFlowMapper.class);
      var settlement = settlements.shopReceivables(4, java.util.List.of(1L), FROM, UNTIL);
      assertThat(settlement.increase()).isEqualByComparingTo("100.75");
      assertThat(settlement.decrease()).isEqualByComparingTo("20.25");
      assertThat(settlement.net()).isEqualByComparingTo("80.50");
      assertThat(settlements.countShopReceivables(4, 1, FROM, UNTIL)).isEqualTo(2);
      var flows = settlements.pageShopReceivables(4, 1, FROM, UNTIL, 20, 0);
      assertThat(flows)
          .satisfiesExactly(
              refund -> {
                assertThat(refund.amountType()).isEqualTo("REFUND");
                assertThat(refund.amount()).isEqualByComparingTo("-20.25");
                assertThat(refund.eventTime()).isEqualTo(UNTIL.minusSeconds(1));
                assertThat(refund.product()).isEqualTo("A");
                assertThat(refund.sku()).isEqualTo("1");
                assertThat(refund.spuId()).isEqualTo("A");
              },
              income -> {
                assertThat(income.amountType()).isEqualTo("INCREASE");
                assertThat(income.eventTime()).isEqualTo(FROM);
                assertThat(income.itemCount()).isEqualTo(2);
                assertThat(income.product()).isNull();
                assertThat(income.spuId()).isEqualTo("A");
              });
      jdbc.update("UPDATE crm_order_item SET weidian_item_id = 'B' WHERE id = 2");
      session.clearCache();
      assertThat(settlements.pageShopReceivables(4, 1, FROM, UNTIL, 1, 1))
          .singleElement()
          .satisfies(flow -> assertThat(flow.spuId()).isNull());
      jdbc.update("UPDATE crm_order_item SET weidian_item_id = 'A' WHERE id = 2");
      assertThat(settlements.pageShopReceivables(4, 1, FROM, UNTIL, 1, 1))
          .singleElement()
          .satisfies(flow -> assertThat(flow.id()).isEqualTo(1L));
      assertThat(settlements.shopReceivables(4, java.util.List.of(3L), FROM, UNTIL)).isNull();
      assertThat(settlements.shopReceivables(4, java.util.List.of(4L), FROM, UNTIL)).isNull();
      var own = mapper.totals(new ScopeDTO(4, 9L, 1L, 11L, 9L), FROM, UNTIL);
      assertThat(own.paidOrders()).isEqualTo(1);
      assertThat(own.units()).isEqualTo(3);
      assertThat(own.sales()).isEqualByComparingTo("100");
      assertThat(own.cohortRefunds()).isEqualByComparingTo("32");
      assertThat(own.completedRefunds()).isEqualByComparingTo("20");
      assertThat(own.refundedOrders()).isEqualTo(1);
      assertThat(own.updatedAt()).isNotNull();

      var evidence = session.getMapper(OrderEvidenceMapper.class);
      var scope = new ScopeDTO(4, 9L, 1L, 11L, null, java.util.List.of(1L));
      var shopEvidence = new Selection(scope, null, null, null, null, FROM, UNTIL);
      var productEvidence = new Selection(scope, null, null, "A", null, FROM, UNTIL);
      assertThat(evidence.count(shopEvidence, Kind.ORDERS)).isEqualTo(2);
      assertThat(evidence.page(shopEvidence, Kind.ORDERS, 1, 1))
          .singleElement()
          .satisfies(row -> assertThat(row.units()).isEqualTo(2));
      assertThat(evidence.page(shopEvidence, Kind.REFUNDS, 20, 0))
          .extracting(row -> row.refundNo())
          .containsExactly("t144-r4", "t144-r3");
      assertThat(evidence.count(productEvidence, Kind.REFUNDS)).isZero();
      assertThat(evidence.countProductGroups(productEvidence, ProductBreakdown.SKUS)).isEqualTo(2);
      assertThat(evidence.productGroups(productEvidence, ProductBreakdown.SKUS, 1, 0))
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.code()).isEqualTo("2");
                assertThat(row.netSalesAmount()).isEqualByComparingTo("40");
              });
      assertThat(evidence.productGroups(productEvidence, ProductBreakdown.CREATORS, 20, 0))
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.code()).isEqualTo("11");
                assertThat(row.paidOrderCount()).isEqualTo(1);
                assertThat(row.netSalesAmount()).isEqualByComparingTo("68");
              });
      assertThat(evidence.daily(productEvidence))
          .singleElement()
          .satisfies(
              day -> {
                assertThat(day.day()).isEqualTo(LocalDate.of(2026, 11, 3));
                assertThat(day.sales()).isEqualByComparingTo("100");
                assertThat(day.refunds()).isEqualByComparingTo("0");
              });
      assertThat(evidence.daily(shopEvidence))
          .singleElement()
          .satisfies(
              day -> {
                assertThat(day.sales()).isEqualByComparingTo(own.sales());
                assertThat(day.refunds()).isEqualByComparingTo(own.completedRefunds());
              });
      var otherInviter = new Selection(scope, null, 10L, null, null, FROM, UNTIL);
      assertThat(evidence.daily(otherInviter)).isEmpty();

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
      var products = daily.productFacts(productScope, "a", FROM, UNTIL);
      assertThat(products).hasSize(2);
      var product = products.stream().filter(row -> row.shopId() != null).findFirst().orElseThrow();
      assertThat(product.spu()).isEqualTo("unmapped:A");
      assertThat(product.paidOrders()).isEqualTo(1);
      assertThat(product.units()).isEqualTo(3);
      assertThat(product.sales()).isEqualByComparingTo("100");
      assertThat(product.cohortRefunds()).isEqualByComparingTo("32");
      assertThat(product.cohortRefundedOrders()).isEqualTo(1);
      assertThat(product.completedRefunds()).isZero();
      assertThat(daily.productFacts(productScope, "not-matching", FROM, UNTIL))
          .singleElement()
          .satisfies(total -> assertThat(total.paidOrders()).isZero());
      assertThat(mapper.sellingCreators(productScope, FROM, UNTIL)).isEqualTo(1);
      assertThat(mapper.rankFacts(productScope, RankDimension.KOL, FROM, UNTIL))
          .singleElement()
          .satisfies(row -> assertThat(row.linkCount()).isEqualTo(1));
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
      var service =
          new StatsRollupService(new OrderStatsService(mapper, daily, settlements), rollupMapper);
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
    jdbc.update(
        """
        INSERT INTO crm_product (id, product_code, shop_id, tenant_id, weidian_item_id, name,
          price, image_url, create_time, update_time)
        VALUES (90, 'T144-PROD', 1, 4, '678900', 'Source Alpha', 100, 'https://example.test/product.png', now(), now())
        """);
    jdbc.update("UPDATE crm_order_item SET product_id = 90 WHERE id IN (1, 2)");
    jdbc.update("UPDATE crm_order_item SET weidian_item_id = 'B', kol_id = 12 WHERE id = 2");
    jdbc.update("UPDATE crm_order SET shop_id = 2 WHERE id = 3");
    jdbc.execute(
        """
        INSERT INTO crm_order_item (
          id, tenant_id, order_id, sub_order_id, weidian_item_id, sku_id, item_name, sku_name, quantity,
          pay_amount, refund_amount, kol_id, product_id, attribution_type, create_time, update_time
        ) VALUES (4, 4, 3, 't144-copy', 'C', '4', 'C', 'SKU', 1, 200, 0, 11, 90, 1, now(), now())
        """);
    try (var session = factory.openSession()) {
      var productMapper = session.getMapper(OrderDailyStatsMapper.class);
      var visible = new ScopeDTO(4, null, null, null, null, java.util.List.of(1L, 2L));
      assertThat(productMapper.productFacts(visible, null, FROM, UNTIL))
          .filteredOn(row -> row.shopId() != null)
          .hasSize(2)
          .allSatisfy(row -> assertThat(row.spu()).isEqualTo("product:90"));
      for (String key : java.util.List.of("90", "ALPHA")) {
        assertThat(productMapper.productFacts(visible, key, FROM, UNTIL))
            .filteredOn(row -> row.shopId() != null)
            .hasSize(2);
      }
      assertThat(productMapper.productFacts(visible, "678900", FROM, UNTIL))
          .filteredOn(row -> row.shopId() != null)
          .singleElement()
          .satisfies(row -> assertThat(row.shopId()).isEqualTo(1L));
      assertThat(productMapper.productFacts(visible, "B", FROM, UNTIL))
          .filteredOn(row -> row.shopId() != null)
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.shopId()).isEqualTo(1L);
                assertThat(row.sales()).isEqualByComparingTo("100");
                assertThat(row.paidOrders()).isEqualTo(1);
                assertThat(row.kolCount()).isEqualTo(2);
              });
      var shopScope = new ScopeDTO(4, null, 1L, null, null, java.util.List.of(1L));
      assertThat(session.getMapper(OrderStatsMapper.class).countShopsWithPaidOrders(visible))
          .isEqualTo(2);
      assertThat(session.getMapper(OrderStatsMapper.class).countShopsWithPaidOrders(shopScope))
          .isEqualTo(1);
      var selection = new Selection(shopScope, null, null, "product:90", "B", FROM, UNTIL);
      var evidence = session.getMapper(OrderEvidenceMapper.class);
      assertThat(evidence.count(selection, Kind.ORDERS)).isEqualTo(2);
      assertThat(evidence.page(selection, Kind.ORDERS, 20, 0))
          .allSatisfy(
              row -> {
                assertThat(row.shopId()).isEqualTo(1L);
                assertThat(row.spuId()).isEqualTo("678900");
                assertThat(row.imageUrl()).isEqualTo("https://example.test/product.png");
              });
      var creatorSearch =
          new ScopeDTO(
              4, null, 1L, null, null, java.util.List.of(1L), java.util.List.of(12L), null);
      var filtered = new Selection(creatorSearch, null, null, "product:90", null, FROM, UNTIL);
      assertThat(productMapper.productFacts(creatorSearch, null, FROM, UNTIL))
          .filteredOn(row -> row.shopId() != null)
          .singleElement()
          .satisfies(row -> assertThat(row.sales()).isEqualByComparingTo("40"));
      assertThat(evidence.count(filtered, Kind.ORDERS)).isEqualTo(1);
      var noMatch =
          new ScopeDTO(4, null, 1L, null, null, java.util.List.of(1L), java.util.List.of(), null);
      assertThat(
              evidence.count(
                  new Selection(noMatch, null, null, null, null, FROM, UNTIL), Kind.ORDERS))
          .isZero();
      assertThat(evidence.daily(selection))
          .singleElement()
          .satisfies(day -> assertThat(day.sales()).isEqualByComparingTo("100"));
    }
    measureNinetyDayQueries(jdbc, factory);
    checkCreatorCards(jdbc, factory);
    checkInviterSearch(jdbc, factory);
    checkFirstShopSpu(jdbc, factory);
  }

  private static void checkFirstShopSpu(JdbcTemplate jdbc, SqlSessionFactory factory) {
    jdbc.update(
        """
        INSERT INTO crm_product (id, product_code, shop_id, tenant_id, weidian_item_id, name,
          price, create_time, update_time)
        VALUES (91, 'T144-SPU', 11, 4, '7890', 'Product A', 100, now(), now())
        """);
    jdbc.update(
        """
        INSERT INTO crm_kol_item (id, tenant_id, kol_id, product_id, shop_id, weidian_item_id,
          valid_from, create_time, update_time)
        SELECT id, 4, id, 91, shop, item, '2026-11-01T00:00:00Z', created::timestamptz, now()
        FROM (VALUES (951,11,'8800','2026-11-01T00:00:00Z'),
                     (952,11,'9900','2026-11-02T00:00:00Z'),
                     (953,12,'9400','2026-11-01T00:00:00Z'),
                     (954,12,'9300','2026-11-02T00:00:00Z'),
                     (956,11,'9600','2026-11-02T00:00:00Z')) links(id,shop,item,created)
        """);
    jdbc.update(
        """
        INSERT INTO crm_order (id, tenant_id, shop_id, weidian_order_id, kol_id, bd_user_id,
          attribution_status, order_status, raw_status, order_time, pay_time, total_amount,
          pay_amount, freight, discount, source, last_sync_time, create_time, update_time)
        SELECT id, 4, shop, 't144-spu-' || id, kol, CASE WHEN kol IS NULL THEN NULL ELSE 9 END,
          CASE WHEN kol IS NULL THEN 'NON_KOL' ELSE 'ATTRIBUTED' END,
          'PAID', '1', '2026-11-03T00:00:00Z', '2026-11-03T00:00:00Z',
          amount, amount, 0, 0, 1, now(), now(), now()
        FROM (VALUES (951,11,NULL,100), (952,11,951,200), (953,11,952,300),
                     (954,12,954,400), (955,11,NULL,50)) orders(id,shop,kol,amount)
        """);
    jdbc.update(
        """
        INSERT INTO crm_order_item (id, tenant_id, order_id, sub_order_id, weidian_item_id, sku_id,
          item_name, sku_name, quantity, pay_amount, refund_amount, kol_id, product_id,
          attribution_type, create_time, update_time)
        SELECT id, 4, id, 't144-spu-line-' || id, item, id::text, 'Product A', 'SKU', units, amount, 0,
          kol, product, CASE WHEN kol IS NULL THEN 3 ELSE 1 END, now(), now()
        FROM (VALUES (951,'7890',1,100,NULL,NULL), (952,'8800',2,200,951,91),
          (953,'9900',3,300,952,NULL), (954,'9300',4,400,954,91), (955,'9999',1,50,NULL,NULL))
          lines(id,item,units,amount,kol,product)
        """);
    jdbc.update(
        """
        INSERT INTO crm_refund (id, tenant_id, order_id, order_item_id, weidian_refund_no, amount,
          raw_status, refund_status, refund_time, finish_time, create_time, update_time)
        VALUES (951,4,953,953,'t144-spu-refund',30,'10','SUCCESS','2026-11-03T00:00:00Z',
          '2026-11-03T01:00:00Z',now(),now()),
          (952,4,953,953,'t144-spu-late-refund',50,'10','SUCCESS','2026-12-04T00:00:00Z',
          '2026-12-04T01:00:00Z',now(),now())
        """);
    try (var session = factory.openSession()) {
      var mapper = session.getMapper(OrderDailyStatsMapper.class);
      var scope = new ScopeDTO(4, null, null, null, null, java.util.List.of(11L, 12L));
      assertThat(mapper.productFacts(scope, null, FROM, UNTIL))
          .filteredOn(p -> p.shopId() != null)
          .extracting(p -> p.spuId())
          .containsExactlyInAnyOrder("7890", "9400", "9999");
      for (String keyword : java.util.List.of("7890", "8800", "9900")) {
        assertThat(mapper.productFacts(scope, keyword, FROM, UNTIL))
            .filteredOn(p -> p.shopId() != null)
            .singleElement()
            .satisfies(
                p -> {
                  assertThat(p.shopId()).isEqualTo(11L);
                  assertThat(p.spuId()).isEqualTo("7890");
                  assertThat(p.paidOrders()).isEqualTo(3);
                  assertThat(p.units()).isEqualTo(6);
                  assertThat(p.sales()).isEqualByComparingTo("600");
                  assertThat(p.cohortRefunds()).isEqualByComparingTo("30");
                });
      }
      var selection = new Selection(scope, null, null, "product:91", null, FROM, UNTIL);
      var evidence = session.getMapper(OrderEvidenceMapper.class);
      assertThat(evidence.page(selection, Kind.ORDERS, 20, 0))
          .extracting(row -> row.spuId())
          .containsExactlyInAnyOrder("7890", "7890", "7890", "9400");
      assertThat(evidence.page(selection, Kind.ORDERS, 20, 0))
          .filteredOn(row -> row.id() == 953)
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.amount()).isEqualByComparingTo("300");
                assertThat(row.refundAmount()).isEqualByComparingTo("30");
                assertThat(row.netTransactionAmount()).isEqualByComparingTo("270");
              });
      assertThat(evidence.page(selection, Kind.REFUNDS, 20, 0))
          .extracting(row -> row.spuId())
          .containsExactly("7890");
      var store = new ScopeDTO(4, null, 11L, null, null, java.util.List.of(11L));
      var counts =
          evidence.shopCreatorCounts(new Selection(store, null, null, null, null, FROM, UNTIL));
      assertThat(counts.selling()).isEqualTo(2);
      assertThat(counts.total()).isEqualTo(3);
      var stats = session.getMapper(OrderStatsMapper.class);
      assertThat(stats.totals(store.withProductKeyword("7890"), FROM, UNTIL).sales())
          .isEqualByComparingTo("600");
      assertThat(stats.rankFacts(store, RankDimension.SHOP, FROM, UNTIL))
          .singleElement()
          .satisfies(row -> assertThat(row.spuCount()).isEqualTo(2));
    }
  }

  private static void checkInviterSearch(JdbcTemplate jdbc, SqlSessionFactory factory) {
    jdbc.execute(
        """
        INSERT INTO sys_user (id, username, password_hash, nickname, role, status, create_time, update_time)
        VALUES (9501,'t144_north_bd','!','North','MEMBER',1,now(),now()),
               (9502,'t144_south_bd','!','South','MEMBER',1,now(),now())
        """);
    jdbc.execute(
        """
        INSERT INTO sys_user_tenant (id, user_id, tenant_id, role, data_scope, status, create_time, update_time)
        VALUES (9501,9501,4,'STAFF','OWN',0,now(),now()), (9502,9502,5,'STAFF','OWN',1,now(),now())
        """);
    try (var session = factory.openSession()) {
      var members = session.getMapper(SysUserTenantMapper.class);
      assertThat(members.findMemberIdsByKeyword(4L, "9501")).containsExactly(9501L);
      assertThat(members.findMemberIdsByKeyword(4L, "NORTH")).containsExactly(9501L);
      assertThat(members.findMemberIdsByKeyword(4L, "south")).isEmpty();
      var scope =
          new ScopeDTO(4, null, 1L, null, null, java.util.List.of(1L), null, java.util.List.of(9L));
      var stats = session.getMapper(OrderStatsMapper.class);
      assertThat(stats.totals(scope, FROM, UNTIL).sales()).isEqualByComparingTo("100");
      var evidence = session.getMapper(OrderEvidenceMapper.class);
      assertThat(
              evidence.count(
                  new Selection(scope, null, null, null, null, FROM, UNTIL), Kind.ORDERS))
          .isEqualTo(2);
    }
  }

  private static void checkCreatorCards(JdbcTemplate jdbc, SqlSessionFactory factory) {
    jdbc.execute(
        """
        INSERT INTO crm_kol (id, tenant_id, kol_code, name, email, country, create_time, update_time)
        VALUES (701, 4, 'KOL-AAA701', 'Fixture', 't144-701@example.test', 'US', '2026-11-01T00:00:00Z', now()),
               (702, 4, 'KOL-AAA702', 'Fixture', 't144-702@example.test', 'US', '2026-11-01T00:00:00Z', now())
        """);
    jdbc.update("UPDATE crm_kol SET status = 0 WHERE id = 701");
    jdbc.update(
        """
        INSERT INTO crm_kol_reporting_status (tenant_id, kol_id, enabled, changed_at, baseline)
        VALUES (4, 702, TRUE, '2026-11-04T00:00:00Z', TRUE)
        """);
    jdbc.execute(
        """
        INSERT INTO crm_commission_item_state (id, tenant_id, order_id, order_item_id, kol_id,
          source_key, settlement_state, verified, accrued_commission_cny, release_basis,
          create_time, update_time)
        VALUES (701, 4, 1, 1, 11, 't144-card-state', 'CONFIRMED', TRUE, 8, 'CONFIRMED', now(), now())
        """);
    jdbc.execute(
        """
        INSERT INTO crm_commission_item_revision (id, tenant_id, item_state_id, source_version,
          basis_cny, successful_refund_cny, rate, accrued_cny, delta_cny, release_basis, create_time)
        VALUES (701, 4, 701, 'initial', 100, 0, .10, 10, 0, 'CONFIRMED', '2026-11-03T00:00:00Z'),
               (702, 4, 701, 'refund', 80, 20, .10, 8, -2, 'CONFIRMED', '2026-11-04T00:00:00Z')
        """);
    jdbc.execute(
        """
        INSERT INTO crm_commission_ledger (id, tenant_id, kol_id, entry_type, amount_cny, source_type,
          source_id, source_key, occurred_at, balance_after_cny, create_time)
        VALUES (701, 4, 11, 'COMMISSION_ADJUSTMENT', -2, 'COMMISSION', 701,
          'COMMISSION_ITEM:701:refund', '2026-11-04T00:00:00Z', 8, now()),
          (702, 4, 11, 'COMMISSION_ADJUSTMENT', -1, 'COMMISSION', 701,
          'COMMISSION_ITEM:701:correction:K11', '2026-11-04T00:00:00Z', 7, now())
        """);
    try (var session = factory.openSession()) {
      var kols = session.getMapper(KolMapper.class);
      kols.recordReportingStatus(4L, 701L, false, UNTIL);
      assertThat(kols.countEnabledDuring(4, java.util.List.of(701L), FROM, UNTIL)).isEqualTo(1L);
      assertThat(kols.countEnabledDuring(4, java.util.List.of(701L), UNTIL, UNTIL.plusSeconds(60)))
          .isZero();
      assertThat(kols.countEnabledDuring(4, java.util.List.of(702L), FROM, UNTIL)).isNull();
      var orders = session.getMapper(OrderStatsMapper.class);
      var scoped = new ScopeDTO(4, 9L, 1L, null, null, java.util.List.of(1L));
      assertThat(orders.creatorSpuCount(scoped, FROM, UNTIL)).isEqualTo(1);
      var commission = orders.creatorCommission(scoped, FROM, UNTIL);
      assertThat(commission.total()).isEqualByComparingTo("8");
      assertThat(commission.clawback()).isEqualByComparingTo("2");
      assertThat(orders.inviterCommissions(scoped, FROM, UNTIL))
          .singleElement()
          .satisfies(
              value -> {
                assertThat(value.inviterId()).isEqualTo(9L);
                assertThat(value.estimated()).isEqualByComparingTo("8");
                assertThat(value.actual()).isZero();
              });
      jdbc.update(
          "UPDATE crm_commission_item_state SET released_commission_cny = 8 WHERE id = 701");
      jdbc.update(
          "UPDATE crm_order_item SET item_name = 'Other', weidian_item_id = 'B', product_id = NULL WHERE id = 2");
      jdbc.update("UPDATE crm_order_item SET item_name = 'Single selected item' WHERE id = 1");
      var productScope = scoped.withProductKeyword("Single selected item");
      session.clearCache();
      var selected = orders.totals(productScope, FROM, UNTIL);
      assertThat(selected.paidOrders()).isEqualTo(1L);
      assertThat(selected.units()).isEqualTo(2L);
      assertThat(selected.sales()).isEqualByComparingTo("60");
      assertThat(selected.cohortRefunds()).isEqualByComparingTo("32");
      assertThat(orders.rankFacts(productScope, RankDimension.INVITER, FROM, UNTIL))
          .singleElement()
          .satisfies(value -> assertThat(value.sales()).isEqualByComparingTo("60"));
      assertThat(orders.bestCreators(productScope, FROM, UNTIL))
          .singleElement()
          .satisfies(
              value -> {
                assertThat(value.inviterId()).isEqualTo(9L);
                assertThat(value.creatorId()).isEqualTo(11L);
                assertThat(value.netSalesAmount()).isEqualByComparingTo("28");
              });
      assertThat(orders.inviterCommissions(productScope, FROM, UNTIL))
          .singleElement()
          .satisfies(
              value -> {
                assertThat(value.estimated()).isZero();
                assertThat(value.actual()).isEqualByComparingTo("8");
              });
      assertThat(orders.inviterCommissions(scoped.withProductKeyword("Other"), FROM, UNTIL))
          .isEmpty();
      var evidence = session.getMapper(OrderEvidenceMapper.class);
      var selection = new Selection(productScope, null, 9L, null, null, FROM, UNTIL);
      assertThat(evidence.count(selection, Kind.ORDERS)).isEqualTo(1L);
      assertThat(evidence.daily(selection))
          .singleElement()
          .satisfies(day -> assertThat(day.sales()).isEqualByComparingTo("60"));
      jdbc.update(
          """
          INSERT INTO crm_commission_item_state (id, tenant_id, order_id, order_item_id, kol_id,
            source_key, settlement_state, verified, accrued_commission_cny, release_basis,
            create_time, update_time)
          VALUES (702, 4, 1, 2, 11, 't144-unverified', 'UNKNOWN', FALSE, 0, 'UNVERIFIED', now(), now())
          """);
      session.clearCache();
      assertThat(orders.inviterCommissions(productScope, FROM, UNTIL)).isEmpty();
      assertThat(orders.creatorCommission(new ScopeDTO(4, 10L, null, null, null), FROM, UNTIL))
          .isNull();
    }
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
