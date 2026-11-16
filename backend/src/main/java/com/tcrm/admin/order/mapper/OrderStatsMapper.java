package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.order.api.OrderStatsApi.CreatorCommissionDTO;
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
  String SCOPED_ORDERS =
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
      )
      """;

  String SCOPED_PAID_ORDERS =
      SCOPED_ORDERS
          + """
      ,
      paid AS (
        SELECT id, shop_id, kol_id, pay_time, pay_amount, update_time FROM scoped_orders
        WHERE pay_time &gt;= #{from} AND pay_time &lt; #{until}
      ),
      """;

  @InterceptorIgnore(tenantLine = "true")
  @Select(SCOPED_ORDERS + "SELECT COUNT(DISTINCT shop_id) FROM scoped_orders </script>")
  long countShopsWithPaidOrders(@Param("scope") ScopeDTO scope);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_ORDERS
          + "SELECT DISTINCT kol_id FROM scoped_orders WHERE kol_id IS NOT NULL </script>")
  List<Long> paidCreatorIds(@Param("scope") ScopeDTO scope);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_ORDERS
          + """
      SELECT COUNT(DISTINCT (p.shop_id, COALESCE('product:' || i.product_id, i.weidian_item_id)))
      FROM scoped_orders p JOIN crm_order_item i ON i.order_id = p.id AND i.tenant_id = #{scope.tenantId}
      WHERE NOT i.is_deleted AND i.weidian_item_id != ''
        AND p.pay_time &gt;= #{from} AND p.pay_time &lt; #{until}
      </script>
      """)
  long creatorSpuCount(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  // This read model uses wallet postings, not mutable release balances, for confirmed deductions.
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_ORDERS
          + """
      , verified AS MATERIALIZED (
        SELECT s.id, s.kol_id, s.accrued_commission_cny AS total
        FROM scoped_orders p
        JOIN crm_commission_item_state s ON s.order_id = p.id AND s.kol_id = p.kol_id
          AND s.tenant_id = #{scope.tenantId} AND s.verified AND NOT s.is_deleted
        JOIN crm_order_item i ON i.id = s.order_item_id AND i.order_id = p.id
          AND i.kol_id = s.kol_id AND i.tenant_id = #{scope.tenantId} AND NOT i.is_deleted
        WHERE p.pay_time &gt;= #{from} AND p.pay_time &lt; #{until}
      ), adjustments AS (
        SELECT -l.amount_cny AS amount,
          CASE
            WHEN prior.id IS NULL THEN NULL
            WHEN COALESCE(r.successful_refund_cny, 0) &lt;= COALESCE(prior.successful_refund_cny, 0) THEN FALSE
            WHEN r.release_basis != 'AFTER_SALES'
              AND r.rate IS NOT NULL AND r.rate = prior.rate
              AND prior.basis_cny - r.basis_cny IN (
                0, COALESCE(r.successful_refund_cny, 0) - COALESCE(prior.successful_refund_cny, 0))
              THEN TRUE
            ELSE NULL
          END AS refund
        FROM verified s
        JOIN crm_commission_ledger l ON l.tenant_id = #{scope.tenantId} AND l.source_id = s.id
          AND l.kol_id = s.kol_id AND l.source_type = 'COMMISSION'
          AND l.entry_type = 'COMMISSION_ADJUSTMENT' AND l.amount_cny &lt; 0
        LEFT JOIN crm_commission_item_revision r ON r.tenant_id = #{scope.tenantId}
          AND r.item_state_id = s.id
          AND l.source_key = 'COMMISSION_ITEM:' || s.id || ':' || r.source_version
        LEFT JOIN LATERAL (
          SELECT prev.id, prev.basis_cny, prev.successful_refund_cny, prev.rate
          FROM crm_commission_item_revision prev
          WHERE prev.tenant_id = #{scope.tenantId} AND prev.item_state_id = s.id
            AND (prev.create_time, prev.id) &lt; (r.create_time, r.id)
          ORDER BY prev.create_time DESC, prev.id DESC LIMIT 1
        ) prior ON TRUE
        WHERE l.source_key !~ ':K[0-9]+$'
      )
      SELECT (SELECT SUM(total) FROM verified) AS total,
             CASE WHEN EXISTS (SELECT 1 FROM verified)
               AND NOT EXISTS (SELECT 1 FROM adjustments WHERE refund IS NULL)
               THEN (SELECT COALESCE(SUM(amount) FILTER (WHERE refund), 0) FROM adjustments)
             END AS clawback
      </script>
      """)
  CreatorCommissionDTO creatorCommission(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

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
               COUNT(DISTINCT (p.shop_id, COALESCE('product:' || i.product_id, i.weidian_item_id)))
                 FILTER (WHERE i.weidian_item_id != '') AS spu_count,
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
