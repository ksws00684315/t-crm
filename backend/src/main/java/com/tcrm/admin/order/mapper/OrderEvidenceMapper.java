package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.order.api.OrderEvidenceApi.DailyAmount;
import com.tcrm.admin.order.api.OrderEvidenceApi.EvidenceRow;
import com.tcrm.admin.order.api.OrderEvidenceApi.Kind;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductBreakdown;
import com.tcrm.admin.order.api.OrderEvidenceApi.ProductGroup;
import com.tcrm.admin.order.api.OrderEvidenceApi.Selection;
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
        SELECT o.id, o.weidian_order_id, o.pay_time, o.pay_amount
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
        <if test="s.productCode != null">AND o.attribution_status = 'ATTRIBUTED'</if>
        AND o.shop_id IN
        <foreach collection="s.scope.visibleShopIds" item="shop" open="(" separator="," close=")">
          #{shop}
        </foreach>
      ), scoped_items AS NOT MATERIALIZED (
        SELECT i.id, i.order_id, i.item_name, i.sku_id, i.sku_name, i.kol_id, i.quantity, i.pay_amount
        FROM crm_order_item i
        WHERE i.tenant_id = #{s.scope.tenantId} AND NOT i.is_deleted
        <if test="s.productCode != null">
          AND i.kol_id IS NOT NULL
          <choose>
            <when test="s.productCode.startsWith('product:')">
              AND ('product:' || i.product_id) = #{s.productCode}
            </when>
            <when test="s.productCode.startsWith('unmapped:')">
              AND i.product_id IS NULL AND ('unmapped:' || i.weidian_item_id) = #{s.productCode}
            </when>
            <otherwise>
              AND i.weidian_item_id = #{s.productCode}
              <if test="s.keyword != null">
                AND (strpos(lower(i.item_name), lower(#{s.keyword})) &gt; 0 OR i.weidian_item_id = #{s.keyword})
              </if>
            </otherwise>
          </choose>
          <if test="s.scope.kolId != null">AND i.kol_id = #{s.scope.kolId}</if>
          <if test="s.scope.matchedKolIds != null">
            <choose>
              <when test="s.scope.matchedKolIds.isEmpty()">AND FALSE</when>
              <otherwise>
                AND i.kol_id IN
                <foreach collection="s.scope.matchedKolIds" item="matched" open="(" separator="," close=")">
                  #{matched}
                </foreach>
              </otherwise>
            </choose>
          </if>
        </if>
        <if test="s.scope.productKeyword != null">
          AND
      """
          + OrderStatsMapper.PRODUCT_MATCH
          + """
        </if>
      ),
      """;

  String ORDERS =
      """
      SELECT i.id, o.weidian_order_id AS order_no, NULL::text AS refund_no,
             i.item_name AS product, i.sku_name AS sku, i.quantity AS units,
             i.pay_amount AS amount, o.pay_time AS event_time
      FROM scoped_orders o JOIN scoped_items i ON i.order_id = o.id
      WHERE o.pay_time &gt;= #{s.from} AND o.pay_time &lt; #{s.until}
      """;

  String REFUNDS =
      """
      SELECT r.id, o.weidian_order_id AS order_no, r.weidian_refund_no AS refund_no,
             i.item_name AS product, i.sku_name AS sku, NULL::integer AS units,
             r.amount, r.finish_time AS event_time
      FROM scoped_orders o JOIN crm_refund r ON r.order_id = o.id
      <choose>
        <when test="s.productCode != null or s.scope.productKeyword != null">JOIN</when>
        <otherwise>LEFT JOIN</otherwise>
      </choose>
        scoped_items i ON i.id = r.order_item_id AND i.order_id = o.id
      WHERE r.tenant_id = #{s.scope.tenantId} AND NOT r.is_deleted AND r.refund_status = 'SUCCESS'
        AND r.finish_time &gt;= #{s.from} AND r.finish_time &lt; #{s.until}
      """;

  String EVIDENCE =
      SCOPED
          + """
      evidence AS (
        <choose>
          <when test="kind.name() == 'ORDERS'">
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
      SELECT id, order_no, refund_no, product, sku, units, amount, event_time
      FROM evidence ORDER BY event_time DESC, id DESC LIMIT #{size} OFFSET #{offset}
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
      activity AS (
        <choose>
          <when test="s.productCode != null or s.scope.productKeyword != null">
            SELECT o.pay_time AS event_time, i.pay_amount AS sales, 0::numeric AS refunds
            FROM scoped_orders o JOIN scoped_items i ON i.order_id = o.id
            WHERE o.pay_time &gt;= #{s.from} AND o.pay_time &lt; #{s.until}
          </when>
          <otherwise>
            SELECT o.pay_time AS event_time, o.pay_amount AS sales, 0::numeric AS refunds
            FROM scoped_orders o WHERE o.pay_time &gt;= #{s.from} AND o.pay_time &lt; #{s.until}
          </otherwise>
        </choose>
        UNION ALL
        SELECT event_time, 0::numeric, amount FROM (
      """
          + REFUNDS
          + """
        ) refund_events
      )
      SELECT (event_time AT TIME ZONE 'Asia/Shanghai')::date AS day,
             SUM(sales) AS sales, SUM(refunds) AS refunds
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
            <when test="group.name() == 'SKUS'">i.sku_id AS code, i.sku_name AS label</when>
            <otherwise>i.kol_id::text AS code, NULL::text AS label</otherwise>
          </choose>
        FROM scoped_items i
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
