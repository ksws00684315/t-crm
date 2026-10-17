package com.tcrm.admin.kol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kol.model.KolCopyTokenDO;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KolCopyTokenMapper extends BaseMapper<KolCopyTokenDO> {
  /**
   * Atomically consumes {@code count} from the remaining quota. Returns 0 when the token is not
   * ACTIVE, expired, or would exceed the quota (task 17a §4.5).
   */
  @Update(
      """
      UPDATE crm_kol_copy_token
         SET used = used + #{count},
             last_used_at = #{now},
             update_time = #{now}
       WHERE id = #{id}
         AND is_deleted = FALSE
         AND status = 'ACTIVE'
         AND expires_at > #{now}
         AND used + #{count} <= quota
      """)
  int consume(@Param("id") long id, @Param("count") int count, @Param("now") Instant now);
}
