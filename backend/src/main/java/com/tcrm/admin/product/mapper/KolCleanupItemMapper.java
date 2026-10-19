package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.KolCleanupItemDO;
import java.time.Instant;
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
}
