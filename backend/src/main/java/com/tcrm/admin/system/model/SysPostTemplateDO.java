package com.tcrm.admin.system.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.security.api.DataScope;
import java.time.Instant;

@TableName(value = "sys_post_template", autoResultMap = true)
public class SysPostTemplateDO {
  @TableId private Long id;
  private Long tenantId;
  private String postTemplate;
  private DataScope dataScope;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String permissions;

  private Long updateBy;
  private Instant updateTime;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    id = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }

  public String getPostTemplate() {
    return postTemplate;
  }

  public void setPostTemplate(String value) {
    postTemplate = value;
  }

  public DataScope getDataScope() {
    return dataScope;
  }

  public void setDataScope(DataScope value) {
    dataScope = value;
  }

  public String getPermissions() {
    return permissions;
  }

  public void setPermissions(String value) {
    permissions = value;
  }

  public Long getUpdateBy() {
    return updateBy;
  }

  public void setUpdateBy(Long value) {
    updateBy = value;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant value) {
    updateTime = value;
  }
}
