package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.KolItemLinkRow;
import java.util.List;
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

  /**
   * Current mappings joined with their undeleted product and shop (task 06 link index). Every query
   * below starts from an index on crm_kol_item: kol → uk_crm_kol_item_kol_product, product →
   * idx_crm_kol_item_product.
   */
  String LINK_COLUMNS =
      """
      SELECT ki.id AS kol_item_id, ki.kol_id, ki.product_id, p.product_code,
             p.name AS product_name, ki.shop_id, ki.weidian_item_id,
             p.status AS product_status, s.status AS shop_status
      FROM crm_kol_item ki
      JOIN crm_product p ON p.id = ki.product_id AND p.is_deleted = FALSE
      JOIN crm_shop s ON s.id = ki.shop_id AND s.is_deleted = FALSE
      """;

  @Select(LINK_COLUMNS + " WHERE ki.kol_id = #{kolId} AND ki.valid_to IS NULL")
  List<KolItemLinkRow> listCurrentLinksByKol(@Param("kolId") Long kolId);

  @Select(
      LINK_COLUMNS
          + " WHERE ki.kol_id = #{kolId} AND ki.shop_id = #{shopId} AND ki.valid_to IS NULL")
  List<KolItemLinkRow> listCurrentLinksByKolAndShop(
      @Param("kolId") Long kolId, @Param("shopId") Long shopId);

  @Select(LINK_COLUMNS + " WHERE ki.product_id = #{productId} AND ki.valid_to IS NULL")
  List<KolItemLinkRow> listCurrentLinksByProduct(@Param("productId") Long productId);

  @Select(
      LINK_COLUMNS
          + " WHERE ki.kol_id = #{kolId} AND ki.product_id = #{productId} AND ki.valid_to IS NULL")
  KolItemLinkRow getCurrentLink(@Param("kolId") Long kolId, @Param("productId") Long productId);

  /** Link list page of one KOL ordered by product code; productCode null means all products. */
  @Select(
      "<script>"
          + LINK_COLUMNS
          + " WHERE ki.kol_id = #{kolId} AND ki.valid_to IS NULL"
          + "<if test='productCode != null'> AND p.product_code = #{productCode}</if>"
          + " ORDER BY p.product_code ASC, ki.id ASC"
          + "</script>")
  IPage<KolItemLinkRow> pageCurrentLinksByKol(
      Page<KolItemLinkRow> page,
      @Param("kolId") Long kolId,
      @Param("productCode") String productCode);
}
