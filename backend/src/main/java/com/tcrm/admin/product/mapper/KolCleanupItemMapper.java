package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.KolCleanupItemDO;
import com.tcrm.admin.product.model.KolCleanupLatestRow;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KolCleanupItemMapper extends BaseMapper<KolCleanupItemDO> {
  @Select(
      """
      SELECT DISTINCT kol_id FROM crm_kol_cleanup_item
      WHERE status = 'PENDING' AND is_deleted = FALSE
      ORDER BY kol_id
      LIMIT #{limit}
      """)
  List<Long> listPendingKolIds(@Param("limit") int limit);

  @Select(
      """
      SELECT DISTINCT kol_id FROM crm_kol_cleanup_item
      WHERE status = 'PENDING'
        AND is_deleted = FALSE
        AND update_time < #{staleBefore}
      ORDER BY kol_id
      LIMIT #{limit}
      """)
  List<Long> listStalePendingKolIds(
      @Param("staleBefore") Instant staleBefore, @Param("limit") int limit);

  /**
   * Cleanup rows of the given mappings, newest first. The caller keeps the first row of each
   * mapping. {@code ids} must be non-empty.
   */
  @Select(
      """
      <script>
      SELECT kol_item_id, status, reason, fail_reason, cleanup_time
      FROM crm_kol_cleanup_item
      WHERE is_deleted = FALSE
        AND kol_item_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY cleanup_time DESC, id DESC
      </script>
      """)
  List<KolCleanupLatestRow> listByKolItemIds(@Param("ids") Collection<Long> ids);
}
