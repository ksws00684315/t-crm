package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.ShopDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ShopMapper extends BaseMapper<ShopDO> {
  @Select("SELECT COUNT(*) FROM crm_shop WHERE is_deleted = FALSE")
  long countAlive();

  /**
   * Serializes shop creation for one company until the surrounding transaction ends. The key
   * includes the company id so two companies do not block each other. {@code countAlive} is limited
   * to the current company by the tenant line.
   */
  @Select(
      "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('crm_shop:create:' || #{tenantId}, 0))")
  Integer lockForCreate(@Param("tenantId") long tenantId);

  /**
   * Rows with this Weidian shop id, including deleted ones. Callers must run in system scope: the
   * unique index is global.
   */
  @Select("SELECT COUNT(*) FROM crm_shop WHERE weidian_shop_id = #{weidianShopId}")
  long countByWeidianId(@Param("weidianShopId") String weidianShopId);
}
