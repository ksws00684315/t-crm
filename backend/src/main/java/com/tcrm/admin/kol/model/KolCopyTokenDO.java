package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

/** One copy token for a KOL (task 17a, ADR-019). Plaintext is never stored. */
@TableName("crm_kol_copy_token")
public class KolCopyTokenDO extends BaseEntity {
  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_REVOKED = "REVOKED";
  public static final String CHANNEL_WEIDIAN = "WEIDIAN";

  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long kolId;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String tokenHash;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String tokenCipher;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String tokenHint;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String targetChannel;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long targetShopId;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Integer quota;

  private Integer used;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Integer validMinutes;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Instant expiresAt;

  private String status;
  private Instant lastUsedAt;

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    kolId = value;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String value) {
    tokenHash = value;
  }

  public String getTokenCipher() {
    return tokenCipher;
  }

  public void setTokenCipher(String value) {
    tokenCipher = value;
  }

  public String getTokenHint() {
    return tokenHint;
  }

  public void setTokenHint(String value) {
    tokenHint = value;
  }

  public String getTargetChannel() {
    return targetChannel;
  }

  public void setTargetChannel(String value) {
    targetChannel = value;
  }

  public Long getTargetShopId() {
    return targetShopId;
  }

  public void setTargetShopId(Long value) {
    targetShopId = value;
  }

  public Integer getQuota() {
    return quota;
  }

  public void setQuota(Integer value) {
    quota = value;
  }

  public Integer getUsed() {
    return used;
  }

  public void setUsed(Integer value) {
    used = value;
  }

  public Integer getValidMinutes() {
    return validMinutes;
  }

  public void setValidMinutes(Integer value) {
    validMinutes = value;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant value) {
    expiresAt = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    status = value;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(Instant value) {
    lastUsedAt = value;
  }
}
