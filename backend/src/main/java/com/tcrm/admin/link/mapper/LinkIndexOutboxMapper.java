package com.tcrm.admin.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.link.model.LinkIndexOutboxDO;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LinkIndexOutboxMapper extends BaseMapper<LinkIndexOutboxDO> {
  /**
   * Due rows, oldest first. Uses {@code idx_crm_link_index_outbox_due}. The caller passes a
   * positive limit; an empty table returns an empty list.
   */
  @Select(
      """
      SELECT id, scope, scope_id, product_id, attempts, next_attempt_at, last_error, create_time
      FROM crm_link_index_outbox
      WHERE next_attempt_at <= #{now}
      ORDER BY next_attempt_at, id
      LIMIT #{limit}
      """)
  List<LinkIndexOutboxDO> listDue(@Param("now") Instant now, @Param("limit") int limit);

  /** Create time of the oldest row, or null when the table is empty. */
  @Select("SELECT MIN(create_time) FROM crm_link_index_outbox")
  Instant oldestCreateTime();

  /**
   * Rows that have failed at least {@code attempts} times. Uses the partial index {@code
   * idx_crm_link_index_outbox_attempts}.
   */
  @Select(
      """
      SELECT id, scope, scope_id, product_id, attempts, next_attempt_at, last_error, create_time
      FROM crm_link_index_outbox
      WHERE attempts >= #{attempts}
      ORDER BY id
      """)
  List<LinkIndexOutboxDO> listAttemptsAtLeast(@Param("attempts") int attempts);

  /** Deletes the given rows. The caller does not pass an empty collection. */
  @Delete(
      """
      <script>
      DELETE FROM crm_link_index_outbox WHERE id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      </script>
      """)
  int deleteIds(@Param("ids") Collection<Long> ids);
}
