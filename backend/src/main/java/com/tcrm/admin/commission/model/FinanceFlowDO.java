package com.tcrm.admin.commission.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One finance flow entry of {@code crm_finance_flow} (task 159, 财务流转口径规则 task 158): an event
 * posting to an income-side or commission-side subject. Append-only with source-key idempotency;
 * commission balances and states stay authoritative in {@code crm_commission_wallet} / {@code
 * crm_commission_item_state}, this table only records the subject flows for display and audit.
 */
@TableName("crm_finance_flow")
public class FinanceFlowDO extends BaseEntity {

  private Long tenantId;

  private Long orderId;

  private Long orderItemId;

  private Long kolId;

  /** Subject code; values documented on the column comment. */
  private String subject;

  /** Signed posting amount: positive increases the subject, negative is an offsetting posting. */
  private BigDecimal amountCny;

  /** Business fact time of the event, never a read or sync time. */
  private Instant occurredAt;

  private String sourceType;

  private Long sourceId;

  private String sourceKey;

  private String flowNo;

  private String evidence;

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }

  public Long getOrderId() {
    return orderId;
  }

  public void setOrderId(Long value) {
    orderId = value;
  }

  public Long getOrderItemId() {
    return orderItemId;
  }

  public void setOrderItemId(Long value) {
    orderItemId = value;
  }

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    kolId = value;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String value) {
    subject = value;
  }

  public BigDecimal getAmountCny() {
    return amountCny;
  }

  public void setAmountCny(BigDecimal value) {
    amountCny = value;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(Instant value) {
    occurredAt = value;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String value) {
    sourceType = value;
  }

  public Long getSourceId() {
    return sourceId;
  }

  public void setSourceId(Long value) {
    sourceId = value;
  }

  public String getSourceKey() {
    return sourceKey;
  }

  public void setSourceKey(String value) {
    sourceKey = value;
  }

  public String getFlowNo() {
    return flowNo;
  }

  public void setFlowNo(String value) {
    flowNo = value;
  }

  public String getEvidence() {
    return evidence;
  }

  public void setEvidence(String value) {
    evidence = value;
  }
}
