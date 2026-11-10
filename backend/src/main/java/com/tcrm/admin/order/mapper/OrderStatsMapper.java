package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.order.api.OrderStatsApi.RankDimension;
import com.tcrm.admin.order.api.OrderStatsApi.RankFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.order.api.OrderStatsApi.TotalsDTO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Order-owned read model. All tenant and logical deletion predicates are explicit in this query.
 */
@Mapper
public interface OrderStatsMapper {
  String SCOPED_PAID_ORDERS =
      """
      <script>
      WITH scoped_orders AS NOT MATERIALIZED (
        SELECT o.id, o.shop_id, o.kol_id, o.bd_user_id, o.pay_time, o.pay_amount, o.update_time
        FROM crm_order o
        WHERE o.tenant_id = #{scope.tenantId} AND NOT o.is_deleted
          AND o.pay_time IS NOT NULL
        <if test="scope.ownerId != null">AND o.bd_user_id = #{scope.ownerId}</if>
        <if test="scope.shopId != null">AND o.shop_id = #{scope.shopId}</if>
        <if test="scope.kolId != null">AND o.kol_id = #{scope.kolId}</if>
        <if test="scope.inviterId != null">AND o.bd_user_id = #{scope.inviterId}</if>
        <if test="scope.matchedKolIds != null">
          <choose>
            <when test="scope.matchedKolIds.isEmpty()">AND FALSE</when>
            <otherwise>AND o.kol_id IN
              <foreach collection="scope.matchedKolIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </otherwise>
          </choose>
        </if>
        <if test="scope.matchedInviterIds != null">
          <choose>
            <when test="scope.matchedInviterIds.isEmpty()">AND FALSE</when>
            <otherwise>AND o.bd_user_id IN
              <foreach collection="scope.matchedInviterIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </otherwise>
          </choose>
        </if>
        <if test="scope.visibleShopIds != null">
          <choose>
            <when test="scope.visibleShopIds.isEmpty()">AND FALSE</when>
            <otherwise>
              AND o.shop_id IN
              <foreach collection="scope.visibleShopIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            </otherwise>
          </choose>
        </if>
      ),
      paid AS (
        SELECT id, shop_id, kol_id, pay_time, pay_amount, update_time FROM scoped_orders
        WHERE pay_time &gt;= #{from} AND pay_time &lt; #{until}
      ),
      """;

