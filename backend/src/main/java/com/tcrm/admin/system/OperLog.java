package com.tcrm.admin.system;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("sys_oper_log")
public class OperLog {
  @TableId public Long id;
  public Long userId;
  public String username;
  public String module;
  public String action;
  public String method;
  public String requestUri;
  public String requestParams;
  public String result;
  public String errorMsg;
  public String ip;
  public Long costMs;
  public Instant operTime;
}
