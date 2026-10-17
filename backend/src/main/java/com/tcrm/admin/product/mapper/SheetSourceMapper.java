package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.SheetSourceDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SheetSourceMapper extends BaseMapper<SheetSourceDO> {
  /** Locks the source row for the rest of the transaction; null when missing in this company. */
  @Select("SELECT id FROM crm_sheet_source WHERE id = #{id} AND is_deleted = FALSE FOR UPDATE")
  Long lockById(@Param("id") Long id);
}
