package com.tcrm.admin.system;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tcrm.admin.common.BaseEntity;
import java.time.Instant;

@TableName("sys_user")
public class SysUser extends BaseEntity {
  public String username;
  @JsonIgnore public String passwordHash;
  public String nickname;
  public String email;
  public Role role;
  public DataScope dataScope;
  public Integer status;
  public Instant lastLoginTime;
}
