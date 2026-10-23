package com.tcrm.admin.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.system.api.AccountNameDTO;
import com.tcrm.admin.system.model.SysUserDO;
import java.util.Collection;
import java.util.List;
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

  /**
   * Nicknames for display, including disabled and deleted accounts. {@code sys_user} is not
   * company-scoped and this statement does not filter {@code is_deleted}.
   */
  @Select(
      """
      <script>
      SELECT id, nickname FROM sys_user
      WHERE id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      </script>
      """)
  List<AccountNameDTO> listNamesByIds(@Param("ids") Collection<Long> ids);
}
