package com.tcrm.admin.commission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Append-only commission item revisions (task 62). */
@Mapper
public interface CommissionItemRevisionMapper extends BaseMapper<CommissionItemRevisionDO> {

  @Select(
      "SELECT rate FROM crm_commission_rate_supplement"
          + " WHERE tenant_id = #{tenantId} AND order_item_id = #{itemId} AND kol_id = #{kolId}")
  BigDecimal supplementedRate(
      @Param("tenantId") long tenantId, @Param("itemId") long itemId, @Param("kolId") long kolId);

  @Insert(
      """
      INSERT INTO crm_commission_rate_supplement
        (id, tenant_id, order_id, order_item_id, kol_id, rate, reason, create_by, create_time)
      VALUES (#{id}, #{tenantId}, #{orderId}, #{itemId}, #{kolId}, #{rate}, #{reason}, #{actorId}, #{now})
      ON CONFLICT (tenant_id, order_item_id, kol_id) DO NOTHING
      """)
  int insertSupplement(
      @Param("id") long id,
      @Param("tenantId") long tenantId,
      @Param("orderId") long orderId,
      @Param("itemId") long itemId,
      @Param("kolId") long kolId,
      @Param("rate") BigDecimal rate,
      @Param("reason") String reason,
      @Param("actorId") long actorId,
      @Param("now") Instant now);

  /** All revisions of the item states, oldest first; used to show rate snapshots per line. */
  @Select(
      "<script>SELECT * FROM crm_commission_item_revision WHERE item_state_id IN "
          + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " ORDER BY item_state_id, create_time, id</script>")
  List<CommissionItemRevisionDO> listByItemStateIds(@Param("ids") Collection<Long> ids);

  /** Latest revision rows (one per item state) for line display. */
  @Select(
      "<script>SELECT r.* FROM crm_commission_item_revision r JOIN ("
          + " SELECT item_state_id, MAX(id) AS max_id FROM crm_commission_item_revision"
          + " WHERE item_state_id IN "
          + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " GROUP BY item_state_id) m ON r.item_state_id = m.item_state_id AND r.id = m.max_id"
          + "</script>")
  List<CommissionItemRevisionDO> listLatestByItemStateIds(@Param("ids") Collection<Long> ids);

  /**
   * Supplemented rates of the given order lines, keyed by line and by the creator the supplement
   * was granted to. Used to mark a line's rate as supplemented: the revision keeps the number but
   * not where it came from, and a missing {@code config_version} alone cannot tell — records
   * predating versioning have none either.
   */
  @Select(
      "<script>SELECT order_item_id AS itemId, kol_id AS kolId, rate"
          + " FROM crm_commission_rate_supplement"
          + " WHERE tenant_id = #{tenantId} AND order_item_id IN "
          + "<foreach collection='itemIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + "</script>")
  List<SupplementRow> listSupplementsByOrderItems(
      @Param("tenantId") long tenantId, @Param("itemIds") Collection<Long> itemIds);

  /** Mapper row for {@link #listSupplementsByOrderItems}. */
  class SupplementRow {
    private Long itemId;
    private Long kolId;
    private BigDecimal rate;

    public Long getItemId() {
      return itemId;
    }

    public void setItemId(Long value) {
      itemId = value;
    }

    public Long getKolId() {
      return kolId;
    }

    public void setKolId(Long value) {
      kolId = value;
    }

    public BigDecimal getRate() {
      return rate;
    }

    public void setRate(BigDecimal value) {
      rate = value;
    }
  }
}
