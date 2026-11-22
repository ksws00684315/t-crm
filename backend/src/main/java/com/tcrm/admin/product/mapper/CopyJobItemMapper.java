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

  record ItemSequence(long id, int seq) {}

  @Select(
      """
      <script>
      SELECT id, seq FROM (
        SELECT id, row_number() OVER (ORDER BY id) AS seq
        FROM crm_copy_job_item WHERE job_id = #{jobId}
      ) numbered
      WHERE id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      </script>
      """)
  List<ItemSequence> sequences(@Param("jobId") long jobId, @Param("ids") List<Long> ids);

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
