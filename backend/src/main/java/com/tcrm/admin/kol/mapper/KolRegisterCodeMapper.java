package com.tcrm.admin.kol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kol.model.KolRegisterCodeDO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KolRegisterCodeMapper extends BaseMapper<KolRegisterCodeDO> {
  /**
   * Locks every link of this BD for the rest of the transaction so a concurrent issue or rotate
   * waits. An empty result locks nothing; the partial unique index still rejects a second insert.
   */
  @Select("SELECT id FROM crm_kol_register_code WHERE bd_user_id = #{bdUserId} FOR UPDATE")
  List<Long> lockByBd(@Param("bdUserId") long bdUserId);

  /** Revokes the active link, if any. The company predicate is added by the tenant plugin. */
  @Update(
      "UPDATE crm_kol_register_code SET status = 0, update_by = #{actor}, update_time = #{time}"
          + " WHERE bd_user_id = #{bdUserId} AND status = 1")
  int revokeActive(
      @Param("bdUserId") long bdUserId, @Param("actor") long actor, @Param("time") Instant time);
}
