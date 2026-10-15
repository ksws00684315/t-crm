package com.tcrm.admin.integration.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One ECB reference date of the CNY to USD rate (design §7.5). Platform-level and append-only: no
 * tenant id, update actor, update time or soft delete.
 */
@TableName("crm_fx_rate")
public class FxRateDO {
  @TableId private Long id;
  private LocalDate rateDate;
  private String base;
  private String quote;
  private BigDecimal rate;
  private String source;
  private Instant fetchedTime;
  private Long createBy;
  private Instant createTime;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public LocalDate getRateDate() {
    return rateDate;
  }

  public void setRateDate(LocalDate value) {
    this.rateDate = value;
  }

  public String getBase() {
    return base;
  }

  public void setBase(String value) {
    this.base = value;
  }

  public String getQuote() {
    return quote;
  }

  public void setQuote(String value) {
    this.quote = value;
  }

  public BigDecimal getRate() {
    return rate;
  }

  public void setRate(BigDecimal value) {
    this.rate = value;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String value) {
    this.source = value;
  }

  public Instant getFetchedTime() {
    return fetchedTime;
  }

  public void setFetchedTime(Instant value) {
    this.fetchedTime = value;
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
}
