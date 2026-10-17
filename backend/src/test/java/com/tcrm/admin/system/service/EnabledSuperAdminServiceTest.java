package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EnabledSuperAdminServiceTest {
  private final SysUserMapper users = mock(SysUserMapper.class);
  private final EnabledSuperAdminService service = new EnabledSuperAdminService(users);

  @BeforeAll
  static void table() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDO.class);
  }

  @Test
  void shouldListEnabledSuperAdminsInIdOrder() {
    when(users.selectList(any())).thenReturn(List.of(user(1L), user(4L)));
    assertThat(service.listEnabledIds()).containsExactly(1L, 4L);
    @SuppressWarnings("unchecked")
    var wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(users).selectList(wrapper.capture());
    assertThat(wrapper.getValue().getSqlSegment()).contains("ORDER BY");
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper.getValue())
                .getParamNameValuePairs()
                .values())
        .contains(Role.SUPER_ADMIN, 1);
  }

  private static SysUserDO user(long id) {
    SysUserDO row = new SysUserDO();
    row.setId(id);
    return row;
  }
}
