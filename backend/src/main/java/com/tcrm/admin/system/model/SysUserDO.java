package com.tcrm.admin.system.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

/**
 * An account (the person). It belongs to no company; company roles live in {@link SysUserTenantDO}.
 * The tenant line ignores this table (ADR-022).
 */
@TableName("sys_user")
public class SysUserDO extends BaseEntity {
  private String username;
  @JsonIgnore private String passwordHash;
  private String nickname;
  private String email;
  private AccountType role;
  private Integer status;
  private Instant lastLoginTime;
  private Integer credentialVersion;

  /** Company of the last login or switch; only picks the default company at login. */
  private Long lastTenantId;

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

  public AccountType getRole() {
    return role;
  }

  public void setRole(AccountType value) {
    this.role = value;
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

  public Integer getCredentialVersion() {
    return credentialVersion;
  }

  public void setCredentialVersion(Integer value) {
    this.credentialVersion = value;
  }

  public Long getLastTenantId() {
    return lastTenantId;
  }

  public void setLastTenantId(Long value) {
    this.lastTenantId = value;
  }
}
