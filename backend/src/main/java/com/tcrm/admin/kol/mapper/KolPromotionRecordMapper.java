package com.tcrm.admin.kol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kol.model.KolPromotionRecordDO;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KolPromotionRecordMapper extends BaseMapper<KolPromotionRecordDO> {
  /**
   * Latest CONFIG record effective at {@code payTime} (task 62 / ADR-032 §3); the tenant plugin
   * keeps the company scope.
   */
  @Select(
      """
      SELECT version_no, after_json->>'defaultRate' AS rate_text
      FROM crm_kol_promotion_record
      WHERE kol_id = #{kolId} AND change_type = 'CONFIG' AND effective_time <= #{payTime}
      ORDER BY effective_time DESC, version_no DESC
      LIMIT 1
      """)
  PayTimeRateRow findRateEffectiveAt(@Param("kolId") long kolId, @Param("payTime") Instant payTime);

  /** {@code versionNo} is null for records predating versioning; {@code rateText} may be null. */
  record PayTimeRateRow(Integer versionNo, String rateText) {}
}
