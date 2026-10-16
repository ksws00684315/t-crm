package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One-time invitation or reset token row (task 12a §4.1). Append-only: the only mutation is marking
 * {@code usedAt}, done by {@code KolAuthTokenStore} through a column whitelist; the raw token never
 * reaches the database, only its SHA-256 hex digest.
 */
@TableName("crm_kol_auth_token")
public class KolAuthTokenDO {
  /** Token purpose (design §4.1): invitation to activate, or password reset. */
  public static final String PURPOSE_INVITE = "INVITE";

  public static final String PURPOSE_RESET = "RESET";

  @TableId private Long id;
  private Long tenantId;
  private Long kolId;
  private String purpose;
  private String tokenHash;
  private Instant expiresAt;
  private Instant usedAt;

  /** Creation time only; updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Instant createTime;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    this.kolId = value;
  }

  public String getPurpose() {
    return purpose;
  }

  public void setPurpose(String value) {
    this.purpose = value;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String value) {
    this.tokenHash = value;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant value) {
    this.expiresAt = value;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public void setUsedAt(Instant value) {
    this.usedAt = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }
}
