package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * KOL row. Nullable editable columns ({@code defaultRate}, {@code remark}) use ALWAYS so clears
 * survive updateById; {@code bdUserId} is NEVER written by entity updates — only {@code
 * KolService.updateBdUser} sets it through a column whitelist. Account columns ({@code
 * passwordHash}, {@code accountStatus}, {@code credentialVersion}, {@code lastLoginTime}) are also
 * NEVER written by entity updates; {@code KolAccountStoreService} owns them with column whitelists.
 */
@TableName("crm_kol")
public class KolDO extends BaseEntity {
  private String kolCode;
  private String name;
  private String email;
  private String country;

  /** JSON array text; see crm_kol.social_accounts. */
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String socialAccounts;

  /** Ownership; changed only by KolService.updateBdUser via LambdaUpdateWrapper. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long bdUserId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal defaultRate;

  private Integer status;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String remark;

  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  /** BCrypt hash; null until the KOL activates the account (task 12a). */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String passwordHash;

  /** 0 unactivated, 1 activated; see crm_kol.account_status. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Integer accountStatus;

  /** Bumped on every password change or reset; tokens bind the version they were issued for. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Integer credentialVersion;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Instant lastLoginTime;

  public String getKolCode() {
    return kolCode;
  }

  public void setKolCode(String value) {
    this.kolCode = value;
  }

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String value) {
    this.email = value;
  }

  public String getCountry() {
    return country;
  }

  public void setCountry(String value) {
    this.country = value;
  }

  public String getSocialAccounts() {
    return socialAccounts;
  }

  public void setSocialAccounts(String value) {
    this.socialAccounts = value;
  }

  public Long getBdUserId() {
    return bdUserId;
  }

  public void setBdUserId(Long value) {
    this.bdUserId = value;
  }

  public BigDecimal getDefaultRate() {
    return defaultRate;
  }

  public void setDefaultRate(BigDecimal value) {
    this.defaultRate = value;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer value) {
    this.status = value;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String value) {
    this.remark = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String value) {
    this.passwordHash = value;
  }

  public Integer getAccountStatus() {
    return accountStatus;
  }

  public void setAccountStatus(Integer value) {
    this.accountStatus = value;
  }

  public Integer getCredentialVersion() {
    return credentialVersion;
  }

  public void setCredentialVersion(Integer value) {
    this.credentialVersion = value;
  }

  public Instant getLastLoginTime() {
    return lastLoginTime;
  }

  public void setLastLoginTime(Instant value) {
    this.lastLoginTime = value;
  }
}
