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
   * Locks the account's links in this company for the rest of the transaction. An empty result
   * locks nothing; the tenant/account partial unique index still rejects a duplicate insert.
   */
  @Select(
      "SELECT id FROM crm_kol_register_code WHERE tenant_id = #{tenantId}"
          + " AND bd_user_id = #{bdUserId} FOR UPDATE")
  List<Long> lockByBd(@Param("tenantId") long tenantId, @Param("bdUserId") long bdUserId);

  /** Revokes only the active link owned by this account in this company. */
  @Update(
      "UPDATE crm_kol_register_code SET status = 0, update_by = #{actor}, update_time = #{time}"
          + " WHERE tenant_id = #{tenantId} AND bd_user_id = #{bdUserId} AND status = 1")
  int revokeActive(
      @Param("tenantId") long tenantId,
      @Param("bdUserId") long bdUserId,
      @Param("actor") long actor,
      @Param("time") Instant time);
}
