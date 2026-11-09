package com.tcrm.admin.kollibrary.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One published content of a creator with its result (crm_library_kol_content, task 143 §3.1). The
 * manual fields of requirement 1.2.3 only: the estimated commission, total cost and actual ROI are
 * calculated by {@code LibraryMetrics} and never stored, so a rule change cannot leave stale
 * values.
 */
@TableName("crm_library_kol_content")
public class LibraryKolContentDO extends BaseEntity {
  /** Set on insert only; a row never changes its company. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private Long kolId;

  private String publishUrl;

  private LocalDate publishDate;

  private LibraryContentForm contentForm;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long clicks;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long orders;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal gmv;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LibrarySampleType sampleType;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal sampleCost;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal slotFee;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal commissionRate;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String nextAction;

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

  public String getPublishUrl() {
    return publishUrl;
  }

  public void setPublishUrl(String value) {
    this.publishUrl = value;
  }

  public LocalDate getPublishDate() {
    return publishDate;
  }

  public void setPublishDate(LocalDate value) {
    this.publishDate = value;
  }

  public LibraryContentForm getContentForm() {
    return contentForm;
  }

  public void setContentForm(LibraryContentForm value) {
    this.contentForm = value;
  }

  public Long getClicks() {
    return clicks;
  }

  public void setClicks(Long value) {
    this.clicks = value;
  }

  public Long getOrders() {
    return orders;
  }

  public void setOrders(Long value) {
    this.orders = value;
  }

  public BigDecimal getGmv() {
    return gmv;
  }

  public void setGmv(BigDecimal value) {
    this.gmv = value;
  }

  public LibrarySampleType getSampleType() {
    return sampleType;
  }

  public void setSampleType(LibrarySampleType value) {
    this.sampleType = value;
  }

  public BigDecimal getSampleCost() {
    return sampleCost;
  }

  public void setSampleCost(BigDecimal value) {
    this.sampleCost = value;
  }

  public BigDecimal getSlotFee() {
    return slotFee;
  }

  public void setSlotFee(BigDecimal value) {
    this.slotFee = value;
  }

  public BigDecimal getCommissionRate() {
    return commissionRate;
  }

  public void setCommissionRate(BigDecimal value) {
    this.commissionRate = value;
  }

  public String getNextAction() {
    return nextAction;
  }

  public void setNextAction(String value) {
    this.nextAction = value;
  }
}
