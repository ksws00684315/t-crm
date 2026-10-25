package com.tcrm.admin.order.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Paged order lookups for the settlement detail list (task 62, 13.05). Parameters are bound; the
 * BD-scope KOL list uses a foreach. Company isolation is the tenant plugin's job.
 */
@Mapper
public interface OrderSettlementQueryMapper {
  String OWN_SCOPE =
      "<if test='ownKolIds != null'>"
          + " AND EXISTS (SELECT 1 FROM crm_order_item i WHERE i.order_id = o.id"
          + " AND i.is_deleted = FALSE AND i.kol_id IN"
          + " <foreach collection='ownKolIds' item='kid' open='(' separator=',' close=')'>#{kid}</foreach>)"
          + " AND NOT EXISTS (SELECT 1 FROM crm_order_item i2 WHERE i2.order_id = o.id"
          + " AND i2.is_deleted = FALSE AND i2.kol_id IS NOT NULL AND i2.kol_id NOT IN"
          + " <foreach collection='ownKolIds' item='kid' open='(' separator=',' close=')'>#{kid}</foreach>)"
          + "</if>";

  @Select(
      "<script>SELECT o.id FROM crm_order o WHERE o.is_deleted = FALSE"
          + "<if test='orderNo != null and orderNo != \"\"'> AND o.weidian_order_id = #{orderNo}</if>"
          + "<if test='shopId != null'> AND o.shop_id = #{shopId}</if>"
          + OWN_SCOPE
          + " ORDER BY o.pay_time DESC NULLS LAST, o.id DESC"
          + " LIMIT #{limit} OFFSET #{offset}</script>")
  List<Long> pageOrderIds(
      @Param("orderNo") String orderNo,
      @Param("shopId") Long shopId,
      @Param("ownKolIds") Collection<Long> ownKolIds,
      @Param("limit") int limit,
      @Param("offset") long offset);

  @Select(
      "<script>SELECT COUNT(*) FROM crm_order o WHERE o.is_deleted = FALSE"
          + "<if test='orderNo != null and orderNo != \"\"'> AND o.weidian_order_id = #{orderNo}</if>"
          + "<if test='shopId != null'> AND o.shop_id = #{shopId}</if>"
          + OWN_SCOPE
          + "</script>")
  long countOrderIds(
      @Param("orderNo") String orderNo,
      @Param("shopId") Long shopId,
      @Param("ownKolIds") Collection<Long> ownKolIds);
}
