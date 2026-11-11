package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.CopyJobItemDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopyJobItemMapper extends BaseMapper<CopyJobItemDO> {
  /** Explicit columns of {@link CopyJobItemDO} instead of SELECT * (task 145, audit C-02). */
  String SELECT_FROM =
      "SELECT id, job_id, tenant_id, source_channel, source_product_id, source_shop_id, rule_id, status, target_item_id, error_message, update_time FROM crm_copy_job_item ";

  @Select(
      SELECT_FROM
          + """
      WHERE job_id = #{jobId}
      ORDER BY id
      """)
  List<CopyJobItemDO> listByJob(@Param("jobId") Long jobId);

  @Select(
      SELECT_FROM
          + """
      WHERE job_id = #{jobId} AND status = 'pending'
      ORDER BY id
      """)
  List<CopyJobItemDO> listPendingByJob(@Param("jobId") Long jobId);
}
