package com.tcrm.admin.kol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kol.model.KolBdHistoryDO;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KolBdHistoryMapper extends BaseMapper<KolBdHistoryDO> {
  /** Ends the current ownership period of the KOL, if any; returns the number of rows closed. */
  @Update(
      "UPDATE crm_kol_bd_history SET end_time = #{time}, update_time = #{time},"
          + " update_by = #{actor} WHERE kol_id = #{kolId} AND end_time IS NULL"
          + " AND is_deleted = FALSE")
  int closeCurrent(
      @Param("kolId") Long kolId, @Param("time") Instant time, @Param("actor") Long actor);
}
