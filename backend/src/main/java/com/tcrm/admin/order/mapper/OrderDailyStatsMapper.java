package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.order.api.OrderStatsApi.DailyFactDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ProductFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Order-owned source for Beijing-day rollups. Every fact join remains in one tenant. */
@Mapper
public interface OrderDailyStatsMapper {
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      <bind name="productSearch" value="keyword"/>
      WITH lines AS NOT MATERIALIZED (
        SELECT o.id AS order_id, o.shop_id, o.pay_time, i.id AS line_id,
               """
          + OrderProductSql.GROUP_KEY
          + """
                 AS spu,
               """
          + OrderProductSql.SPU_ID
          + """
                 AS spu_id,
               i.weidian_item_id, i.tenant_id,
               i.item_name, reporting_product.id AS reporting_product_id, i.kol_id, i.quantity, i.pay_amount,
               GREATEST(o.update_time, i.update_time) AS updated_at
        FROM crm_order o JOIN crm_order_item i ON i.order_id = o.id
      """
          + OrderProductSql.PRODUCT_JOIN
          + OrderProductSql.FIRST_LINK_JOIN
          + """
        WHERE o.tenant_id = #{scope.tenantId} AND i.tenant_id = #{scope.tenantId}
          AND NOT o.is_deleted AND NOT i.is_deleted AND o.pay_time IS NOT NULL
          AND i.weidian_item_id != ''
        <if test="scope.ownerId != null">AND o.bd_user_id = #{scope.ownerId}</if>
        <if test="scope.matchedInviterIds != null">
          <choose>
            <when test="scope.matchedInviterIds.isEmpty()">AND FALSE</when>
            <otherwise>
              AND o.bd_user_id IN
              <foreach collection="scope.matchedInviterIds" item="inviter" open="(" separator="," close=")">
                #{inviter}
              </foreach>
            </otherwise>
          </choose>
        </if>
        <if test="scope.shopId != null">AND o.shop_id = #{scope.shopId}</if>
        <if test="scope.visibleShopIds != null">
          <choose>
            <when test="scope.visibleShopIds.isEmpty()">AND FALSE</when>
            <otherwise>
              AND o.shop_id IN
              <foreach collection="scope.visibleShopIds" item="visibleShop" open="(" separator="," close=")">
                #{visibleShop}
              </foreach>
            </otherwise>
          </choose>
        </if>
        <if test="scope.kolId != null">AND i.kol_id = #{scope.kolId}</if>
        <if test="scope.matchedKolIds != null">
          <choose>
            <when test="scope.matchedKolIds.isEmpty()">AND FALSE</when>
            <otherwise>
              AND i.kol_id IN
              <foreach collection="scope.matchedKolIds" item="matched" open="(" separator="," close=")">
                #{matched}
              </foreach>
            </otherwise>
          </choose>
        </if>
      ), selected_lines AS NOT MATERIALIZED (
        SELECT l.* FROM lines l
        <if test="keyword != null">
          WHERE (l.shop_id, l.spu) IN (
            SELECT i.shop_id, i.spu FROM lines i
            WHERE
      """
          + OrderProductSql.MATCH
          + """
          )
        </if>
      ),
      paid AS (
        SELECT order_id, shop_id, pay_time, line_id, spu, spu_id, item_name, reporting_product_id,
               kol_id, quantity, pay_amount, updated_at
        FROM selected_lines WHERE pay_time &gt;= #{from} AND pay_time &lt; #{until}
      ),
      events AS (
        SELECT shop_id, spu, spu_id, item_name, reporting_product_id, order_id, kol_id, quantity,
               pay_amount AS sales, 0::numeric AS cohort, 0::numeric AS completed,
               NULL::bigint AS refund_order, NULL::bigint AS cohort_order,
               pay_time, NULL::timestamptz AS finish_time, updated_at
        FROM paid
        UNION ALL
        SELECT p.shop_id, p.spu, p.spu_id, p.item_name, p.reporting_product_id, NULL, NULL, 0, 0, r.amount, 0,
               NULL, p.order_id, p.pay_time, NULL, r.update_time
        FROM crm_refund r JOIN paid p ON r.order_item_id = p.line_id AND r.order_id = p.order_id
        WHERE r.tenant_id = #{scope.tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
      """
          + OrderStatsMapper.COHORT_REFUND_WINDOW
          + """
        UNION ALL
        SELECT p.shop_id, p.spu, p.spu_id, p.item_name, p.reporting_product_id, NULL, NULL, 0, 0, 0, r.amount,
               p.order_id, NULL, NULL, r.finish_time, r.update_time
        FROM crm_refund r JOIN selected_lines p ON r.order_item_id = p.line_id AND r.order_id = p.order_id
        WHERE r.tenant_id = #{scope.tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
          AND r.finish_time &gt;= #{from} AND r.finish_time &lt; #{until}
      )
      SELECT shop_id, spu, MAX(item_name) AS name, MAX(reporting_product_id) AS product_id,
             COUNT(DISTINCT order_id) AS paid_orders, COALESCE(SUM(quantity), 0) AS units,
             COUNT(DISTINCT kol_id) AS kol_count, COALESCE(SUM(sales), 0) AS sales,
             COALESCE(SUM(cohort), 0) AS cohort_refunds,
             COALESCE(SUM(completed), 0) AS completed_refunds,
             COUNT(DISTINCT refund_order) AS refunded_orders,
             COUNT(DISTINCT cohort_order) AS cohort_refunded_orders,
             MAX(pay_time) AS last_paid_at, MAX(finish_time) AS last_refunded_at, MAX(updated_at) AS updated_at, MAX(spu_id) AS spu_id
      FROM events
      GROUP BY GROUPING SETS ((shop_id, spu), ())
      </script>
      """)
  List<ProductFactsDTO> productFacts(
      @Param("scope") ScopeDTO scope,
      @Param("keyword") String keyword,
      @Param("from") Instant from,
      @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      SELECT DISTINCT (event_time AT TIME ZONE 'Asia/Shanghai')::date
      FROM (
        SELECT pay_time AS event_time FROM crm_order
        WHERE tenant_id = #{tenantId} AND id = #{orderId}
        UNION ALL
        SELECT finish_time FROM crm_refund
        WHERE tenant_id = #{tenantId} AND order_id = #{orderId} AND NOT is_deleted
      ) events
      WHERE event_time IS NOT NULL
      """)
  List<LocalDate> orderDays(@Param("tenantId") long tenantId, @Param("orderId") long orderId);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      WITH changed_orders AS (
        SELECT o.id, o.pay_time
        FROM crm_order o
        WHERE o.tenant_id = #{tenantId} AND o.update_time >= #{from} AND o.update_time < #{until}
        UNION
        SELECT o.id, o.pay_time
        FROM crm_order_item i JOIN crm_order o ON o.id = i.order_id
        WHERE i.tenant_id = #{tenantId} AND o.tenant_id = #{tenantId}
          AND i.update_time >= #{from} AND i.update_time < #{until}
      ),
      changed_refunds AS (
        SELECT o.pay_time, r.finish_time
        FROM crm_refund r JOIN crm_order o ON o.id = r.order_id
        WHERE r.tenant_id = #{tenantId} AND o.tenant_id = #{tenantId}
          AND r.update_time >= #{from} AND r.update_time < #{until}
        UNION
        SELECT o.pay_time, r.finish_time
        FROM changed_orders o JOIN crm_refund r ON r.order_id = o.id
        WHERE r.tenant_id = #{tenantId}
      ),
      changed_times AS (
        SELECT pay_time AS event_time FROM changed_orders
        UNION SELECT pay_time FROM changed_refunds
        UNION SELECT finish_time FROM changed_refunds
      )
      SELECT DISTINCT (event_time AT TIME ZONE 'Asia/Shanghai')::date
      FROM changed_times WHERE event_time IS NOT NULL
      ORDER BY 1
      """)
  List<LocalDate> changedDays(
      @Param("tenantId") long tenantId, @Param("from") Instant from, @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      WITH scoped_orders AS NOT MATERIALIZED (
        SELECT id, shop_id, COALESCE(kol_id, 0) AS kol_id, COALESCE(bd_user_id, 0) AS owner_id,
               attribution_status, pay_time, pay_amount
        FROM crm_order
        WHERE tenant_id = #{tenantId} AND NOT is_deleted AND pay_time IS NOT NULL
      ),
      paid AS (
        SELECT id, shop_id, kol_id, owner_id, attribution_status, pay_time, pay_amount
        FROM scoped_orders WHERE pay_time &gt;= #{from} AND pay_time &lt; #{until}
      ),
      refund_facts AS (
        SELECT p.id AS order_id, p.shop_id, p.kol_id, p.owner_id, p.attribution_status,
               r.order_item_id, r.amount, true AS cohort
        FROM crm_refund r JOIN paid p ON p.id = r.order_id
        WHERE r.tenant_id = #{tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
      """
          + OrderStatsMapper.COHORT_REFUND_WINDOW
          + """
        UNION ALL
        SELECT o.id, o.shop_id, o.kol_id, o.owner_id, o.attribution_status,
               r.order_item_id, r.amount, false
        FROM crm_refund r JOIN scoped_orders o ON o.id = r.order_id
        WHERE r.tenant_id = #{tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
          AND r.finish_time &gt;= #{from} AND r.finish_time &lt; #{until}
      ),
      facts AS (
        SELECT 'ORDER' AS grain, shop_id, kol_id, owner_id, '' AS product_code, '' AS sku_code,
               1 AS paid_orders, 0 AS units, pay_amount AS sales,
               0::numeric AS cohort_refunds, 0::numeric AS completed_refunds
        FROM paid
        UNION ALL
        SELECT 'ORDER', p.shop_id, p.kol_id, p.owner_id, '', '', 0, i.quantity, 0, 0, 0
        FROM crm_order_item i JOIN paid p ON p.id = i.order_id
        WHERE i.tenant_id = #{tenantId} AND NOT i.is_deleted
        UNION ALL
        SELECT 'ITEM', p.shop_id, i.kol_id, p.owner_id,
               COALESCE('product:' || i.product_id, i.weidian_item_id), i.sku_id,
               0, i.quantity, i.pay_amount, 0, 0
        FROM crm_order_item i JOIN paid p ON p.id = i.order_id
        WHERE i.tenant_id = #{tenantId} AND NOT i.is_deleted AND i.kol_id IS NOT NULL
          AND i.weidian_item_id != ''
          AND p.attribution_status = 'ATTRIBUTED'
        UNION ALL
        SELECT 'ORDER', shop_id, kol_id, owner_id, '', '', 0, 0, 0,
               CASE WHEN cohort THEN amount ELSE 0 END,
               CASE WHEN NOT cohort THEN amount ELSE 0 END
        FROM refund_facts
        UNION ALL
        SELECT 'ITEM', r.shop_id, i.kol_id, r.owner_id,
               COALESCE('product:' || i.product_id, i.weidian_item_id), i.sku_id, 0, 0, 0,
               CASE WHEN r.cohort THEN r.amount ELSE 0 END,
               CASE WHEN NOT r.cohort THEN r.amount ELSE 0 END
        FROM refund_facts r JOIN crm_order_item i ON i.id = r.order_item_id AND i.order_id = r.order_id
        WHERE i.tenant_id = #{tenantId} AND NOT i.is_deleted AND i.kol_id IS NOT NULL
          AND i.weidian_item_id != ''
          AND r.attribution_status = 'ATTRIBUTED'
      )
      SELECT grain, shop_id, kol_id, owner_id, product_code, sku_code,
             SUM(paid_orders) AS paid_orders, SUM(units) AS units, SUM(sales) AS sales,
             SUM(cohort_refunds) AS cohort_refunds, SUM(completed_refunds) AS completed_refunds
      FROM facts GROUP BY grain, shop_id, kol_id, owner_id, product_code, sku_code
      </script>
      """)
  List<DailyFactDTO> rollup(
      @Param("tenantId") long tenantId, @Param("from") Instant from, @Param("until") Instant until);
}
