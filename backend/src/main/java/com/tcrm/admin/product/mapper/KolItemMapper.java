package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.KolItemDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KolItemMapper extends BaseMapper<KolItemDO> {
  /** Current mapping that occupies this exclusive item, or null. */
  @Select(
      """
      SELECT * FROM crm_kol_item
      WHERE shop_id = #{shopId} AND weidian_item_id = #{weidianItemId} AND valid_to IS NULL
      """)
  KolItemDO findCurrentByShopItem(
      @Param("shopId") Long shopId, @Param("weidianItemId") String weidianItemId);

  /** Current mapping for this kol + original product, or null. */
  @Select(
      """
      SELECT * FROM crm_kol_item
      WHERE kol_id = #{kolId} AND product_id = #{productId} AND valid_to IS NULL
      """)
  KolItemDO findCurrentByKolProduct(@Param("kolId") Long kolId, @Param("productId") Long productId);

  /** True when any current mapping uses this shop + weidian item as an exclusive item. */
  @Select(
      """
      SELECT EXISTS (
        SELECT 1 FROM crm_kol_item
        WHERE shop_id = #{shopId} AND weidian_item_id = #{weidianItemId} AND valid_to IS NULL
      )
      """)
  boolean existsCurrentShopItem(
      @Param("shopId") Long shopId, @Param("weidianItemId") String weidianItemId);
}
