package com.tcrm.admin.system.model;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import java.time.Instant;

/** Membership joined with its account: one person as seen inside one company. */
public class MemberRow {
  private Long userId;
  private Long tenantId;
  private Role role;
  private PostTemplate postTemplate;
  private DataScope dataScope;
  private Integer status;
  private Instant lastLoginTime;
  private Instant createTime;
  private String username;
  private String nickname;
  private String email;
  private Integer accountStatus;

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long value) {
    this.userId = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public Role getRole() {
    return role;
  }

  public void setRole(Role value) {
    this.role = value;
  }

  public PostTemplate getPostTemplate() {
    return postTemplate;
  }

  public void setPostTemplate(PostTemplate value) {
    this.postTemplate = value;
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

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String value) {
    this.username = value;
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

  public Integer getAccountStatus() {
    return accountStatus;
  }

  public void setAccountStatus(Integer value) {
    this.accountStatus = value;
  }
}
