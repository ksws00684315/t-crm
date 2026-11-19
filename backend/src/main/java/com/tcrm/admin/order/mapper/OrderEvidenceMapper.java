package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.order.api.OrderEvidenceApi.DailyAmount;
import com.tcrm.admin.order.api.OrderEvidenceApi.EvidenceRow;
import com.tcrm.admin.order.api.OrderEvidenceApi.Kind;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductBreakdown;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductGroup;
import com.tcrm.admin.order.api.OrderEvidenceApi.Selection;
import com.tcrm.admin.order.api.OrderEvidenceApi.ShopCreatorCounts;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Explicit tenant and attribution predicates match the parent dashboard queries. */
@Mapper
public interface OrderEvidenceMapper {
  String SCOPED =
      """
      <script>
      <bind name="productSearch" value="s.scope.productKeyword"/>
      WITH scoped_orders AS NOT MATERIALIZED (
        SELECT o.id, o.weidian_order_id, o.shop_id, o.kol_id, o.pay_time, o.pay_amount, o.attribution_status
        FROM crm_order o
        WHERE o.tenant_id = #{s.scope.tenantId} AND NOT o.is_deleted AND o.pay_time IS NOT NULL
        <if test="s.scope.shopId != null">AND o.shop_id = #{s.scope.shopId}</if>
        <if test="s.scope.ownerId != null">AND o.bd_user_id = #{s.scope.ownerId}</if>
        <if test="s.scope.inviterId != null">AND o.bd_user_id = #{s.scope.inviterId}</if>
        <if test="s.scope.matchedInviterIds != null">
          <choose>
            <when test="s.scope.matchedInviterIds.isEmpty()">AND FALSE</when>
            <otherwise>
              AND o.bd_user_id IN
              <foreach collection="s.scope.matchedInviterIds" item="inviter" open="(" separator="," close=")">
                #{inviter}
              </foreach>
            </otherwise>
          </choose>
        </if>
        <if test="s.inviter != null">AND COALESCE(o.bd_user_id, 0) = #{s.inviter}</if>
        <if test="s.creator != null">AND COALESCE(o.kol_id, 0) = #{s.creator}</if>
        <if test="s.productCode == null and s.scope.kolId != null">AND o.kol_id = #{s.scope.kolId}</if>
        <if test="s.productCode == null and s.scope.matchedKolIds != null">
          <choose>
            <when test="s.scope.matchedKolIds.isEmpty()">AND FALSE</when>
            <otherwise>
              AND o.kol_id IN
              <foreach collection="s.scope.matchedKolIds" item="matched" open="(" separator="," close=")">
                #{matched}
              </foreach>
            </otherwise>
          </choose>
        </if>
        AND o.shop_id IN
        <foreach collection="s.scope.visibleShopIds" item="shop" open="(" separator="," close=")">
          #{shop}
        </foreach>
      ), visible_items AS NOT MATERIALIZED (
        SELECT i.id, i.order_id, i.tenant_id, i.item_name, i.sku_id, i.sku_name, i.kol_id,
               i.quantity, i.pay_amount, i.weidian_item_id, o.shop_id, o.attribution_status, reporting_product.id AS reporting_product_id,
      """
          + OrderProductSql.GROUP_KEY
          + """
               AS spu,
      """
          + OrderProductSql.SPU_ID
          + """
               AS spu_id
        FROM scoped_orders o JOIN crm_order_item i ON i.order_id = o.id
      """
          + OrderProductSql.PRODUCT_JOIN
          + OrderProductSql.CATALOG_JOIN
          + """
        WHERE i.tenant_id = #{s.scope.tenantId} AND NOT i.is_deleted
      ), scoped_items AS NOT MATERIALIZED (
        SELECT line.* FROM visible_items line WHERE TRUE
        <if test="s.productCode != null">
          <choose>
            <when test="s.productCode.startsWith('product:')">
              AND line.spu = #{s.productCode}
            </when>
            <when test="s.productCode.startsWith('unmapped:')">
              AND line.spu = #{s.productCode}
            </when>
            <otherwise>
              AND line.weidian_item_id = #{s.productCode}
              <if test="s.keyword != null">
                AND (strpos(lower(line.item_name), lower(#{s.keyword})) &gt; 0 OR line.weidian_item_id = #{s.keyword})
              </if>
            </otherwise>
          </choose>
          <if test="s.scope.kolId != null">AND line.kol_id = #{s.scope.kolId}</if>
          <if test="s.scope.matchedKolIds != null">
            <choose>
              <when test="s.scope.matchedKolIds.isEmpty()">AND FALSE</when>
              <otherwise>
                AND line.kol_id IN
                <foreach collection="s.scope.matchedKolIds" item="matched" open="(" separator="," close=")">
                  #{matched}
                </foreach>
              </otherwise>
            </choose>
          </if>
        </if>
        <if test="s.scope.productKeyword != null">
          AND line.id IN (
            SELECT i.id FROM visible_items i WHERE
      """
          + OrderProductSql.MATCH
          + """
          )
        </if>
      ), distribution_items AS NOT MATERIALIZED (
        SELECT * FROM scoped_items
        <if test="s.productCode != null">WHERE attribution_status = 'ATTRIBUTED' AND kol_id IS NOT NULL</if>
      ),
      """;

