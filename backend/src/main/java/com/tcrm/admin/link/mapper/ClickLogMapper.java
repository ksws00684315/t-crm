package com.tcrm.admin.link.mapper;

import com.tcrm.admin.link.model.ClickCountRow;
import com.tcrm.admin.link.model.ClickLogDO;
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
        referer, country, is_bot, is_dup, create_time)
      VALUES
      <foreach collection="rows" item="r" separator=",">
        (#{r.id}, #{r.clickTime}, #{r.kolId}, #{r.productId}, #{r.kolItemId}, #{r.ipHash}, #{r.ua},
         #{r.referer}, #{r.country}, #{r.bot}, #{r.dup}, #{r.createTime})
      </foreach>
      ON CONFLICT (id, click_time) DO NOTHING
      </script>
      """)
  int insertIgnore(@Param("rows") List<ClickLogDO> rows);

  /**
   * Clicks per link since the given time; uses idx_crm_click_log_kol_item_time on each partition.
   */
  @Select(
      """
      <script>
      SELECT kol_item_id, COUNT(*) AS total,
             COUNT(*) FILTER (WHERE NOT is_bot AND NOT is_dup) AS valid
      FROM crm_click_log
      WHERE kol_item_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        AND click_time &gt;= #{since}
      GROUP BY kol_item_id
      </script>
      """)
  List<ClickCountRow> countSince(
      @Param("ids") Collection<Long> kolItemIds, @Param("since") Instant since);

  /** Names of the partitions currently attached to crm_click_log. */
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
  @Update(
      "CREATE TABLE IF NOT EXISTS ${name} PARTITION OF crm_click_log"
          + " FOR VALUES FROM ('${from} 00:00:00+00') TO ('${to} 00:00:00+00')")
  void createPartition(
      @Param("name") String name, @Param("from") String from, @Param("to") String to);

  /** Comment of a partition; {@code month} is a whitelisted {@code yyyy-MM}. */
  @Update("COMMENT ON TABLE ${name} IS 'crm_click_log 的 ${month}（UTC）月分区'")
  void commentPartition(@Param("name") String name, @Param("month") String month);

  @Update("ALTER TABLE crm_click_log DETACH PARTITION ${name}")
  void detachPartition(@Param("name") String name);

  @Update("DROP TABLE IF EXISTS ${name}")
  void dropPartition(@Param("name") String name);
}
