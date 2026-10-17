package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.model.SheetMissingRow;
import com.tcrm.admin.product.model.SheetSourceItemRow;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SheetSourceItemMapper {
  /**
   * Records the products seen by one read (at most 1,000 rows per call). The rows carry the company
   * explicitly, so the tenant plugin leaves the statement alone.
   */
  @Insert(
      """
      <script>
      INSERT INTO crm_sheet_source_item (id, tenant_id, source_id, shop_id, weidian_item_id,
        sheet_row, last_read_no, create_time, update_time)
      VALUES
      <foreach collection="rows" item="r" separator=",">
        (#{r.id}, #{r.tenantId}, #{r.sourceId}, #{r.shopId}, #{r.weidianItemId}, #{r.sheetRow},
         #{r.lastReadNo}, NOW(), NOW())
      </foreach>
      ON CONFLICT (source_id, shop_id, weidian_item_id) DO UPDATE
        SET sheet_row = EXCLUDED.sheet_row, last_read_no = EXCLUDED.last_read_no,
            update_time = EXCLUDED.update_time
      </script>
      """)
  int upsert(@Param("rows") List<SheetSourceItemRow> rows);

  /**
   * Original products seen in earlier reads of the source but not in read {@code readNo}; items
   * whose product no longer exists are left out.
   */
  @Select(
      """
      SELECT p.id AS product_id, p.product_code, p.name AS product_name,
             p.status AS product_status, i.shop_id, i.weidian_item_id, i.sheet_row,
             i.update_time AS last_seen_time
      FROM crm_sheet_source_item i
      JOIN crm_product p ON p.shop_id = i.shop_id AND p.weidian_item_id = i.weidian_item_id
        AND p.is_deleted = FALSE
      WHERE i.source_id = #{sourceId} AND i.last_read_no < #{readNo}
      ORDER BY i.update_time DESC, i.id DESC
      """)
  Page<SheetMissingRow> pageMissing(
      Page<SheetMissingRow> page, @Param("sourceId") Long sourceId, @Param("readNo") int readNo);
}
