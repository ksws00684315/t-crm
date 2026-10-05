package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.ShopDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ShopMapper extends BaseMapper<ShopDO> {
  @Select("SELECT COUNT(*) FROM crm_shop WHERE is_deleted = FALSE")
  long countAlive();
}
