package com.tcrm.admin.commission.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * Payout account row (crm_payout_account, task 15a). The account itself is only stored encrypted
 * ({@code accountCipher}); every list shows {@code accountMasked}. No logical delete: accounts are
 * disabled, never removed.
 */
@TableName("crm_payout_account")
public class PayoutAccountDO {
  @TableId private Long id;

  /** Owning company. Never written by updates. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private Long kolId;

  private PayoutAccountType accountType;

  private String accountCipher;

  private String accountMasked;

  private String holderName;

  /** ISO 4217; PayPal only. */
  private String currency;

  private UsdtNetwork usdtNetwork;

  private Long qrFileId;

  private PayoutAccountStatus status;

  private Boolean isDefault;

  private String rejectReason;

  private Long reviewedBy;

  private Instant reviewedTime;

  private Instant lastSubmittedAt;

  /** Creator: always the KOL that owns the account. */
  @TableField(fill = FieldFill.INSERT)
  private Long createBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createTime;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Long updateBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updateTime;

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

  public PayoutAccountType getAccountType() {
    return accountType;
  }

  public void setAccountType(PayoutAccountType value) {
    this.accountType = value;
  }

  public String getAccountCipher() {
    return accountCipher;
  }

  public void setAccountCipher(String value) {
    this.accountCipher = value;
  }

  public String getAccountMasked() {
    return accountMasked;
  }

  public void setAccountMasked(String value) {
    this.accountMasked = value;
  }

  public String getHolderName() {
    return holderName;
  }

  public void setHolderName(String value) {
    this.holderName = value;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String value) {
    this.currency = value;
  }

  public UsdtNetwork getUsdtNetwork() {
    return usdtNetwork;
  }

  public void setUsdtNetwork(UsdtNetwork value) {
    this.usdtNetwork = value;
  }

  public Long getQrFileId() {
    return qrFileId;
  }

  public void setQrFileId(Long value) {
    this.qrFileId = value;
  }

  public PayoutAccountStatus getStatus() {
    return status;
  }

  public void setStatus(PayoutAccountStatus value) {
    this.status = value;
  }

  public Boolean getIsDefault() {
    return isDefault;
  }

  public void setIsDefault(Boolean value) {
    this.isDefault = value;
  }

  public String getRejectReason() {
    return rejectReason;
  }

  public void setRejectReason(String value) {
    this.rejectReason = value;
  }

  public Long getReviewedBy() {
    return reviewedBy;
  }

  public void setReviewedBy(Long value) {
    this.reviewedBy = value;
  }

  public Instant getReviewedTime() {
    return reviewedTime;
  }

  public void setReviewedTime(Instant value) {
    this.reviewedTime = value;
  }

  public Instant getLastSubmittedAt() {
    return lastSubmittedAt;
  }

  public void setLastSubmittedAt(Instant value) {
    this.lastSubmittedAt = value;
  }

  public Long getCreateBy() {
    return createBy;
  }

  public void setCreateBy(Long value) {
    this.createBy = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }

  public Long getUpdateBy() {
    return updateBy;
  }

  public void setUpdateBy(Long value) {
    this.updateBy = value;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant value) {
    this.updateTime = value;
  }
}
