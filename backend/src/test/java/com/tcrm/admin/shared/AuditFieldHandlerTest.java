package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.system.model.SysUserDO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

class AuditFieldHandlerTest {
  @Test
  void shouldFillUtcTimestampsAndActor_whenAuthenticated() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDO.class);
    var user = new SysUserDO();
    var meta = SystemMetaObject.forObject(user);
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::isLogin).thenReturn(true);
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      new AuditFieldHandler().insertFill(meta);
      assertThat(user.getCreateBy()).isEqualTo(7L);
      assertThat(user.getCreateTime()).isNotNull();
      new AuditFieldHandler().updateFill(meta);
      assertThat(user.getUpdateBy()).isEqualTo(7L);
    }
  }

  @Test
  void shouldFillSystemTimestamps_whenLoginStateIsUnavailable() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDO.class);
    var user = new SysUserDO();
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::isLogin).thenThrow(new IllegalStateException());
      new AuditFieldHandler().insertFill(SystemMetaObject.forObject(user));
      assertThat(user.getCreateBy()).isNull();
      assertThat(user.getCreateTime()).isNotNull();
    }
  }

  @Test
  void shouldLeaveActorEmpty_whenAnonymous() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDO.class);
    var user = new SysUserDO();
    try (var stp = mockStatic(StpUtil.class)) {
      new AuditFieldHandler().insertFill(SystemMetaObject.forObject(user));
      assertThat(user.getCreateBy()).isNull();
    }
  }
}
