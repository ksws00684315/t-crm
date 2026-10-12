package com.tcrm.admin.integration.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

@TableName("crm_weidian_shop_auth")
public class WeidianShopAuthDO extends BaseEntity {
  private Long shopId;
  private String authMode;
  private String selfAppKey;
  private String selfAppSecretCipher;
  private String openid;
  private String accessTokenCipher;
  private String refreshTokenCipher;
  private Instant accessExpiresAt;
  private Instant refreshExpiresAt;
  private String status;
  private Instant authorizedAt;
  private Long authorizedBy;
  private Instant lastRefreshAt;
  private Instant lastCheckAt;
  private Boolean lastCheckOk;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String lastError;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public Long getShopId() {
    return shopId;
  }

  public void setShopId(Long value) {
    shopId = value;
  }

  public String getAuthMode() {
    return authMode;
  }

  public void setAuthMode(String value) {
    authMode = value;
  }

  public String getSelfAppKey() {
    return selfAppKey;
  }

  public void setSelfAppKey(String value) {
    selfAppKey = value;
  }

  public String getSelfAppSecretCipher() {
    return selfAppSecretCipher;
  }

  public void setSelfAppSecretCipher(String value) {
    selfAppSecretCipher = value;
  }

  public String getOpenid() {
    return openid;
  }

  public void setOpenid(String value) {
    openid = value;
  }

  public String getAccessTokenCipher() {
    return accessTokenCipher;
  }

  public void setAccessTokenCipher(String value) {
    accessTokenCipher = value;
  }

  public String getRefreshTokenCipher() {
    return refreshTokenCipher;
  }

  public void setRefreshTokenCipher(String value) {
    refreshTokenCipher = value;
  }

  public Instant getAccessExpiresAt() {
    return accessExpiresAt;
  }

  public void setAccessExpiresAt(Instant value) {
    accessExpiresAt = value;
  }

  public Instant getRefreshExpiresAt() {
    return refreshExpiresAt;
  }

  public void setRefreshExpiresAt(Instant value) {
    refreshExpiresAt = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    status = value;
  }

  public Instant getAuthorizedAt() {
    return authorizedAt;
  }

  public void setAuthorizedAt(Instant value) {
    authorizedAt = value;
  }

  public Long getAuthorizedBy() {
    return authorizedBy;
  }

  public void setAuthorizedBy(Long value) {
    authorizedBy = value;
  }

  public Instant getLastRefreshAt() {
    return lastRefreshAt;
  }

  public void setLastRefreshAt(Instant value) {
    lastRefreshAt = value;
  }

  public Instant getLastCheckAt() {
    return lastCheckAt;
  }

  public void setLastCheckAt(Instant value) {
    lastCheckAt = value;
  }

  public Boolean getLastCheckOk() {
    return lastCheckOk;
  }

  public void setLastCheckOk(Boolean value) {
    lastCheckOk = value;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String value) {
    lastError = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }
}
