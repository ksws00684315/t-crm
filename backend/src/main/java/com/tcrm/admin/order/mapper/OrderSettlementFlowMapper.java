package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.order.api.OrderEvidenceApi.SettlementFlowRow;
import com.tcrm.admin.order.api.OrderStatsApi.ShopSettlementDTO;
import com.tcrm.admin.order.model.OrderSettlementFlowDO;
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
      SELECT ROUND(COALESCE(SUM(amount_raw) FILTER (WHERE trade_sub_type = '货款收入'), 0), 2) AS increase,
             ROUND(COALESCE(SUM(-amount_raw) FILTER (WHERE trade_sub_type = '退款'), 0), 2) AS decrease
      FROM crm_order_settlement_flow
      WHERE tenant_id = #{tenantId} AND is_deleted = FALSE AND query_type = 'INCOME_DETAIL'
        AND trade_time &gt;= #{from} AND trade_time &lt; #{until}
        AND shop_id IN
        <foreach collection="shopIds" item="shopId" open="(" separator="," close=")">#{shopId}</foreach>
        AND trade_sub_type IN ('货款收入', '退款')
      HAVING COUNT(*) &gt; 0 AND COUNT(*) = COUNT(amount_raw)
      </script>
      """)
  ShopSettlementDTO shopReceivables(
      @Param("tenantId") long tenantId,
      @Param("shopIds") List<Long> shopIds,
      @Param("from") Instant from,
      @Param("until") Instant until);

  String RECEIVABLES_FILTER =
      """
      tenant_id = #{tenantId} AND shop_id = #{shopId} AND NOT is_deleted
        AND query_type = 'INCOME_DETAIL' AND trade_sub_type IN ('货款收入', '退款')
        AND trade_time >= #{from} AND trade_time < #{until}
      """;

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      WITH paged AS (
        SELECT id, weidian_order_id, refund_id, trade_time, trade_sub_type, amount_raw
        FROM crm_order_settlement_flow
        WHERE
      """
          + RECEIVABLES_FILTER
          + """
        ORDER BY trade_time DESC, id DESC LIMIT #{size} OFFSET #{offset}
      )
      SELECT f.id, f.weidian_order_id AS order_no,
             CASE WHEN item.item_count = 1 THEN item.item_name END AS product,
             CASE WHEN item.item_count = 1 THEN item.sku_name END AS sku,
             CASE WHEN item.item_count = 1 THEN p.image_url END AS image_url,
             item.item_count,
             CASE WHEN f.trade_sub_type = '退款' THEN 'REFUND' ELSE 'INCREASE' END AS amount_type,
             ROUND(f.amount_raw, 2) AS amount, f.trade_time AS event_time
      FROM paged f
      LEFT JOIN crm_order o ON o.tenant_id = #{tenantId} AND o.shop_id = #{shopId}
        AND o.weidian_order_id = f.weidian_order_id AND NOT o.is_deleted
      LEFT JOIN LATERAL (
        SELECT CASE WHEN COUNT(*) = 1 THEN MIN(r.order_item_id) END AS item_id
        FROM crm_refund r WHERE r.tenant_id = #{tenantId} AND r.order_id = o.id
          AND NOT r.is_deleted AND r.weidian_refund_no = f.refund_id
      ) refund ON TRUE
      LEFT JOIN LATERAL (
        SELECT i.item_name, i.sku_name, i.product_id, COUNT(*) OVER ()::int AS item_count
        FROM crm_order_item i
        WHERE i.tenant_id = #{tenantId} AND i.order_id = o.id AND NOT i.is_deleted
          AND (refund.item_id IS NULL OR i.id = refund.item_id)
        ORDER BY i.id LIMIT 1
      ) item ON TRUE
      LEFT JOIN crm_product p ON p.id = item.product_id AND p.tenant_id = #{tenantId}
      ORDER BY f.trade_time DESC, f.id DESC
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
