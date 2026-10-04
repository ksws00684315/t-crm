package com.tcrm.admin.common;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import java.time.Instant;

public abstract class BaseEntity {
  @TableId public Long id;

  @TableField(fill = FieldFill.INSERT)
  public Long createBy;

  @TableField(fill = FieldFill.INSERT)
  public Instant createTime;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  public Long updateBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  public Instant updateTime;

  @TableLogic @TableField("is_deleted") public Boolean deleted;
}
