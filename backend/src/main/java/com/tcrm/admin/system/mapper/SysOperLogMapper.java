package com.tcrm.admin.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.system.model.SysOperLogDO;
import java.time.Instant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysOperLogMapper extends BaseMapper<SysOperLogDO> {
  /**
   * Deletes up to {@code limit} rows with {@code oper_time} strictly before {@code cutoff}, oldest
   * first. The range and order use {@code idx_sys_oper_log_time}.
   */
  @Delete(
      """
      DELETE FROM sys_oper_log
      WHERE id IN (
        SELECT id FROM sys_oper_log
        WHERE oper_time < #{cutoff}
        ORDER BY oper_time, id
        LIMIT #{limit}
      )
      """)
  int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
