package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.math.BigDecimal;

/**
 * KOL row. Nullable editable columns ({@code defaultRate}, {@code remark}) use ALWAYS so clears
 * survive updateById; {@code bdUserId} is NEVER written by entity updates — only {@code
 * KolService.updateBdUser} sets it through a column whitelist.
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
}
