package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.order.model.OrderSyncRetryDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderSyncRetryMapper extends BaseMapper<OrderSyncRetryDO> {
  /**
   * Removes a retried order once it synchronized. A hard delete, so a later failure of the same
   * order can insert again under the unique key; the tenant line handler adds the company filter.
   */
  @Delete("DELETE FROM crm_order_sync_retry WHERE id = #{id}")
  int deleteResolved(@Param("id") long id);
}
