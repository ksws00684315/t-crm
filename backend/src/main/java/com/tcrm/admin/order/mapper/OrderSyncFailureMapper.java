package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.order.model.OrderSyncFailureDO;
import java.time.Instant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderSyncFailureMapper extends BaseMapper<OrderSyncFailureDO> {
  /**
   * Deletes up to {@code limit} company-scoped rows with create_time strictly before {@code
   * cutoff}, oldest first. Tenant line handler limits to the current company.
   */
  @Delete(
      """
      DELETE FROM crm_order_sync_failure
      WHERE id IN (
        SELECT id FROM crm_order_sync_failure
        WHERE create_time < #{cutoff}
        ORDER BY create_time, id
        LIMIT #{limit}
      )
      """)
  int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