  String ORDERS =
      """
      SELECT i.id, o.weidian_order_id AS order_no, NULL::text AS refund_no,
             i.item_name AS product, i.sku_name AS sku, i.quantity AS units,
             i.pay_amount AS amount, o.pay_time AS event_time,
             o.shop_id, COALESCE(i.kol_id, o.kol_id) AS creator_id, i.reporting_product_id, i.spu_id,
             o.id AS order_id, i.weidian_item_id AS transaction_item_id
      FROM scoped_orders o JOIN
      <choose>
        <when test="kind.name() == 'ORDERS'">distribution_items</when>
        <otherwise>scoped_items</otherwise>
      </choose>
      i ON i.order_id = o.id
      WHERE o.pay_time &gt;= #{s.from} AND o.pay_time &lt; #{s.until}
      <if test="kind.name() == 'DIRECT_ORDERS'">
        AND i.attribution_status = 'NON_KOL'
        AND i.weidian_item_id = i.spu_id
      </if>
      <if test="kind.name() == 'UNCONFIRMED_ORDERS'">
        AND i.attribution_status != 'NON_KOL'
        AND (i.attribution_status != 'ATTRIBUTED' OR i.kol_id IS NULL)
      </if>
      """;

  String REFUNDS =
      """
      SELECT r.id, o.weidian_order_id AS order_no, r.weidian_refund_no AS refund_no,
             i.item_name AS product, i.sku_name AS sku, NULL::integer AS units,
             r.amount, r.finish_time AS event_time,
             o.shop_id, COALESCE(i.kol_id, o.kol_id) AS creator_id, i.reporting_product_id, i.spu_id, i.weidian_item_id AS transaction_item_id
      FROM scoped_orders o JOIN crm_refund r ON r.order_id = o.id
      <choose>
        <when test="s.productCode != null or s.scope.productKeyword != null">JOIN</when>
        <otherwise>LEFT JOIN</otherwise>
      </choose>
        distribution_items i ON i.id = r.order_item_id AND i.order_id = o.id
      WHERE r.tenant_id = #{s.scope.tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
        AND r.finish_time &gt;= #{s.from} AND r.finish_time &lt; #{s.until}
      """;

