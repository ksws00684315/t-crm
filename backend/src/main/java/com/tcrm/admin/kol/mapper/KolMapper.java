package com.tcrm.admin.kol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kol.model.KolDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
   * Identity columns including logically deleted rows. Custom SQL is not logic-deleted; the tenant
   * plugin still adds the company predicate.
   */
  @Select(
      """
      <script>
      SELECT id, kol_code, name, status, bd_user_id
      FROM crm_kol
      WHERE id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      </script>
      """)
  List<KolDO> listByIdsIncludingDeleted(@Param("ids") Collection<Long> ids);
}
