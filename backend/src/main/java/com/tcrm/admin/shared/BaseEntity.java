package com.tcrm.admin.shared;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import java.time.Instant;

public abstract class BaseEntity {
  @TableId private Long id;

  @TableField(fill = FieldFill.INSERT)
  private Long createBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createTime;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Long updateBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updateTime;

  @TableLogic
  @TableField("is_deleted")
  private Boolean deleted;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
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

  public Boolean getDeleted() {
    return deleted;
  }

  public void setDeleted(Boolean value) {
    this.deleted = value;
  }
}
