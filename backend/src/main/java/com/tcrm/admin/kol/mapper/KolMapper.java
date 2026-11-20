package com.tcrm.admin.kol.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kol.model.KolDO;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KolMapper extends BaseMapper<KolDO> {
  @InterceptorIgnore(tenantLine = "true")
  @Insert(
      """
      INSERT INTO crm_kol_reporting_status (tenant_id, kol_id, enabled, changed_at)
      VALUES (#{tenantId}, #{kolId}, #{enabled}, #{changedAt})
      """)
  int recordReportingStatus(
      @Param("tenantId") Long tenantId,
      @Param("kolId") long kolId,
      @Param("enabled") boolean enabled,
      @Param("changedAt") Instant changedAt);

  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      <script>
      WITH eligible AS (
        SELECT id, create_time FROM crm_kol
        WHERE tenant_id = #{tenantId} AND id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ), events AS (
        SELECT e.kol_id, e.enabled, e.changed_at, e.baseline, e.event_id, k.create_time
        FROM eligible k JOIN crm_kol_reporting_status e ON e.kol_id = k.id AND e.tenant_id = #{tenantId}
        UNION ALL
        SELECT k.id, TRUE, k.create_time, FALSE, 0, k.create_time
        FROM eligible k WHERE NOT EXISTS (
          SELECT 1 FROM crm_kol_reporting_status e
          WHERE e.tenant_id = #{tenantId} AND e.kol_id = k.id AND e.baseline
        )
      ), timeline AS (
        SELECT *, LEAD(changed_at) OVER (PARTITION BY kol_id ORDER BY changed_at, event_id) AS next_at
        FROM events
      )
      SELECT CASE WHEN COUNT(*) FILTER (
        WHERE baseline AND GREATEST(create_time, #{from}) &lt; LEAST(changed_at, #{until})
      ) &gt; 0 THEN NULL ELSE COUNT(DISTINCT kol_id) FILTER (
        WHERE enabled AND changed_at &lt; #{until}
          AND (next_at IS NULL OR (next_at &gt; #{from} AND next_at &gt; changed_at))
      ) END
      FROM timeline
      </script>
      """)
  Long countEnabledDuring(
      @Param("tenantId") long tenantId,
      @Param("ids") Collection<Long> ids,
      @Param("from") Instant from,
      @Param("until") Instant until);

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
