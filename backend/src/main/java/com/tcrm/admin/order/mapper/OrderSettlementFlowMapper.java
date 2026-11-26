package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.order.api.OrderEvidenceApi.SettlementFlowRow;
import com.tcrm.admin.order.api.OrderEvidenceApi.SettlementLedgerRow;
import com.tcrm.admin.order.api.OrderEvidenceApi.SettlementLedgerTotals;
import com.tcrm.admin.order.api.OrderStatsApi.ScopeDTO;
import com.tcrm.admin.order.api.OrderStatsApi.ShopSettlementDTO;
import com.tcrm.admin.order.model.OrderSettlementFlowDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Settlement flow SQL (task 76). Company isolation comes from the tenant plugin; matching against
 * {@code crm_order.weidian_order_id} is exact (no suffix truncation).
 */
@Mapper
public interface OrderSettlementFlowMapper extends BaseMapper<OrderSettlementFlowDO> {
  /**
   * Explicit columns of {@link OrderSettlementFlowDO} instead of SELECT * (task 145, audit C-02).
   */
  String SELECT_FROM =
      "SELECT id, create_by, create_time, update_by, update_time, is_deleted, shop_id, weidian_order_id, order_id, refund_id, trade_type, trade_sub_type, trade_time, remark, amount_raw, amount_cny, query_type, fingerprint, first_pulled_at, last_pulled_at, tenant_id FROM crm_order_settlement_flow ";

  @Select(
      """
      <script>
      SELECT ROUND(COALESCE(SUM(amount_cny) FILTER (WHERE trade_sub_type = '货款收入'), 0), 2) AS increase,
             ROUND(COALESCE(SUM(-amount_cny) FILTER (WHERE trade_sub_type = '退款'), 0), 2) AS decrease
      FROM crm_order_settlement_flow
      WHERE tenant_id = #{tenantId} AND is_deleted = FALSE AND query_type = 'INCOME_DETAIL'
        AND trade_time &gt;= #{from} AND trade_time &lt; #{until}
        AND shop_id IN
        <foreach collection="shopIds" item="shopId" open="(" separator="," close=")">#{shopId}</foreach>
        AND trade_sub_type IN ('货款收入', '退款')
      HAVING COUNT(*) &gt; 0 AND COUNT(*) = COUNT(amount_cny)
      </script>
      """)
  ShopSettlementDTO shopReceivables(
      @Param("tenantId") long tenantId,
      @Param("shopIds") List<Long> shopIds,
      @Param("from") Instant from,
      @Param("until") Instant until);

  /** Signed goods and refund flows in yuan. A single complete goods income is required. */
  @Select(
      """
      SELECT SUM(amount_cny)
      FROM crm_order_settlement_flow
      WHERE shop_id = #{shopId} AND weidian_order_id = #{orderNo}
        AND NOT is_deleted AND query_type = 'INCOME_DETAIL'
        AND trade_sub_type IN ('货款收入', '退款')
      HAVING COUNT(*) FILTER (WHERE trade_sub_type = '货款收入') = 1
        AND COUNT(*) = COUNT(amount_cny)
      """)
  BigDecimal orderReceivable(@Param("shopId") long shopId, @Param("orderNo") String orderNo);

