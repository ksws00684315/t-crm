package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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

  /** All live flows of one remote order id; the commission basis check filters by query type. */
  @Select(
      """
      SELECT * FROM crm_order_settlement_flow
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
