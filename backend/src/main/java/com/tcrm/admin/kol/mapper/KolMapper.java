package com.tcrm.admin.kol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kol.model.KolDO;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KolMapper extends BaseMapper<KolDO> {
  /** Next KOL code number from crm_kol_code_seq; numbers are never returned twice. */
  @Select("SELECT nextval('crm_kol_code_seq')")
  long nextCodeNumber();

  /** True when the code is taken by any row, including logically deleted ones (never reused). */
  @Select("SELECT EXISTS (SELECT 1 FROM crm_kol WHERE kol_code = #{code})")
  boolean existsCode(@Param("code") String code);

  /** Locks a live KOL row for the rest of the transaction; returns null when absent. */
  @Select("SELECT id FROM crm_kol WHERE id = #{id} AND is_deleted = FALSE FOR UPDATE")
  Long lockById(@Param("id") Long id);

  /**
   * Latest pay_time among ATTRIBUTED orders of the KOL (task 25a cleanup). Cross-table read kept in
   * this mapper so the kol module does not depend on order.api (avoids a package cycle).
   */
  @Select(
      """
      SELECT MAX(pay_time) FROM crm_order
      WHERE kol_id = #{kolId}
        AND attribution_status = 'ATTRIBUTED'
        AND pay_time IS NOT NULL
        AND is_deleted = FALSE
      """)
  Instant lastAttributedPayTime(@Param("kolId") Long kolId);

  /**
   * Ends current exclusive-item mappings of the KOL (task 25a cleanup). Cross-table write kept here
   * so kol does not depend on product.api.
   */
  @Update(
      """
      UPDATE crm_kol_item
         SET valid_to = #{at}, update_by = #{actorId}, update_time = #{at}
       WHERE kol_id = #{kolId} AND valid_to IS NULL
      """)
  int closeItemMappings(
      @Param("kolId") Long kolId, @Param("at") Instant at, @Param("actorId") Long actorId);
}