  String RECEIVABLES_FILTER =
      """
      tenant_id = #{tenantId} AND shop_id = #{shopId} AND NOT is_deleted
        AND query_type = 'INCOME_DETAIL' AND trade_sub_type IN ('货款收入', '退款')
        AND trade_time >= #{from} AND trade_time < #{until}
      """;

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      WITH paged AS (
        SELECT id, weidian_order_id, refund_id, trade_time, trade_sub_type, amount_cny
        FROM crm_order_settlement_flow
        WHERE <![CDATA[
      """
          + RECEIVABLES_FILTER
          + """
        ]]> ORDER BY trade_time DESC, id DESC LIMIT #{size} OFFSET #{offset}
      )
      SELECT f.id, f.weidian_order_id AS order_no,
             CASE WHEN item.item_count = 1 THEN item.item_name END AS product,
             CASE WHEN item.item_count = 1 THEN item.sku_name END AS sku,
             CASE WHEN item.item_count = 1 THEN item.image_url END AS image_url,
             item.item_count,
             CASE WHEN f.trade_sub_type = '退款' THEN 'REFUND' ELSE 'INCREASE' END AS amount_type,
             f.amount_cny AS amount, f.trade_time AS event_time, item.spu_id
      FROM paged f
      LEFT JOIN crm_order o ON o.tenant_id = #{tenantId} AND o.shop_id = #{shopId}
        AND o.weidian_order_id = f.weidian_order_id AND NOT o.is_deleted
      LEFT JOIN LATERAL (
        SELECT CASE WHEN COUNT(*) = 1 THEN MIN(r.order_item_id) END AS item_id
        FROM crm_refund r WHERE r.tenant_id = #{tenantId} AND r.order_id = o.id
          AND NOT r.is_deleted AND r.weidian_refund_no = f.refund_id
      ) refund ON TRUE
      LEFT JOIN LATERAL (
        SELECT MIN(lines.item_name) AS item_name, MIN(lines.sku_name) AS sku_name,
               MIN(lines.image_url) AS image_url, COUNT(*)::int AS item_count,
               CASE WHEN COUNT(spu_id) = COUNT(*) AND MIN(spu_id) = MAX(spu_id)
                    THEN MIN(spu_id) END AS spu_id
        FROM (
          SELECT i.item_name, i.sku_name, catalog_product.image_url,
      """
          + OrderProductSql.SPU_ID
          + """
                 AS spu_id
          FROM crm_order_item i
      """
          + OrderProductSql.PRODUCT_JOIN
          + OrderProductSql.CATALOG_JOIN
          + """
          WHERE i.tenant_id = #{tenantId} AND i.order_id = o.id AND NOT i.is_deleted
            AND (refund.item_id IS NULL OR i.id = refund.item_id)
        ) lines
        HAVING COUNT(*) &gt; 0
      ) item ON TRUE
      ORDER BY f.trade_time DESC, f.id DESC
      </script>
      """)
  List<SettlementFlowRow> pageShopReceivables(
      @Param("tenantId") long tenantId,
      @Param("shopId") long shopId,
      @Param("from") Instant from,
      @Param("until") Instant until,
      @Param("size") int size,
      @Param("offset") long offset);

  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT COUNT(*) FROM crm_order_settlement_flow WHERE " + RECEIVABLES_FILTER)
  long countShopReceivables(
      @Param("tenantId") long tenantId,
      @Param("shopId") long shopId,
      @Param("from") Instant from,
      @Param("until") Instant until);

  String LEDGER_SCOPE =
      """
      FROM crm_order_settlement_flow f
      WHERE f.tenant_id = #{scope.tenantId} AND NOT f.is_deleted
        AND f.query_type = 'INCOME_DETAIL'
        AND COALESCE(f.trade_sub_type, '') NOT LIKE '%提现%'
        AND f.trade_time &gt;= #{from} AND f.trade_time &lt; #{until}
        AND f.shop_id IN
        <foreach collection="scope.visibleShopIds" item="shop" open="(" separator="," close=")">#{shop}</foreach>
        <if test="scope.shopId != null">AND f.shop_id = #{scope.shopId}</if>
        <if test="scope.ownerId != null">
          AND EXISTS (
            SELECT 1 FROM crm_order o
            WHERE o.tenant_id = f.tenant_id AND o.shop_id = f.shop_id AND NOT o.is_deleted
              AND o.weidian_order_id = f.weidian_order_id AND (f.order_id IS NULL OR f.order_id = o.id)
              AND o.bd_user_id = #{scope.ownerId}
          )
        </if>
      """;

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      WITH paged AS (SELECT f.*
      """
          + LEDGER_SCOPE
          + """
          ORDER BY f.trade_time DESC, f.id DESC LIMIT #{size} OFFSET #{offset})
          SELECT f.id, f.trade_time AS occurred_at,
                 CASE f.trade_type WHEN '收入' THEN 'INCOME' WHEN '支出' THEN 'EXPENSE' ELSE f.trade_type END AS trade_type,
                 f.amount_cny AS amount,
                 f.trade_sub_type AS bill_type, NULL::numeric AS balance, f.weidian_order_id AS order_no,
                 k.name AS creator_name, k.kol_code AS creator_code, commission.amount AS commission_amount
          FROM paged f
          LEFT JOIN LATERAL (
            SELECT o.id, o.kol_id FROM crm_order o
            WHERE o.tenant_id = f.tenant_id AND o.shop_id = f.shop_id AND NOT o.is_deleted
              AND o.weidian_order_id = f.weidian_order_id AND (f.order_id IS NULL OR f.order_id = o.id)
            ORDER BY o.id DESC LIMIT 1
          ) p ON TRUE
          LEFT JOIN crm_kol k ON k.id = p.kol_id AND k.tenant_id = f.tenant_id AND NOT k.is_deleted
          LEFT JOIN LATERAL (
            SELECT SUM(s.accrued_commission_cny) AS amount
            FROM crm_order_item i
            LEFT JOIN crm_commission_item_state s ON s.order_item_id = i.id AND s.order_id = p.id
              AND s.tenant_id = f.tenant_id AND NOT s.is_deleted
            WHERE i.order_id = p.id AND i.tenant_id = f.tenant_id AND NOT i.is_deleted
            HAVING BOOL_AND(
          """
          + OrderStatsMapper.VERIFIED_COMMISSION_LINE
          + """
            )
          ) commission ON p.kol_id IS NOT NULL
          ORDER BY f.trade_time DESC, f.id DESC
          </script>
          """)
  List<SettlementLedgerRow> pageSettlementLedger(
      @Param("scope") ScopeDTO scope,
      @Param("from") Instant from,
      @Param("until") Instant until,
      @Param("size") int size,
      @Param("offset") long offset);

  @InterceptorIgnore(tenantLine = "true")
  @Select("<script>SELECT COUNT(*) " + LEDGER_SCOPE + "</script>")
  long countSettlementLedger(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      SELECT COALESCE(SUM(f.amount_cny) FILTER (
                 WHERE f.trade_type = '收入' AND f.trade_sub_type = '货款收入'), 0) AS settled_goods_amount,
             -COALESCE(SUM(f.amount_cny) FILTER (
                 WHERE f.trade_sub_type IN ('交易手续费', '高频高额技术服务费')), 0) AS platform_fee_amount
      """
          + LEDGER_SCOPE
          + "</script>")
  SettlementLedgerTotals settlementLedgerTotals(
      @Param("scope") ScopeDTO scope, @Param("from") Instant from, @Param("until") Instant until);

  /** All live flows of one remote order id; the commission basis check filters by query type. */
  @Select(
      SELECT_FROM
          + """
      WHERE shop_id = #{shopId} AND weidian_order_id = #{weidianOrderId} AND is_deleted = FALSE
      """)
  List<OrderSettlementFlowDO> listByRemoteOrderId(
      @Param("shopId") long shopId, @Param("weidianOrderId") String weidianOrderId);

  /**
   * Distinct matched orders whose flows were first pulled after {@code since} — the persisted
   * recovery set for the basis recompute (review rework, task 76): a pull that dies mid-way or a
   * failed recompute leaves the checkpoint in place, so the next round finds these orders again
   * even though their fingerprints are no longer new.
   */
  @Select(
      """
      SELECT DISTINCT order_id FROM crm_order_settlement_flow
      WHERE shop_id = #{shopId} AND order_id IS NOT NULL
        AND first_pulled_at > #{since} AND is_deleted = FALSE
      """)
  List<Long> listMatchedOrderIdsSince(@Param("shopId") long shopId, @Param("since") Instant since);

  /** Backfills the local order id of flows that arrived before their order did. */
  @Update(
      """
      UPDATE crm_order_settlement_flow
      SET order_id = #{orderId}, update_time = #{now}
      WHERE shop_id = #{shopId} AND weidian_order_id = #{weidianOrderId}
        AND order_id IS NULL AND is_deleted = FALSE
      """)
  int backfillOrderId(
      @Param("shopId") long shopId,
      @Param("weidianOrderId") String weidianOrderId,
      @Param("orderId") long orderId,
      @Param("now") Instant now);

  /** Refreshes the last-seen timestamp of already-known fingerprints (dedup re-observation). */
  @Update(
      "<script>UPDATE crm_order_settlement_flow SET last_pulled_at = #{now}, update_time = #{now}"
          + " WHERE is_deleted = FALSE AND fingerprint IN "
          + "<foreach collection='fingerprints' item='fp' open='(' separator=',' close=')'>#{fp}</foreach>"
          + "</script>")
  int touchByFingerprints(
      @Param("fingerprints") Collection<String> fingerprints, @Param("now") Instant now);
}
