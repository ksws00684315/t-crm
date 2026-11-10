package com.tcrm.admin.stats.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.order.api.OrderStatsApi.DailyFactDTO;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Explicit tenant-scoped write model for scheduled rollups. */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface StatsRollupMapper {
  @Select("SELECT scanned_until FROM agg_stats_checkpoint WHERE tenant_id = #{tenantId}")
  Instant checkpoint(long tenantId);

  @Insert(
      """
      INSERT INTO agg_stats_checkpoint (id, tenant_id, scanned_until, create_time, update_time)
      VALUES (#{id}, #{tenantId}, #{until}, #{until}, #{until})
      ON CONFLICT (tenant_id) DO UPDATE
      SET scanned_until = EXCLUDED.scanned_until, update_time = EXCLUDED.update_time
      WHERE agg_stats_checkpoint.scanned_until < EXCLUDED.scanned_until
      """)
  void advance(
      @Param("id") long id, @Param("tenantId") long tenantId, @Param("until") Instant until);

  @Insert(
      """
      INSERT INTO agg_stats_dirty_day (id, tenant_id, stat_date, create_time)
      VALUES (#{id}, #{tenantId}, #{day}, #{now})
      ON CONFLICT (tenant_id, stat_date) DO UPDATE SET create_time = EXCLUDED.create_time
      """)
  void mark(
      @Param("id") long id,
      @Param("tenantId") long tenantId,
      @Param("day") LocalDate day,
      @Param("now") Instant now);

  @Select(
      """
      SELECT stat_date FROM agg_stats_dirty_day
      WHERE tenant_id = #{tenantId}
      ORDER BY stat_date DESC
      LIMIT 1 FOR UPDATE SKIP LOCKED
      """)
  LocalDate lockNext(long tenantId);

  @Delete("DELETE FROM agg_stats_daily WHERE tenant_id = #{tenantId} AND stat_date = #{day}")
  void clear(@Param("tenantId") long tenantId, @Param("day") LocalDate day);

  @Insert(
      """
      <script>
      INSERT INTO agg_stats_daily (
        id, tenant_id, stat_date, grain, shop_id, kol_id, owner_id, product_code, sku_code,
        paid_orders, units, sales, cohort_refunds, completed_refunds, create_time
      ) VALUES
      <foreach collection="rows" item="row" separator=",">
        (#{row.id}, #{tenantId}, #{day}, #{row.fact.grain}, #{row.fact.shopId}, #{row.fact.kolId},
         #{row.fact.ownerId}, #{row.fact.productCode}, #{row.fact.skuCode}, #{row.fact.paidOrders},
         #{row.fact.units}, #{row.fact.sales}, #{row.fact.cohortRefunds}, #{row.fact.completedRefunds},
         #{now})
      </foreach>
      </script>
      """)
  void write(
      @Param("tenantId") long tenantId,
      @Param("day") LocalDate day,
      @Param("now") Instant now,
      @Param("rows") List<FactRow> rows);

  @Delete("DELETE FROM agg_stats_dirty_day WHERE tenant_id = #{tenantId} AND stat_date = #{day}")
  void remove(@Param("tenantId") long tenantId, @Param("day") LocalDate day);

  record FactRow(long id, DailyFactDTO fact) {}
}
