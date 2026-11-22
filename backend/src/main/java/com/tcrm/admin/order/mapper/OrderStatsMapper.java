package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.order.api.OrderStatsApi.BestCreatorDTO;
import com.tcrm.admin.order.api.OrderStatsApi.CommissionWindowDTO;
import com.tcrm.admin.order.api.OrderStatsApi.CreatorCommissionDTO;
import com.tcrm.admin.order.api.OrderStatsApi.CreatorCommissionExpense;
import com.tcrm.admin.order.api.OrderStatsApi.CreatorCommissionTotal;
import com.tcrm.admin.order.api.OrderStatsApi.CreatorRankingCommission;
import com.tcrm.admin.order.api.OrderStatsApi.InviterCommissionDTO;
import com.tcrm.admin.order.api.OrderStatsApi.RankDimension;
import com.tcrm.admin.order.api.OrderStatsApi.RankFactsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.SalesWindowDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.order.api.OrderStatsApi.TotalsDTO;
import com.tcrm.admin.order.api.OrderStatsApi.WorkbenchRankDTO;
import java.math.BigDecimal;
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
  String PRODUCT_ITEMS =
      """
      <if test="scope.productKeyword != null">
        searchable_items AS NOT MATERIALIZED (
          SELECT i.id, i.order_id, o.shop_id, i.tenant_id, i.pay_amount, i.item_name, i.weidian_item_id,
                 reporting_product.id AS reporting_product_id,
      """
          + OrderProductSql.GROUP_KEY
          + """
                 AS spu,
      """
          + OrderProductSql.SPU_ID
          + """
                 AS spu_id
          FROM crm_order_item i JOIN crm_order o ON o.id = i.order_id
      """
          + OrderProductSql.PRODUCT_JOIN
          + OrderProductSql.CATALOG_JOIN
          + """
          WHERE i.tenant_id = #{scope.tenantId} AND o.tenant_id = #{scope.tenantId}
            AND NOT i.is_deleted AND NOT o.is_deleted AND o.pay_time IS NOT NULL
          <if test="scope.shopId != null">AND o.shop_id = #{scope.shopId}</if>
        ), product_items AS NOT MATERIALIZED (
          SELECT i.id, i.order_id, i.pay_amount FROM searchable_items i
          WHERE
      """
          + OrderProductSql.MATCH
          + """
        ),
      </if>
      """;

  String MATCHED_ITEM =
      """
      <if test="scope.productKeyword != null">
        AND EXISTS (SELECT 1 FROM product_items matched WHERE matched.id = i.id)
      </if>
      <if test="scope.affiliateOnly">AND i.kol_id IS NOT NULL</if>
      """;

  String MATCHED_REFUND =
      """
      <if test="scope.productKeyword != null">
        AND EXISTS (SELECT 1 FROM product_items matched WHERE matched.id = r.order_item_id
          AND matched.order_id = r.order_id)
      </if>
      <if test="scope.affiliateOnly">
        AND EXISTS (SELECT 1 FROM crm_order_item line
          WHERE line.tenant_id = #{scope.tenantId} AND NOT line.is_deleted
            AND line.id = r.order_item_id AND line.order_id = r.order_id AND line.kol_id IS NOT NULL)
      </if>
      """;

  String SCOPED_ORDERS =
      """
      <script>
      <bind name="productSearch" value="scope.productKeyword"/>
      WITH
      """
          + PRODUCT_ITEMS
          + """
      scoped_orders AS NOT MATERIALIZED (
        SELECT o.id, o.shop_id, o.kol_id, o.bd_user_id, o.pay_time, o.weidian_order_id,
        <choose>
          <when test="scope.affiliateOnly or scope.productKeyword != null">matched.amount</when>
          <otherwise>o.pay_amount</otherwise>
        </choose>
        AS pay_amount, o.update_time
        FROM crm_order o
        <if test="scope.affiliateOnly or scope.productKeyword != null">
          JOIN LATERAL (
            SELECT COALESCE(SUM(i.pay_amount), 0) AS amount
            FROM crm_order_item i WHERE i.order_id = o.id AND i.tenant_id = #{scope.tenantId}
              AND NOT i.is_deleted
      """
          + MATCHED_ITEM
          + """
            HAVING COUNT(*) &gt; 0
          ) matched ON TRUE
        </if>
        WHERE o.tenant_id = #{scope.tenantId} AND NOT o.is_deleted
          AND o.pay_time IS NOT NULL
        <if test="scope.affiliateOnly">AND o.attribution_status = 'ATTRIBUTED'</if>
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
      SELECT COUNT(DISTINCT reporting_product.id)
      FROM scoped_orders o JOIN crm_order_item i ON i.order_id = o.id AND i.tenant_id = #{scope.tenantId}
      """
          + OrderProductSql.PRODUCT_JOIN
          + """
      WHERE NOT i.is_deleted AND i.weidian_item_id != ''
        AND o.pay_time &gt;= #{from} AND o.pay_time &lt; #{until}
      """
          + MATCHED_ITEM
          + """
      </script>
      """)
  long creatorSpuCount(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_ORDERS
          + """
      SELECT ROUND(SUM(f.amount_raw), 2)
      FROM scoped_orders o
      JOIN crm_order_settlement_flow f ON f.shop_id = o.shop_id AND f.weidian_order_id = o.weidian_order_id
        AND (f.order_id IS NULL OR f.order_id = o.id)
        AND f.tenant_id = #{scope.tenantId} AND NOT f.is_deleted
      WHERE f.query_type = 'INCOME_DETAIL' AND f.trade_sub_type IN ('货款收入', '退款')
        AND f.trade_time &gt;= #{from} AND f.trade_time &lt; #{until}
      HAVING COUNT(*) &gt; 0 AND COUNT(*) = COUNT(f.amount_raw)
      </script>
      """)
  BigDecimal scopedSettlement(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  /**
   * Current verified snapshots; released means posted to the wallet, independent of withdrawals.
   */
  String COMMISSION_ORDERS =
      SCOPED_ORDERS
          + """
      , states AS (
        SELECT p.bd_user_id AS inviter_id, p.kol_id AS creator_id,
               p.id AS order_id, s.id AS item_state_id, s.accrued_commission_cny AS accrued,
               COALESCE(s.released_commission_cny, 0) AS released,
               (s.verified AND s.kol_id IS NOT DISTINCT FROM p.kol_id
                 AND i.kol_id IS NOT DISTINCT FROM p.kol_id) AS verified,
        <choose>
          <when test="scope.productKeyword != null">
            EXISTS (SELECT 1 FROM product_items matched WHERE matched.id = i.id)
          </when>
          <otherwise>TRUE</otherwise>
        </choose> AS included
        FROM scoped_orders p
        JOIN crm_commission_item_state s ON s.order_id = p.id
          AND s.tenant_id = #{scope.tenantId} AND NOT s.is_deleted
        JOIN crm_order_item i ON i.id = s.order_item_id AND i.order_id = p.id
          AND i.tenant_id = #{scope.tenantId} AND NOT i.is_deleted
        WHERE p.kol_id IS NOT NULL AND p.pay_time &lt; #{until}
        <if test="from != null">AND p.pay_time &gt;= #{from}</if>
      ), commission_orders AS (
        SELECT inviter_id, creator_id, order_id, SUM(accrued) FILTER (WHERE included) AS accrued,
          SUM(released) FILTER (WHERE included) AS released,
          BOOL_OR(accrued &gt; released) AS unsettled
        FROM states GROUP BY inviter_id, creator_id, order_id
        HAVING BOOL_AND(verified) AND BOOL_OR(included)
      )
      """;

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      COMMISSION_ORDERS
          + """
      SELECT creator_id,
        SUM(CASE WHEN unsettled THEN accrued ELSE 0 END) AS estimated,
        SUM(CASE WHEN unsettled THEN accrued ELSE released END) AS total
      FROM commission_orders GROUP BY creator_id
      </script>
      """)
  List<CreatorRankingCommission> creatorRankingCommissions(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  // A paid withdrawal is fully allocated. Its order-item shares preserve historical ownership.
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_ORDERS
          + """
      SELECT o.kol_id AS creator_id, SUM(a.occupied_cny) AS amount
      FROM crm_commission_withdrawal_payment p
      JOIN crm_commission_withdrawal w ON w.id = p.withdrawal_id AND w.tenant_id = p.tenant_id
        AND w.status = 'PAID'
      JOIN crm_commission_withdrawal_allocation a ON a.withdrawal_id = w.id AND a.tenant_id = w.tenant_id
      JOIN crm_order_item i ON i.id = a.order_item_id AND i.tenant_id = a.tenant_id AND NOT i.is_deleted
      JOIN scoped_orders o ON o.id = i.order_id AND o.kol_id = w.kol_id
      WHERE p.tenant_id = #{scope.tenantId} AND p.paid_at &gt;= #{from} AND p.paid_at &lt; #{until}
      """
          + MATCHED_ITEM
          + """
      GROUP BY o.kol_id
      </script>
      """)
  List<CreatorCommissionExpense> creatorCommissionExpenses(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  // This read model uses wallet postings, not mutable release balances, for confirmed deductions.
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      COMMISSION_ORDERS
          + """
      , verified AS MATERIALIZED (
        SELECT s.item_state_id AS id, s.creator_id AS kol_id
        FROM states s JOIN commission_orders o ON o.order_id = s.order_id
        WHERE s.included
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
      SELECT (SELECT SUM(CASE WHEN unsettled THEN accrued ELSE released END) FROM commission_orders) AS total,
             CASE WHEN EXISTS (SELECT 1 FROM verified)
               AND NOT EXISTS (SELECT 1 FROM adjustments WHERE refund IS NULL)
               THEN (SELECT COALESCE(SUM(amount) FILTER (WHERE refund), 0) FROM adjustments)
             END AS clawback
      </script>
      """)
  CreatorCommissionDTO creatorCommission(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      COMMISSION_ORDERS
          + """
      SELECT inviter_id,
        COALESCE(SUM(accrued) FILTER (WHERE unsettled), 0) AS estimated,
        COALESCE(SUM(released) FILTER (WHERE NOT unsettled), 0) AS actual
      FROM commission_orders WHERE inviter_id IS NOT NULL GROUP BY inviter_id
      </script>
      """)
  List<InviterCommissionDTO> inviterCommissions(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_PAID_ORDERS
          + """
      commission_orders AS (
        SELECT p.id, BOOL_AND(s.id IS NOT NULL) AS verified,
          SUM(s.accrued_commission_cny) AS accrued,
          BOOL_OR(s.accrued_commission_cny &gt; COALESCE(s.released_commission_cny, 0)) AS unsettled
        FROM paid p
        JOIN crm_order_item i ON i.order_id = p.id AND i.tenant_id = #{scope.tenantId}
          AND NOT i.is_deleted AND i.kol_id IS NOT NULL
        LEFT JOIN crm_commission_item_state s ON s.order_item_id = i.id AND s.order_id = p.id
          AND s.tenant_id = i.tenant_id AND s.kol_id = i.kol_id AND s.verified AND NOT s.is_deleted
        GROUP BY p.id
      )
      SELECT CASE WHEN COUNT(*) FILTER (WHERE NOT verified) = 0
        THEN COALESCE(SUM(accrued) FILTER (WHERE unsettled), 0) END
      FROM commission_orders
      </script>
      """)
  BigDecimal unsettledCommission(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_ORDERS
          + """
      SELECT COALESCE(SUM(pay_amount), 0) AS total_amount, COUNT(*) AS total_orders,
        COALESCE(SUM(pay_amount) FILTER (WHERE pay_time &gt;= #{dayStart}), 0) AS today_amount,
        COUNT(*) FILTER (WHERE pay_time &gt;= #{dayStart}) AS today_orders
      FROM scoped_orders WHERE pay_time &lt; #{asOf}
      </script>
      """)
  SalesWindowDTO salesWindow(
      @Param("scope") ScopeDTO scope,
      @Param("dayStart") Instant dayStart,
      @Param("asOf") Instant asOf);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_ORDERS
          + """
      , commission_lines AS (
        SELECT p.pay_time, s.id, s.accrued_commission_cny
        FROM scoped_orders p
        JOIN crm_order_item i ON i.order_id = p.id AND i.tenant_id = #{scope.tenantId}
          AND NOT i.is_deleted AND i.kol_id IS NOT NULL
        LEFT JOIN crm_commission_item_state s ON s.order_item_id = i.id AND s.order_id = p.id
          AND s.tenant_id = i.tenant_id AND s.kol_id = i.kol_id AND s.verified AND NOT s.is_deleted
        WHERE p.pay_time &lt; #{asOf}
      )
      SELECT CASE WHEN COUNT(*) FILTER (WHERE id IS NULL) = 0
          THEN COALESCE(SUM(accrued_commission_cny), 0) END AS total_amount,
        CASE WHEN COUNT(*) FILTER (WHERE id IS NULL AND pay_time &gt;= #{dayStart}) = 0
          THEN COALESCE(SUM(accrued_commission_cny) FILTER (WHERE pay_time &gt;= #{dayStart}), 0)
        END AS today_amount
      FROM commission_lines
      </script>
      """)
  CommissionWindowDTO commissionWindow(
      @Param("scope") ScopeDTO scope,
      @Param("dayStart") Instant dayStart,
      @Param("asOf") Instant asOf);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED_ORDERS
          + """
      <choose>
        <when test="dimension.name() == 'SHOP'">
          SELECT p.shop_id AS entity_id, COUNT(*) AS quantity, SUM(p.pay_amount) AS amount
          FROM scoped_orders p WHERE p.pay_time &lt; #{asOf}
          <if test="from != null">AND p.pay_time &gt;= #{from}</if>
          GROUP BY p.shop_id
        </when>
        <otherwise>
          SELECT p.kol_id AS entity_id, SUM(i.quantity) AS quantity, SUM(i.pay_amount) AS amount
          FROM scoped_orders p
          JOIN crm_order_item i ON i.order_id = p.id AND i.tenant_id = #{scope.tenantId}
            AND NOT i.is_deleted AND i.kol_id IS NOT NULL
          WHERE p.pay_time &lt; #{asOf} AND p.kol_id IS NOT NULL
          <if test="from != null">AND p.pay_time &gt;= #{from}</if>
          GROUP BY p.kol_id
        </otherwise>
      </choose>
      ORDER BY amount DESC NULLS LAST, entity_id
      </script>
      """)
  List<WorkbenchRankDTO> workbenchRanks(
      @Param("scope") ScopeDTO scope,
      @Param("dimension") RankDimension dimension,
      @Param("from") Instant from,
      @Param("asOf") Instant asOf);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      COMMISSION_ORDERS
          + """
      SELECT creator_id, SUM(CASE WHEN unsettled THEN accrued ELSE released END) AS total
      FROM commission_orders WHERE inviter_id IS NOT NULL GROUP BY creator_id
      </script>
      """)
  List<CreatorCommissionTotal> inviterCreatorCommissions(
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
      SCOPED_ORDERS
          + """
      , period_orders AS (
        SELECT p.id, p.bd_user_id, p.kol_id, p.pay_time, p.pay_amount
        FROM scoped_orders p
        WHERE p.bd_user_id IS NOT NULL AND p.kol_id IS NOT NULL
          AND p.pay_time &gt;= #{from} AND p.pay_time &lt; #{until}
      ), cohort AS (
        SELECT p.id, SUM(r.amount) AS amount
        FROM crm_refund r JOIN period_orders p ON r.order_id = p.id
        WHERE r.tenant_id = #{scope.tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
      """
          + COHORT_REFUND_WINDOW
          + MATCHED_REFUND
          + """
        GROUP BY p.id
      ), creators AS (
        SELECT p.bd_user_id AS inviter_id, p.kol_id AS creator_id,
          SUM(p.pay_amount - COALESCE(c.amount, 0)) AS net_sales_amount
        FROM period_orders p LEFT JOIN cohort c ON c.id = p.id
        GROUP BY p.bd_user_id, p.kol_id
      )
      SELECT DISTINCT ON (inviter_id) inviter_id, creator_id, net_sales_amount
      FROM creators ORDER BY inviter_id, net_sales_amount DESC, creator_id
      </script>
      """)
  List<BestCreatorDTO> bestCreators(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

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
      """
          + MATCHED_ITEM
          + """
      ),
      cohort AS (
        SELECT COALESCE(SUM(r.amount), 0) AS amount, MAX(r.update_time) AS updated_at
        FROM crm_refund r JOIN paid p ON p.id = r.order_id
        WHERE r.tenant_id = #{scope.tenantId} AND NOT r.is_deleted
          AND r.refund_status = 'SUCCESS'
      """
          + COHORT_REFUND_WINDOW
          + MATCHED_REFUND
          + """
      ),
      completed AS (
        SELECT COALESCE(SUM(r.amount), 0) AS amount, COUNT(DISTINCT r.order_id) AS refunded_orders,
               MAX(GREATEST(r.update_time, o.update_time)) AS updated_at
        FROM crm_refund r JOIN scoped_orders o ON o.id = r.order_id
        WHERE r.tenant_id = #{scope.tenantId} AND NOT r.is_deleted
          AND r.refund_status = 'SUCCESS'
          AND r.finish_time &gt;= #{from} AND r.finish_time &lt; #{until}
      """
          + MATCHED_REFUND
          + """
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
        SELECT o.entity_id, SUM(i.quantity) AS units,
               COUNT(DISTINCT reporting_product.id) AS spu_count,
               COUNT(DISTINCT (o.shop_id, i.weidian_item_id, i.sku_id))
                 FILTER (WHERE i.weidian_item_id != '' AND i.sku_id != '') AS sku_count,
               COUNT(DISTINCT i.kol_item_id) FILTER (WHERE i.kol_id = o.kol_id) AS link_count
        FROM crm_order_item i JOIN ranked_paid o ON o.id = i.order_id
      """
          + OrderProductSql.PRODUCT_JOIN
          + """
        WHERE i.tenant_id = #{scope.tenantId} AND NOT i.is_deleted
      """
          + MATCHED_ITEM
          + """
        GROUP BY o.entity_id
      ),
      cohort AS (
        SELECT p.entity_id, SUM(r.amount) AS amount
        FROM crm_refund r JOIN ranked_paid p ON p.id = r.order_id
        WHERE r.tenant_id = #{scope.tenantId} AND NOT r.is_deleted
          AND r.refund_status = 'SUCCESS'
      """
          + COHORT_REFUND_WINDOW
          + MATCHED_REFUND
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
      """
          + MATCHED_REFUND
          + """
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
