package com.tcrm.admin.commission.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.commission.model.WithdrawalOutboxClaim;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Withdrawal outbox claims and delivery marks. Delivery updates run inside one company scope. */
@Mapper
public interface WithdrawalOutboxMapper {
  /**
   * Next due event across companies. The tenant plugin is off for this statement (spec 06): the
   * scheduled worker has no single company. The join pairs each event with the withdrawal of the
   * same company.
   */
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      SELECT o.id, o.tenant_id, o.withdrawal_id, o.event_type, w.kol_id, k.kol_code,
             w.application_no
      FROM crm_commission_withdrawal_outbox o
      JOIN crm_commission_withdrawal w
        ON w.tenant_id = o.tenant_id AND w.id = o.withdrawal_id
      LEFT JOIN crm_kol k ON k.tenant_id = w.tenant_id AND k.id = w.kol_id
      WHERE o.delivered_at IS NULL AND o.next_attempt_at &lt;= #{now}
      ORDER BY o.next_attempt_at, o.id
      LIMIT 1
      FOR UPDATE OF o SKIP LOCKED
      </script>
      """)
  WithdrawalOutboxClaim claimDue(@Param("now") Instant now);

  @Update(
      """
      UPDATE crm_commission_withdrawal_outbox
      SET delivered_at = #{deliveredAt}, attempts = attempts + 1
      WHERE id = #{id} AND delivered_at IS NULL
      """)
  int markDelivered(@Param("id") long id, @Param("deliveredAt") Instant deliveredAt);

  @Update(
      """
      UPDATE crm_commission_withdrawal_outbox
      SET attempts = attempts + 1, next_attempt_at = #{nextAttemptAt}
      WHERE id = #{id} AND delivered_at IS NULL
      """)
  int defer(@Param("id") long id, @Param("nextAttemptAt") Instant nextAttemptAt);
}
