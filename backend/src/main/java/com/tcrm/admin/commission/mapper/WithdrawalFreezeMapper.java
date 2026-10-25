package com.tcrm.admin.commission.mapper;

import com.tcrm.admin.commission.model.WithdrawalRows;
import com.tcrm.admin.commission.model.WithdrawalWrite;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Creator withdrawal freeze SQL; company isolation is the tenant plugin's job. */
@Mapper
public interface WithdrawalFreezeMapper {
  @Select(
      """
      SELECT id, kol_id, reason, frozen_by, frozen_time, resume_reason, resumed_by, resumed_time
      FROM crm_commission_withdrawal_freeze
      WHERE kol_id = #{kolId}
      ORDER BY frozen_time DESC, id DESC
      LIMIT 1
      """)
  WithdrawalRows.FreezeRow latest(@Param("kolId") long kolId);

  @Select(
      """
      SELECT id FROM crm_commission_withdrawal_freeze
      WHERE kol_id = #{kolId} AND resumed_time IS NULL
      FOR UPDATE
      """)
  Long lockActive(@Param("kolId") long kolId);

  @Insert(
      """
      INSERT INTO crm_commission_withdrawal_freeze (
        id, kol_id, reason, frozen_by, frozen_time, create_time, update_time)
      VALUES (#{row.id}, #{row.kolId}, #{row.reason}, #{row.frozenBy}, #{row.now}, #{row.now},
              #{row.now})
      """)
  int insert(@Param("row") WithdrawalWrite.Freeze row);

  @Update(
      """
      UPDATE crm_commission_withdrawal_freeze
      SET resume_reason = #{row.reason,jdbcType=VARCHAR},
          resumed_by = #{row.resumedBy},
          resumed_time = #{row.now},
          update_time = #{row.now}
      WHERE id = #{row.id} AND resumed_time IS NULL
      """)
  int resume(@Param("row") WithdrawalWrite.Resume row);
}
