package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.CopyJobDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CopyJobMapper extends BaseMapper<CopyJobDO> {
  /** Explicit columns of {@link CopyJobDO} instead of SELECT * (task 145, audit C-02). */
  String SELECT_FROM =
      "SELECT id, tenant_id, kol_id, token_id, job_code, caller_platform, target_shop_id, target_channel, status, total_count, success_count, fail_count, error_message, create_time, update_time, finish_time FROM crm_copy_job ";

  @Select(SELECT_FROM + "WHERE job_code = #{jobCode} LIMIT 1")
  CopyJobDO findByCode(@Param("jobCode") String jobCode);

  @Select(
      """
      SELECT id FROM crm_copy_job
      WHERE status = 'processing'
      ORDER BY create_time
      LIMIT #{limit}
      """)
  List<Long> listProcessingIds(@Param("limit") int limit);

  @Update(
      """
      UPDATE crm_copy_job
      SET success_count = #{successCount},
          fail_count = #{failCount},
          status = #{status},
          update_time = #{updateTime},
          finish_time = CASE
            WHEN #{status} = 'processing' THEN finish_time
            ELSE COALESCE(finish_time, #{updateTime})
          END
      WHERE id = #{id}
      """)
  int updateProgress(
      @Param("id") Long id,
      @Param("successCount") int successCount,
      @Param("failCount") int failCount,
      @Param("status") String status,
      @Param("updateTime") java.time.Instant updateTime);
}
