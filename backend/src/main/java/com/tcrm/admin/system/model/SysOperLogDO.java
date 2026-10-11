package com.tcrm.admin.system.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("sys_oper_log")
public class SysOperLogDO {
  @TableId private Long id;
  private Long userId;
  private String username;
  private String module;
  private String action;
  private String method;
  private String requestUri;
  private String requestParams;
  private String result;
  private String errorMsg;
  private String ip;
  private Long costMs;
  private Instant operTime;

  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long value) {
    this.userId = value;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String value) {
    this.username = value;
  }

  public String getModule() {
    return module;
  }

  public void setModule(String value) {
    this.module = value;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String value) {
    this.action = value;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String value) {
    this.method = value;
  }

  public String getRequestUri() {
    return requestUri;
  }

  public void setRequestUri(String value) {
    this.requestUri = value;
  }

  public String getRequestParams() {
    return requestParams;
  }

  public void setRequestParams(String value) {
    this.requestParams = value;
  }

  public String getResult() {
    return result;
  }

  public void setResult(String value) {
    this.result = value;
  }

  public String getErrorMsg() {
    return errorMsg;
  }

  public void setErrorMsg(String value) {
    this.errorMsg = value;
  }

  public String getIp() {
    return ip;
  }

  public void setIp(String value) {
    this.ip = value;
  }

  public Long getCostMs() {
    return costMs;
  }

  public void setCostMs(Long value) {
    this.costMs = value;
  }

  public Instant getOperTime() {
    return operTime;
  }

  public void setOperTime(Instant value) {
    this.operTime = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }
}
