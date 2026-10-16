package com.tcrm.admin.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.system.model.SysUserDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Accounts. The tenant line ignores {@code sys_user} (ADR-022): company code reaches accounts only
 * through {@link SysUserTenantMapper}.
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUserDO> {
  /**
   * Every row with this login name, including disabled and deleted accounts; the unique index is
   * global.
   */
  @Select("SELECT COUNT(*) FROM sys_user WHERE lower(username) = lower(#{username})")
  long countByUsername(@Param("username") String username);
}
