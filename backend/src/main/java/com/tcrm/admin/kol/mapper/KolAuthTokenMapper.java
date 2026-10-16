package com.tcrm.admin.kol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kol.model.KolAuthTokenDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KolAuthTokenMapper extends BaseMapper<KolAuthTokenDO> {
  /**
   * Returns the live token row for this digest, or null when unknown, already used or expired. Runs
   * in system scope (public endpoints validate tokens before any login exists).
   */
  @Select(
      "SELECT id, tenant_id, kol_id, purpose, expires_at, create_time FROM crm_kol_auth_token"
          + " WHERE token_hash = #{hash} AND used_at IS NULL AND expires_at > now() LIMIT 1")
  KolAuthTokenDO findLiveByHash(@Param("hash") String hash);

  /**
   * Marks one token row used; 0 rows means a concurrent request consumed it first. Callers run in
   * system scope and inside the transaction that applies the token.
   */
  @Update("UPDATE crm_kol_auth_token SET used_at = now() WHERE id = #{id} AND used_at IS NULL")
  int markUsed(@Param("id") Long id);

  /**
   * Marks every unused token of this KOL and purpose used (resending invalidates the previous
   * invitation; a successful reset invalidates the remaining reset links).
   */
  @Update(
      "UPDATE crm_kol_auth_token SET used_at = now() WHERE kol_id = #{kolId}"
          + " AND purpose = #{purpose} AND used_at IS NULL")
  int invalidateUnused(@Param("kolId") Long kolId, @Param("purpose") String purpose);
}
