package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.order.model.KolItemSalesRow;
import com.tcrm.admin.order.model.OrderItemDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemDO> {
  /**
   * Per-shop order-line missing-field counts for the monitoring view (task 40 §3.5). CASE sums
   * instead of FILTER clauses: the tenant interceptor re-parses every statement with JSqlParser.
   * The tenant predicate comes from the interceptor.
   */
  @Select(
      """
      SELECT o.shop_id AS shopId,
             SUM(CASE WHEN i.weidian_item_id IS NULL OR i.weidian_item_id = '' THEN 1 ELSE 0 END) AS missingItemId,
             SUM(CASE WHEN i.pay_amount IS NULL THEN 1 ELSE 0 END) AS missingPayAmount
        FROM crm_order_item i
        JOIN crm_order o ON o.id = i.order_id
       WHERE i.is_deleted = FALSE
       GROUP BY o.shop_id
      """)
  List<ItemQualityRow> missingItemFields();

  /** Mapper row for {@link #missingItemFields()}. */
  class ItemQualityRow {
    private Long shopId;
    private Long missingItemId;
    private Long missingPayAmount;

    public Long getShopId() {
      return shopId;
    }

    public void setShopId(Long shopId) {
      this.shopId = shopId;
    }

    public Long getMissingItemId() {
      return missingItemId;
    }

    public void setMissingItemId(Long value) {
      missingItemId = value;
    }

    public Long getMissingPayAmount() {
      return missingPayAmount;
    }

    public void setMissingPayAmount(Long value) {
      missingPayAmount = value;
    }
  }

  /**
   * Sales of the given mappings in one grouped query. Paid quantity and pay amount count PAID,
   * SHIPPED, RECEIVED and COMPLETED only. Refund amount sums every non-deleted line. {@code ids}
   * must be non-empty. The tenant line limits both tables to the caller's company.
   */
  @Select(
      """
      <script>
      SELECT oi.kol_item_id AS kol_item_id,
             COALESCE(SUM(CASE WHEN o.order_status IN ('PAID', 'SHIPPED', 'RECEIVED', 'COMPLETED')
                               THEN oi.quantity ELSE 0 END), 0) AS sales_quantity,
             COALESCE(SUM(CASE WHEN o.order_status IN ('PAID', 'SHIPPED', 'RECEIVED', 'COMPLETED')
                               THEN oi.pay_amount ELSE 0 END), 0) AS pay_amount,
             COALESCE(SUM(oi.refund_amount), 0) AS refund_amount
      FROM crm_order_item oi
      JOIN crm_order o ON o.id = oi.order_id AND o.is_deleted = FALSE
      WHERE oi.is_deleted = FALSE
        AND oi.kol_item_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      GROUP BY oi.kol_item_id
      </script>
      """)
  List<KolItemSalesRow> sumByKolItemIds(@Param("ids") Collection<Long> ids);
}
