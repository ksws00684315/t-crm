package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.order.model.RefundDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefundMapper extends BaseMapper<RefundDO> {
  /**
   * Per-shop refund missing-field counts for the monitoring view (task 40 §3.5). CASE sums instead
   * of FILTER clauses: the tenant interceptor re-parses every statement with JSqlParser. The tenant
   * predicate comes from the interceptor.
   */
  @Select(
      """
      SELECT o.shop_id AS shopId,
             SUM(CASE WHEN r.order_item_id IS NULL THEN 1 ELSE 0 END) AS missingOrderItemId,
             SUM(CASE WHEN r.refund_status = 'SUCCESS' AND r.finish_time IS NULL THEN 1 ELSE 0 END) AS missingFinishTime
        FROM crm_refund r
        JOIN crm_order o ON o.id = r.order_id
       WHERE r.is_deleted = FALSE
       GROUP BY o.shop_id
      """)
  List<RefundQualityRow> missingRefundFields();

  /** Data provenance per shop: the order source of the newest refund (task 40 §3.5). */
  @Select(
      """
      SELECT o.shop_id AS shopId, o.source AS latestSource
        FROM crm_refund r
        JOIN crm_order o ON o.id = r.order_id AND o.is_deleted = FALSE
       WHERE r.is_deleted = FALSE
         AND r.id IN (
           SELECT MAX(r2.id) FROM crm_refund r2
           JOIN crm_order o2 ON o2.id = r2.order_id AND o2.is_deleted = FALSE
           WHERE r2.is_deleted = FALSE
           GROUP BY o2.shop_id
         )
      """)
  List<RefundSourceRow> latestRefundSourcePerShop();

  /** Mapper row for {@link #missingRefundFields()}. */
  class RefundQualityRow {
    private Long shopId;
    private Long missingOrderItemId;
    private Long missingFinishTime;

    public Long getShopId() {
      return shopId;
    }

    public void setShopId(Long shopId) {
      this.shopId = shopId;
    }

    public Long getMissingOrderItemId() {
      return missingOrderItemId;
    }

    public void setMissingOrderItemId(Long value) {
      missingOrderItemId = value;
    }

    public Long getMissingFinishTime() {
      return missingFinishTime;
    }

    public void setMissingFinishTime(Long value) {
      missingFinishTime = value;
    }
  }

  /** Mapper row for {@link #latestRefundSourcePerShop()}. */
  class RefundSourceRow {
    private Long shopId;
    private Integer latestSource;

    public Long getShopId() {
      return shopId;
    }

    public void setShopId(Long shopId) {
      this.shopId = shopId;
    }

    public Integer getLatestSource() {
      return latestSource;
    }

    public void setLatestSource(Integer value) {
      latestSource = value;
    }
  }
}
