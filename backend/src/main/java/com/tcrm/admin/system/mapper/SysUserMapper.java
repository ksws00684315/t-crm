package com.tcrm.admin.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUserDO> {
  /**
   * Locks the enabled super administrators until the surrounding transaction ends and returns their
   * IDs; concurrent demotions or disables of super administrators are serialized by this lock.
   */
  @Select(
      "SELECT id FROM sys_user WHERE role = 'SUPER_ADMIN' AND status = 1 AND is_deleted = FALSE"
          + " ORDER BY id FOR UPDATE")
  List<Long> lockEnabledSuperAdmins();

  /**
   * Every row with this login name, including disabled and deleted accounts. Callers must run in
   * system scope: the unique index is global, and a company filter would hide other companies.
   */
  @Select("SELECT COUNT(*) FROM sys_user WHERE lower(username) = lower(#{username})")
  long countByUsername(@Param("username") String username);
}
