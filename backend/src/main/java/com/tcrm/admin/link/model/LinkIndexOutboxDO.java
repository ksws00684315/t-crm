package com.tcrm.admin.link.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One pending Redis link-index refresh (task 06b). Inserted in the business transaction and deleted
 * after the background sync applies it. Retry columns are updated in place; there is no update
 * actor, update time or soft delete.
 */
@TableName("crm_link_index_outbox")
public class LinkIndexOutboxDO {
  @TableId private Long id;
  private LinkIndexScope scope;
  private Long scopeId;
  private Long productId;
  private Integer attempts;
  private Instant nextAttemptAt;
  private String lastError;
  private Instant createTime;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public LinkIndexScope getScope() {
    return scope;
  }

  public void setScope(LinkIndexScope value) {
    this.scope = value;
  }

  public Long getScopeId() {
    return scopeId;
  }

  public void setScopeId(Long value) {
    this.scopeId = value;
  }

  public Long getProductId() {
    return productId;
  }

  public void setProductId(Long value) {
    this.productId = value;
  }

  public Integer getAttempts() {
    return attempts;
  }

  public void setAttempts(Integer value) {
    this.attempts = value;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public void setNextAttemptAt(Instant value) {
    this.nextAttemptAt = value;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String value) {
    this.lastError = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }
}
