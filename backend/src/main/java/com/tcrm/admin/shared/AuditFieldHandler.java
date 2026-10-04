package com.tcrm.admin.shared;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.time.Instant;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

@Component
public class AuditFieldHandler implements MetaObjectHandler {
  private Long actor() {
    try {
      return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public void insertFill(MetaObject meta) {
    strictInsertFill(meta, "createBy", Long.class, actor());
    strictInsertFill(meta, "createTime", Instant.class, Instant.now());
    strictInsertFill(meta, "updateBy", Long.class, actor());
    strictInsertFill(meta, "updateTime", Instant.class, Instant.now());
  }

  @Override
  public void updateFill(MetaObject meta) {
    setFieldValByName("updateBy", actor(), meta);
    setFieldValByName("updateTime", Instant.now(), meta);
  }
}
