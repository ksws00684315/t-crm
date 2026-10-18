package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.CopyJobItemDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopyJobItemMapper extends BaseMapper<CopyJobItemDO> {
  @Select(
      """
      SELECT * FROM crm_copy_job_item
      WHERE job_id = #{jobId}
      ORDER BY id
      """)
  List<CopyJobItemDO> listByJob(@Param("jobId") Long jobId);

  @Select(
      """
      SELECT * FROM crm_copy_job_item
      WHERE job_id = #{jobId} AND status = 'pending'
      ORDER BY id
      """)
  List<CopyJobItemDO> listPendingByJob(@Param("jobId") Long jobId);
}
