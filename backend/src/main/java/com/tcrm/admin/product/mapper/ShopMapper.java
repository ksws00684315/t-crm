package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.ShopDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

  @Select("SELECT COALESCE(MAX(SUBSTRING(shop_code, 6)::INTEGER), 99999) + 1 FROM crm_shop")
  int nextShopCodeNumber();

  @Select("SELECT id FROM crm_shop WHERE id = #{shopId} FOR UPDATE")
  Long lockShop(@Param("shopId") long shopId);

  @Select(
      "SELECT COUNT(*) FROM crm_copy_job_item i JOIN crm_copy_job j ON j.id = i.job_id "
          + "WHERE j.target_shop_id = #{shopId} AND j.status = 'processing' AND i.status = 'pending'")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  int countReservedItems(@Param("shopId") long shopId);

  @Update(
      "UPDATE crm_shop SET current_items = current_items + 1, update_time = #{now} "
          + "WHERE id = #{shopId} AND current_items < max_items")
  int incrementCurrentItems(@Param("shopId") long shopId, @Param("now") java.time.Instant now);

  /**
   * Rows with this Weidian shop id, including deleted ones. Callers must run in system scope: the
   * unique index is global.
   */
  @Select("SELECT COUNT(*) FROM crm_shop WHERE weidian_shop_id = #{weidianShopId}")
  long countByWeidianId(@Param("weidianShopId") String weidianShopId);
}
