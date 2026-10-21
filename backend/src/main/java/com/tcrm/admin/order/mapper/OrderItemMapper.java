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
