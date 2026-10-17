package com.tcrm.admin.integration.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;

/** Company Google service account (ADR-021); the private key is stored only as cipher text. */
@TableName("crm_google_credential")
public class GoogleCredentialDO extends BaseEntity {
  private String clientEmail;
  private String privateKeyId;
  private String privateKeyCipher;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public String getClientEmail() {
    return clientEmail;
  }

  public void setClientEmail(String value) {
    clientEmail = value;
  }

  public String getPrivateKeyId() {
    return privateKeyId;
  }

  public void setPrivateKeyId(String value) {
    privateKeyId = value;
  }

  public String getPrivateKeyCipher() {
    return privateKeyCipher;
  }

  public void setPrivateKeyCipher(String value) {
    privateKeyCipher = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }
}
