package com.tcrm.admin.link.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tcrm.admin.link.model.ClickCountRow;
import com.tcrm.admin.link.model.ClickLogDO;
import com.tcrm.admin.link.model.KolItemVisitRow;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ClickLogMapper {
  /**
   * Inserts one batch (at most 500 rows); rows whose (id, click_time) already exist are skipped, so
   * redelivered stream messages never create duplicate clicks.
   */
  @Insert(
      """
      <script>
      INSERT INTO crm_click_log (id, click_time, kol_id, product_id, kol_item_id, ip_hash, ua,
        referer, country, is_bot, is_dup, create_time, tenant_id)
      VALUES
      <foreach collection="rows" item="r" separator=",">
        (#{r.id}, #{r.clickTime}, #{r.kolId}, #{r.productId}, #{r.kolItemId}, #{r.ipHash}, #{r.ua},
         #{r.referer}, #{r.country}, #{r.bot}, #{r.dup}, #{r.createTime}, #{r.tenantId})
      </foreach>
      ON CONFLICT (id, click_time) DO NOTHING
      </script>
      """)
  int insertIgnore(@Param("rows") List<ClickLogDO> rows);

  /**
   * Latest {@code click_time} of each link and IP hash inside {@code [from, to]}. One statement:
   * each arm carries {@code kol_id}, {@code product_id} and the time range so PostgreSQL can use
   * {@code idx_crm_click_log_kol_product_time}, then filters {@code kol_item_id} and {@code
   * ip_hash}. {@code keys} must be non-empty.
   */
  @Select(
      """
      <script>
      SELECT kol_item_id, ip_hash, MAX(click_time) AS click_time
      FROM crm_click_log
      WHERE
      <foreach collection="keys" item="k" separator=" OR ">
        (kol_id = #{k.kolId} AND product_id = #{k.productId}
         AND kol_item_id = #{k.kolItemId} AND ip_hash = #{k.ipHash}
         AND click_time &gt;= #{from} AND click_time &lt;= #{to})
      </foreach>
      GROUP BY kol_item_id, ip_hash
      </script>
      """)
  List<ClickLogDO> latestClickTimes(
      @Param("keys") List<ClickLogDO> keys, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Clicks per link (KOL + original product) of one KOL since the given time, one row per product
   * that has clicks; uses idx_crm_click_log_kol_product_time on each partition. Counting by link
   * rather than by kol_item_id keeps the counts when the exclusive item is changed (which creates a
   * new crm_kol_item row while the link stays the same).
   */
  @Select(
      """
      <script>
      SELECT product_id, COUNT(*) AS total,
             COUNT(*) FILTER (WHERE NOT is_bot AND NOT is_dup) AS valid
      FROM crm_click_log
      WHERE kol_id = #{kolId}
        AND product_id IN
      <foreach collection="productIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        AND click_time &gt;= #{since}
      GROUP BY product_id
      </script>
      """)
  List<ClickCountRow> countSince(
      @Param("kolId") long kolId,
      @Param("productIds") Collection<Long> productIds,
      @Param("since") Instant since);

  /**
   * Valid visits of the given mappings (task 39). Bot and duplicate clicks are excluded. Counts by
   * {@code kol_item_id}, the trace key of the creator-item page. {@code ids} must be non-empty.
   */
  @Select(
      """
      <script>
      SELECT kol_item_id AS kol_item_id, COUNT(*) AS visit_count
      FROM crm_click_log
      WHERE NOT is_bot
        AND NOT is_dup
        AND kol_item_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      GROUP BY kol_item_id
      </script>
      """)
  List<KolItemVisitRow> countVisitsByKolItemIds(@Param("ids") Collection<Long> ids);

  /**
   * Names of the partitions currently attached to crm_click_log. Catalog tables have no tenant_id;
   * the tenant line would add a column that does not exist, so this statement bypasses it. Callers
   * still run in system scope.
   */
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      """
      SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
      WHERE i.inhparent = 'crm_click_log'::regclass
      """)
  List<String> listPartitions();

  /**
   * DDL cannot bind parameters: name and bounds are whitelisted by {@code ClickPartitions} (name
   * {@code crm_click_log_yyyymm}, bounds {@code yyyy-MM-01}) before this is called.
   */
  /** Partition DDL cannot be parsed by the tenant line, even in system scope. */
  @InterceptorIgnore(tenantLine = "true")
  @Update(
      "CREATE TABLE IF NOT EXISTS ${name} PARTITION OF crm_click_log"
          + " FOR VALUES FROM ('${from} 00:00:00+00') TO ('${to} 00:00:00+00')")
  void createPartition(
      @Param("name") String name, @Param("from") String from, @Param("to") String to);

  /**
   * Comment of a partition; {@code month} is a whitelisted {@code yyyy-MM}. COMMENT ON is not a
   * tenant statement; bypass the parser so a missed system scope cannot fail the job.
   */
  @InterceptorIgnore(tenantLine = "true")
  @Update("COMMENT ON TABLE ${name} IS 'crm_click_log 的 ${month}（UTC）月分区'")
  void commentPartition(@Param("name") String name, @Param("month") String month);

  /** DETACH is DDL. Bypass the tenant parser; see {@link #createPartition}. */
  @InterceptorIgnore(tenantLine = "true")
  @Update("ALTER TABLE crm_click_log DETACH PARTITION ${name}")
  void detachPartition(@Param("name") String name);

  /** DROP is DDL. Bypass the tenant parser; see {@link #createPartition}. */
  @InterceptorIgnore(tenantLine = "true")
  @Update("DROP TABLE IF EXISTS ${name}")
  void dropPartition(@Param("name") String name);
}
