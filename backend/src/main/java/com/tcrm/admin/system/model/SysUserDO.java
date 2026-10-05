package com.tcrm.admin.system.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

@TableName("sys_user")
public class SysUserDO extends BaseEntity {
  private String username;
  @JsonIgnore private String passwordHash;
  private String nickname;
  private String email;
  private Role role;
  private DataScope dataScope;
  private Integer status;
  private Instant lastLoginTime;

  public String getUsername() {
    return username;
  }

  public void setUsername(String value) {
    this.username = value;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String value) {
    this.passwordHash = value;
  }

  public String getNickname() {
    return nickname;
  }

  public void setNickname(String value) {
    this.nickname = value;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String value) {
    this.email = value;
  }

  public Role getRole() {
    return role;
  }

  public void setRole(Role value) {
    this.role = value;
  }

  public DataScope getDataScope() {
    return dataScope;
  }

  public void setDataScope(DataScope value) {
    this.dataScope = value;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer value) {
    this.status = value;
  }

  public Instant getLastLoginTime() {
    return lastLoginTime;
  }

  public void setLastLoginTime(Instant value) {
    this.lastLoginTime = value;
  }
}
