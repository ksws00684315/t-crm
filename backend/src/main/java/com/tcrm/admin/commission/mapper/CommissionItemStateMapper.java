package com.tcrm.admin.commission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Commission item snapshot SQL (task 62). Company isolation is the tenant plugin's job; the
 * aggregate queries group by KOL after the plugin adds its filter.
 */
@Mapper
public interface CommissionItemStateMapper extends BaseMapper<CommissionItemStateDO> {

  @Select(
      """
      SELECT * FROM crm_commission_item_state WHERE order_item_id = #{orderItemId} FOR UPDATE
      """)
  CommissionItemStateDO lockByOrderItemId(@Param("orderItemId") long orderItemId);

  @Select(
      """
      SELECT * FROM crm_commission_item_state WHERE order_item_id = #{orderItemId}
      """)
  CommissionItemStateDO findByOrderItemId(@Param("orderItemId") long orderItemId);

  @Select(
      "<script>SELECT * FROM crm_commission_item_state WHERE order_item_id IN "
          + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " AND is_deleted = FALSE ORDER BY order_item_id</script>")
  List<CommissionItemStateDO> listByOrderItemIds(@Param("ids") Collection<Long> ids);

  @Select(
      "<script>SELECT * FROM crm_commission_item_state WHERE order_id IN "
          + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " AND is_deleted = FALSE ORDER BY order_id, order_item_id</script>")
  List<CommissionItemStateDO> listByOrderIds(@Param("ids") Collection<Long> ids);

  @Select(
      """
      SELECT * FROM crm_commission_item_state
      WHERE verified AND is_deleted = FALSE AND release_at IS NOT NULL AND release_at <= #{now}
        AND accrued_commission_cny > released_commission_cny
      ORDER BY release_at, id
      LIMIT #{limit}
      """)
  List<CommissionItemStateDO> listDueForRelease(
      @Param("now") Instant now, @Param("limit") int limit);

  @Select("SELECT * FROM crm_commission_item_state WHERE id = #{id} FOR UPDATE")
  CommissionItemStateDO lockById(@Param("id") long id);

  /**
   * Accrual aggregates per KOL: estimated (accrued), frozen (accrued - released), unverified count.
   */
  @Select(
      "<script>SELECT kol_id,"
          + " COALESCE(SUM(CASE WHEN verified THEN accrued_commission_cny ELSE 0 END), 0) AS estimated_cny,"
          + " COALESCE(SUM(CASE WHEN verified THEN accrued_commission_cny - released_commission_cny ELSE 0 END), 0) AS frozen_cny,"
          + " COUNT(*) FILTER (WHERE NOT verified) AS unverified_count"
          + " FROM crm_commission_item_state WHERE is_deleted = FALSE AND kol_id IN "
          + "<foreach collection='kolIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " GROUP BY kol_id</script>")
  List<KolAccrualRow> aggregateByKol(@Param("kolIds") Collection<Long> kolIds);

  /** Company-wide totals of the income summary row (11.02). */
  @Select(
      """
      SELECT COALESCE(SUM(CASE WHEN verified THEN accrued_commission_cny ELSE 0 END), 0) AS estimated_cny,
             COALESCE(SUM(CASE WHEN verified THEN accrued_commission_cny - released_commission_cny ELSE 0 END), 0) AS frozen_cny,
             COUNT(*) FILTER (WHERE NOT verified) AS unverified_count
      FROM crm_commission_item_state WHERE is_deleted = FALSE
      """)
  KolAccrualRow aggregateCompany();

  /** Per-order accrual roll-up for the settlement detail list (13.05). */
  @Select(
      "<script>SELECT order_id,"
          + " COALESCE(SUM(accrued_commission_cny), 0) AS estimated_cny,"
          + " COUNT(*) FILTER (WHERE NOT verified) AS unverified_count"
          + " FROM crm_commission_item_state WHERE is_deleted = FALSE AND order_id IN "
          + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " GROUP BY order_id</script>")
  List<OrderAccrualRow> aggregateByOrders(@Param("ids") Collection<Long> orderIds);

  @Select(
      "SELECT COUNT(*) FROM crm_commission_item_state"
          + " WHERE is_deleted = FALSE AND kol_id = #{kolId} AND NOT verified")
  long countUnverifiedByKol(@Param("kolId") long kolId);

  /** All live snapshot rows of the KOL, newest business update first (income detail lines). */
  @Select(
      """
      SELECT * FROM crm_commission_item_state
      WHERE is_deleted = FALSE AND kol_id = #{kolId}
      ORDER BY update_time DESC, order_item_id DESC
      """)
  List<CommissionItemStateDO> listByKol(@Param("kolId") long kolId);

  /** Logically deletes snapshots whose order item lines disappeared from a later full sync. */
  @Update(
      "<script>UPDATE crm_commission_item_state SET is_deleted = TRUE, update_time = #{now}"
          + " WHERE order_id = #{orderId} AND is_deleted = FALSE AND order_item_id NOT IN "
          + "<foreach collection='itemIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + "</script>")
  int deleteStaleByOrder(
      @Param("orderId") long orderId,
      @Param("itemIds") Collection<Long> itemIds,
      @Param("now") Instant now);

  /**
   * Moves the released amount into the row after a wallet credit. The accrual guard keeps a
   * concurrent revision from being overwritten; the release service re-locks and re-checks rows.
   */
  @Update(
      """
      UPDATE crm_commission_item_state
      SET released_commission_cny = #{released}, update_time = #{now}
      WHERE id = #{id} AND released_commission_cny = #{previous}
      """)
  int updateReleased(
      @Param("id") long id,
      @Param("previous") BigDecimal previous,
      @Param("released") BigDecimal released,
      @Param("now") Instant now);

  /** Aggregate row of one KOL: null kol_id rows are excluded by the WHERE in the callers. */
  record KolAccrualRow(
      Long kolId, BigDecimal estimatedCny, BigDecimal frozenCny, Long unverifiedCount) {}

  /** Accrual roll-up of one order. */
  record OrderAccrualRow(Long orderId, BigDecimal estimatedCny, Long unverifiedCount) {}
}