  String COHORT_REFUND_WINDOW =
      """
      AND r.finish_time &lt; (
        ((p.pay_time AT TIME ZONE 'Asia/Shanghai')::date + 30)::timestamp
        AT TIME ZONE 'Asia/Shanghai'
      )
      """;

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_PAID_ORDERS
          + """
      sales AS (
        SELECT COUNT(*) AS paid_orders, COALESCE(SUM(pay_amount), 0) AS amount,
               MAX(update_time) AS updated_at
        FROM paid
      ),
      items AS (
        SELECT COALESCE(SUM(i.quantity), 0) AS units, MAX(i.update_time) AS updated_at
        FROM crm_order_item i JOIN paid p ON p.id = i.order_id
        WHERE i.tenant_id = #{scope.tenantId} AND NOT i.is_deleted
      ),
      cohort AS (
        SELECT COALESCE(SUM(r.amount), 0) AS amount, MAX(r.update_time) AS updated_at
        FROM crm_refund r JOIN paid p ON p.id = r.order_id
        WHERE r.tenant_id = #{scope.tenantId} AND NOT r.is_deleted
          AND r.refund_status = 'SUCCESS'
      """
          + COHORT_REFUND_WINDOW
          + """
      ),
      completed AS (
        SELECT COALESCE(SUM(r.amount), 0) AS amount, COUNT(DISTINCT r.order_id) AS refunded_orders,
               MAX(GREATEST(r.update_time, o.update_time)) AS updated_at
        FROM crm_refund r JOIN scoped_orders o ON o.id = r.order_id
        WHERE r.tenant_id = #{scope.tenantId} AND NOT r.is_deleted
          AND r.refund_status = 'SUCCESS'
          AND r.finish_time &gt;= #{from} AND r.finish_time &lt; #{until}
      )
      SELECT sales.paid_orders, items.units, sales.amount AS sales,
             cohort.amount AS cohort_refunds, completed.amount AS completed_refunds,
             completed.refunded_orders,
             GREATEST(sales.updated_at, items.updated_at, cohort.updated_at, completed.updated_at)
               AS updated_at
      FROM sales CROSS JOIN items CROSS JOIN cohort CROSS JOIN completed
      </script>
      """)
  TotalsDTO totals(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_PAID_ORDERS
          + """
      named AS (
        SELECT kol_id FROM scoped_orders WHERE bd_user_id IS NOT NULL
          AND pay_time &gt;= #{from} AND pay_time &lt; #{until}
      )
      SELECT COUNT(DISTINCT kol_id) FROM named
      </script>
      """)
  long sellingCreators(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_PAID_ORDERS
          + """
      <choose>
        <when test="dimension.name() == 'KOL'">
          grouped AS (
            SELECT p.kol_id AS entity_id, MAX(i.item_name) AS name, NULL::bigint AS creator_id,
                   ROW_NUMBER() OVER (PARTITION BY p.kol_id ORDER BY SUM(i.pay_amount) DESC,
                     MAX(p.pay_time) DESC, p.shop_id, i.weidian_item_id) AS position
            FROM paid p JOIN crm_order_item i ON i.order_id = p.id AND i.kol_id = p.kol_id
            WHERE i.tenant_id = #{scope.tenantId} AND NOT i.is_deleted AND i.weidian_item_id != ''
            GROUP BY p.kol_id, p.shop_id, i.weidian_item_id
          )
          SELECT entity_id, name, creator_id FROM grouped WHERE position &lt;= 3
          ORDER BY entity_id, position
        </when>
        <otherwise>
          grouped AS (
            SELECT bd_user_id AS entity_id, NULL::text AS name, kol_id AS creator_id,
                   ROW_NUMBER() OVER (PARTITION BY bd_user_id ORDER BY SUM(pay_amount) DESC,
                     MAX(pay_time) DESC, kol_id) AS position
            FROM scoped_orders
            WHERE pay_time &gt;= #{from} AND pay_time &lt; #{until}
              AND kol_id IS NOT NULL AND bd_user_id IS NOT NULL
            GROUP BY bd_user_id, kol_id
          )
          SELECT entity_id, name, creator_id FROM grouped WHERE position = 1
        </otherwise>
      </choose>
      </script>
      """)
  List<com.tcrm.admin.order.api.OrderStatsApi.HighlightDTO> rankHighlights(
      @Param("scope") ScopeDTO scope,
      @Param("dimension") RankDimension dimension,
      @Param("from") Instant from,
      @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_PAID_ORDERS
          + """
      ranked_orders AS NOT MATERIALIZED (
        SELECT id, shop_id, kol_id, pay_time, pay_amount,
        <choose>
          <when test="dimension.name() == 'KOL'">COALESCE(kol_id, 0)</when>
          <when test="dimension.name() == 'INVITER'">COALESCE(bd_user_id, 0)</when>
          <otherwise>shop_id</otherwise>
        </choose>
        AS entity_id
        FROM scoped_orders
      ),
      ranked_paid AS (
        SELECT id, shop_id, kol_id, pay_time, pay_amount, entity_id FROM ranked_orders
        WHERE pay_time &gt;= #{from} AND pay_time &lt; #{until}
      ),
      sales AS (
        SELECT entity_id, COUNT(*) AS paid_orders, SUM(pay_amount) AS sales,
               COUNT(DISTINCT kol_id) AS kol_count, MAX(pay_time) AS last_paid_at
        FROM ranked_paid GROUP BY entity_id
      ),
      items AS (
        SELECT p.entity_id, SUM(i.quantity) AS units,
               COUNT(DISTINCT (p.shop_id, i.weidian_item_id)) FILTER (WHERE i.weidian_item_id != '') AS spu_count,
               COUNT(DISTINCT (p.shop_id, i.weidian_item_id, i.sku_id))
                 FILTER (WHERE i.weidian_item_id != '' AND i.sku_id != '') AS sku_count,
               COUNT(DISTINCT i.kol_item_id) FILTER (WHERE i.kol_id = p.kol_id) AS link_count
        FROM crm_order_item i JOIN ranked_paid p ON p.id = i.order_id
        WHERE i.tenant_id = #{scope.tenantId} AND NOT i.is_deleted
        GROUP BY p.entity_id
      ),
      cohort AS (
        SELECT p.entity_id, SUM(r.amount) AS amount
        FROM crm_refund r JOIN ranked_paid p ON p.id = r.order_id
        WHERE r.tenant_id = #{scope.tenantId} AND NOT r.is_deleted
          AND r.refund_status = 'SUCCESS'
      """
          + COHORT_REFUND_WINDOW
          + """
        GROUP BY p.entity_id
      ),
      completed AS (
        SELECT o.entity_id, SUM(r.amount) AS amount, MAX(r.finish_time) AS last_refunded_at,
               COUNT(DISTINCT r.order_id) AS refunded_orders
        FROM crm_refund r JOIN ranked_orders o ON o.id = r.order_id
        WHERE r.tenant_id = #{scope.tenantId} AND NOT r.is_deleted
          AND r.refund_status = 'SUCCESS'
          AND r.finish_time &gt;= #{from} AND r.finish_time &lt; #{until}
        GROUP BY o.entity_id
      ),
      shops AS (
        SELECT entity_id FROM sales UNION SELECT entity_id FROM completed
      )
      SELECT s.entity_id, COALESCE(sales.paid_orders, 0) AS paid_orders,
             COALESCE(items.units, 0) AS units, COALESCE(items.spu_count, 0) AS spu_count,
             COALESCE(items.sku_count, 0) AS sku_count, COALESCE(sales.kol_count, 0) AS kol_count,
             COALESCE(sales.sales, 0) AS sales, COALESCE(cohort.amount, 0) AS cohort_refunds,
             COALESCE(completed.amount, 0) AS completed_refunds,
             COALESCE(completed.refunded_orders, 0) AS refunded_orders,
             sales.last_paid_at, completed.last_refunded_at,
             COALESCE(items.link_count, 0) AS link_count
      FROM shops s
      LEFT JOIN sales USING (entity_id)
      LEFT JOIN items USING (entity_id)
      LEFT JOIN cohort USING (entity_id)
      LEFT JOIN completed USING (entity_id)
      </script>
      """)
  List<RankFactsDTO> rankFacts(
      @Param("scope") ScopeDTO scope,
      @Param("dimension") RankDimension dimension,
      @Param("from") Instant from,
      @Param("until") Instant until);
}