  String EVIDENCE =
      SCOPED
          + """
      evidence AS (
        <choose>
          <when test="kind.name() != 'REFUNDS'">
      """
          + ORDERS
          + """
          </when>
          <otherwise>
      """
          + REFUNDS
          + """
          </otherwise>
        </choose>
      )
      """;

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      EVIDENCE
          + """
      , paged AS (
        SELECT evidence.*, event_time AS pay_time FROM evidence
        ORDER BY event_time DESC, id DESC LIMIT #{size} OFFSET #{offset}
      )
      SELECT p.id, p.order_no, p.refund_no, p.product, p.sku, p.units, p.amount, p.event_time,
             p.shop_id, k.name AS creator_name, p.spu_id,
             product.image_url,
             <choose>
               <when test="kind.name() == 'ORDERS'">
                 refunds.amount AS refund_amount, p.amount - refunds.amount AS net_transaction_amount
               </when>
               <otherwise>NULL::numeric AS refund_amount, NULL::numeric AS net_transaction_amount</otherwise>
             </choose>, p.transaction_item_id
      FROM paged p
      LEFT JOIN crm_product product ON product.id = p.reporting_product_id AND product.tenant_id = #{s.scope.tenantId}
      LEFT JOIN crm_kol k ON k.id = p.creator_id AND k.tenant_id = #{s.scope.tenantId}
      <if test="kind.name() == 'ORDERS'">
        LEFT JOIN LATERAL (
          SELECT COALESCE(SUM(r.amount), 0) AS amount
          FROM crm_refund r WHERE r.tenant_id = #{s.scope.tenantId} AND NOT r.is_deleted
            AND r.order_id = p.order_id AND r.order_item_id = p.id AND r.refund_status = 'SUCCESS'
      """
          + OrderStatsMapper.COHORT_REFUND_WINDOW
          + """
        ) refunds ON TRUE
      </if>
      ORDER BY p.event_time DESC, p.id DESC
      </script>
      """)
  List<EvidenceRow> page(
      @Param("s") Selection selection,
      @Param("kind") Kind kind,
      @Param("size") int size,
      @Param("offset") long offset);

  @InterceptorIgnore(tenantLine = "true")
  @Select(EVIDENCE + "SELECT COUNT(*) FROM evidence </script>")
  long count(@Param("s") Selection selection, @Param("kind") Kind kind);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED
          + """
      sellers AS (
        SELECT DISTINCT o.kol_id
        FROM scoped_orders o
        WHERE o.kol_id IS NOT NULL AND o.pay_time &gt;= #{s.from} AND o.pay_time &lt; #{s.until}
        <if test="s.scope.productKeyword != null">
          AND EXISTS (SELECT 1 FROM scoped_items i WHERE i.order_id = o.id)
        </if>
      ), shop_creators AS (
        SELECT kol_id FROM sellers
        UNION
        SELECT m.kol_id FROM crm_kol_item m
        WHERE m.tenant_id = #{s.scope.tenantId} AND m.shop_id = #{s.scope.shopId}
          AND m.valid_from &lt; #{s.until}
          AND (m.valid_to IS NULL OR m.valid_to &gt; GREATEST(#{s.from}, m.valid_from))
        <if test="s.scope.kolId != null">AND m.kol_id = #{s.scope.kolId}</if>
        <if test="s.scope.matchedKolIds != null">
          <choose>
            <when test="s.scope.matchedKolIds.isEmpty()">AND FALSE</when>
            <otherwise>
              AND m.kol_id IN
              <foreach collection="s.scope.matchedKolIds" item="matched" open="(" separator="," close=")">
                #{matched}
              </foreach>
            </otherwise>
          </choose>
        </if>
        <if test="s.scope.ownerId != null or s.scope.inviterId != null or s.scope.matchedInviterIds != null">
          AND EXISTS (
            SELECT 1 FROM crm_kol_bd_history h
            WHERE h.tenant_id = m.tenant_id AND h.kol_id = m.kol_id AND NOT h.is_deleted
              AND GREATEST(h.start_time, m.valid_from, #{s.from})
                   &lt; LEAST(h.end_time, m.valid_to, #{s.until})
            <if test="s.scope.ownerId != null">AND h.bd_user_id = #{s.scope.ownerId}</if>
            <if test="s.scope.inviterId != null">AND h.bd_user_id = #{s.scope.inviterId}</if>
            <if test="s.scope.matchedInviterIds != null">
              <choose>
                <when test="s.scope.matchedInviterIds.isEmpty()">AND FALSE</when>
                <otherwise>
                  AND h.bd_user_id IN
                  <foreach collection="s.scope.matchedInviterIds" item="inviter" open="(" separator="," close=")">
                    #{inviter}
                  </foreach>
                </otherwise>
              </choose>
            </if>
          )
        </if>
      )
      SELECT (SELECT COUNT(*) FROM sellers) AS selling, COUNT(*) AS total FROM shop_creators
      </script>
      """)
  ShopCreatorCounts shopCreatorCounts(@Param("s") Selection selection);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      SCOPED
          + """
      paid AS (
        SELECT o.id, o.pay_time,
        <choose>
          <when test="s.productCode != null or s.scope.productKeyword != null">
            SUM(i.pay_amount) AS sales, SUM(i.quantity) AS units
            FROM scoped_orders o JOIN distribution_items i ON i.order_id = o.id
            WHERE o.pay_time &gt;= #{s.from} AND o.pay_time &lt; #{s.until}
            GROUP BY o.id, o.pay_time
          </when>
          <otherwise>
            o.pay_amount AS sales, COALESCE(item.units, 0) AS units
            FROM scoped_orders o
            LEFT JOIN LATERAL (
              SELECT SUM(i.quantity) AS units FROM crm_order_item i
              WHERE i.tenant_id = #{s.scope.tenantId} AND i.order_id = o.id AND NOT i.is_deleted
            ) item ON TRUE
            WHERE o.pay_time &gt;= #{s.from} AND o.pay_time &lt; #{s.until}
          </otherwise>
        </choose>
      ), activity AS (
        SELECT pay_time AS event_time, sales, 0::numeric AS refunds, 0::numeric AS cohort_refunds,
               1 AS paid_orders, units
        FROM paid
        UNION ALL
        SELECT event_time, 0, amount, 0, 0, 0 FROM (
      """
          + REFUNDS
          + """
        ) refund_events
        UNION ALL
        SELECT p.pay_time, 0, 0, r.amount, 0, 0
        FROM paid p JOIN crm_refund r ON r.order_id = p.id
        <if test="s.productCode != null or s.scope.productKeyword != null">
          JOIN distribution_items i ON i.id = r.order_item_id AND i.order_id = p.id
        </if>
        WHERE r.tenant_id = #{s.scope.tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
      """
          + OrderStatsMapper.COHORT_REFUND_WINDOW
          + """
      )
      SELECT (event_time AT TIME ZONE 'Asia/Shanghai')::date AS day,
             SUM(sales) AS sales, SUM(refunds) AS refunds, SUM(cohort_refunds) AS cohort_refunds,
             SUM(paid_orders) AS paid_orders, SUM(units) AS units
      FROM activity GROUP BY 1 ORDER BY 1
      </script>
      """)
  List<DailyAmount> daily(@Param("s") Selection selection);

  String PRODUCT_GROUPS =
      SCOPED
          + """
      item_groups AS (
        SELECT i.*,
          <choose>
            <when test="group.name() == 'SKUS'">
              CONCAT(i.shop_id, ':', i.weidian_item_id, ':', i.sku_id) AS code,
              CONCAT_WS(' · ', i.sku_name, i.weidian_item_id) AS label
            </when>
            <when test="group.name() == 'SHOPS'">
              i.shop_id::text AS code, shop.shop_name AS label
            </when>
            <otherwise>i.kol_id::text AS code, NULL::text AS label</otherwise>
          </choose>
        FROM distribution_items i
        <if test="group.name() == 'SHOPS'">
          LEFT JOIN crm_shop shop ON shop.id = i.shop_id AND shop.tenant_id = #{s.scope.tenantId}
        </if>
        <if test="group.name() == 'CREATORS'">WHERE i.kol_id IS NOT NULL</if>
      ), paid_items AS (
        SELECT i.*, o.pay_time
        FROM scoped_orders o JOIN item_groups i ON i.order_id = o.id
        WHERE o.pay_time &gt;= #{s.from} AND o.pay_time &lt; #{s.until}
      ), transactions AS (
        SELECT code, label, order_id, quantity, pay_amount AS sales,
               0::numeric AS cohort, 0::numeric AS completed
        FROM paid_items
        UNION ALL
        SELECT p.code, p.label, NULL::bigint, 0, 0, r.amount, 0
        FROM crm_refund r JOIN paid_items p ON p.id = r.order_item_id AND p.order_id = r.order_id
        WHERE r.tenant_id = #{s.scope.tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
      """
          + OrderStatsMapper.COHORT_REFUND_WINDOW
          + """
        UNION ALL
        SELECT i.code, i.label, NULL::bigint, 0, 0, 0, r.amount
        FROM scoped_orders o JOIN crm_refund r ON r.order_id = o.id
        JOIN item_groups i ON i.id = r.order_item_id AND i.order_id = o.id
        WHERE r.tenant_id = #{s.scope.tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
          AND r.finish_time &gt;= #{s.from} AND r.finish_time &lt; #{s.until}
      )
      """;

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      PRODUCT_GROUPS
          + """
      SELECT code, MAX(label) AS name, COUNT(DISTINCT order_id) AS paid_order_count,
             SUM(quantity) AS units, SUM(sales) AS sales_amount,
             SUM(completed) AS completed_refund_amount, SUM(sales) - SUM(cohort) AS net_sales_amount
      FROM transactions GROUP BY code
      ORDER BY net_sales_amount DESC, code
      LIMIT #{size} OFFSET #{offset}
      </script>
      """)
  List<ProductGroup> productGroups(
      @Param("s") Selection selection,
      @Param("group") ProductBreakdown group,
      @Param("size") int size,
      @Param("offset") long offset);

  @InterceptorIgnore(tenantLine = "true")
  @Select(PRODUCT_GROUPS + "SELECT COUNT(DISTINCT code) FROM transactions </script>")
  long countProductGroups(@Param("s") Selection selection, @Param("group") ProductBreakdown group);
}
