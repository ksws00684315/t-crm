package com.tcrm.admin.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.product.model.ShopDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ShopMapper extends BaseMapper<ShopDO> {
  @Select("SELECT COUNT(*) FROM crm_shop WHERE is_deleted = FALSE")
  long countAlive();

  /**
   * Serializes shop creation until the surrounding transaction ends (PostgreSQL transaction-level
   * advisory lock; the key is fixed to this purpose).
   */
  @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtext('crm_shop:create'))")
  Integer lockForCreate();
}
