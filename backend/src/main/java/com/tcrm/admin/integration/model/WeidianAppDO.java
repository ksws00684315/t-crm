package com.tcrm.admin.integration.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;

@TableName("crm_weidian_app")
public class WeidianAppDO extends BaseEntity {
  private String appKey;
  private String appSecretCipher;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public String getAppKey() {
    return appKey;
  }

  public void setAppKey(String value) {
    appKey = value;
  }

  public String getAppSecretCipher() {
    return appSecretCipher;
  }

  public void setAppSecretCipher(String value) {
    appSecretCipher = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }
}
