package com.tcrm.admin.commission.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.commission.model.PrepayOutboxClaim;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Outbox claims and delivery marks. Delivery updates run inside one company scope. */
@Mapper
public interface PrepayOutboxMapper {
  /**
   * Next due event across companies. The tenant plugin is off for this statement (spec 06): the
   * scheduled worker has no single company. The join pairs each event with the prepay of the same
   * company ({@code p.tenant_id = o.tenant_id}).
   */
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      SELECT o.id, o.tenant_id, o.prepay_id, o.event_type, p.kol_id, p.applicant_id, p.prepay_no
      FROM crm_prepay_outbox o
      JOIN crm_prepay p ON p.tenant_id = o.tenant_id AND p.id = o.prepay_id
      WHERE o.delivered_at IS NULL AND o.next_attempt_at &lt;= #{now}
      ORDER BY o.next_attempt_at, o.id
      LIMIT 1
      FOR UPDATE OF o SKIP LOCKED
      </script>
      """)
  PrepayOutboxClaim claimDue(@Param("now") Instant now);

  @Update(
      """
      UPDATE crm_prepay_outbox
      SET delivered_at = #{deliveredAt}, attempts = attempts + 1
      WHERE id = #{id} AND delivered_at IS NULL
      """)
  int markDelivered(@Param("id") long id, @Param("deliveredAt") Instant deliveredAt);

  @Update(
      """
      UPDATE crm_prepay_outbox
      SET attempts = attempts + 1, next_attempt_at = #{nextAttemptAt}
      WHERE id = #{id} AND delivered_at IS NULL
      """)
  int defer(@Param("id") long id, @Param("nextAttemptAt") Instant nextAttemptAt);
}
