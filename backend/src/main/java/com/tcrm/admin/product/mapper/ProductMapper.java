package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.PromoProductRow;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProductMapper extends BaseMapper<ProductDO> {
  @Select("SELECT nextval('crm_product_code_seq')")
  long nextCodeNumber();

  /** True when the code is taken by any row, including logically deleted ones (never reused). */
  @Select("SELECT EXISTS (SELECT 1 FROM crm_product WHERE product_code = #{code})")
  boolean existsCode(@Param("code") String code);

  /**
   * True when an undeleted original product already uses this shop + weidian item id (exclusive
   * items must not collide with original products).
   */
  @Select(
      """
      SELECT EXISTS (
        SELECT 1 FROM crm_product
        WHERE shop_id = #{shopId} AND weidian_item_id = #{weidianItemId} AND is_deleted = FALSE
      )
      """)
  boolean existsAliveShopItem(
      @Param("shopId") Long shopId, @Param("weidianItemId") String weidianItemId);

  /** Original products of these shop items, keyed by {@code weidianItemId} (task 26). */
  @Select(
      "<script>"
          + "SELECT weidian_item_id, id AS product_id, product_code FROM crm_product"
          + " WHERE shop_id = #{shopId} AND is_deleted = FALSE AND weidian_item_id IN"
          + " <foreach collection='itemIds' item='itemId' open='(' separator=',' close=')'>"
          + "#{itemId}"
          + "</foreach>"
          + "</script>")
  List<PromoProductRow> listByShopItems(
      @Param("shopId") Long shopId, @Param("itemIds") Collection<String> itemIds);

  /**
   * Serializes original-product and exclusive-item writes for one shop item until the surrounding
   * transaction ends. The key comes from {@code ProductRules.shopItemLockKey}.
   */
  @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
  Integer lockShopItem(@Param("key") String key);
}
