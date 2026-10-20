package com.tcrm.admin.system.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("sys_config")
public class SysConfigDO {
  @TableId private Long id;
  private String configKey;
  private String configValue;
  private Long updateBy;
  private Instant updateTime;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    id = value;
  }

  public String getConfigKey() {
    return configKey;
  }

  public void setConfigKey(String value) {
    configKey = value;
  }

  public String getConfigValue() {
    return configValue;
  }

  public void setConfigValue(String value) {
    configValue = value;
  }

  public Long getUpdateBy() {
    return updateBy;
  }

  public void setUpdateBy(Long value) {
    updateBy = value;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant value) {
    updateTime = value;
  }
}
