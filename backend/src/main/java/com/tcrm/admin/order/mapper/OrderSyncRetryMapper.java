package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.order.model.OrderSyncRetryDO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderSyncRetryMapper extends BaseMapper<OrderSyncRetryDO> {
  /**
   * Pending retries of one shop, oldest failure first, at most {@code limit} rows. The tenant line
   * handler adds the company filter.
   */
  @Select(
      """
      SELECT id, tenant_id, shop_id, order_id, fail_count, last_error,
             create_time, create_by, update_time, update_by, is_deleted
      FROM crm_order_sync_retry
      WHERE shop_id = #{shopId}
      ORDER BY update_time
      LIMIT #{limit}
      """)
  List<OrderSyncRetryDO> listPending(@Param("shopId") long shopId, @Param("limit") int limit);

  /**
   * Removes a retried order once it synchronized. A hard delete, so a later failure of the same
   * order can insert again under the unique key; the tenant line handler adds the company filter.
   */
  @Delete("DELETE FROM crm_order_sync_retry WHERE id = #{id}")
  int deleteResolved(@Param("id") long id);
}
