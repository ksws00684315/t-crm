package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnabledSuperAdminServiceTest {
  private final SysUserTenantMapper members = mock(SysUserTenantMapper.class);
  private final EnabledSuperAdminService service = new EnabledSuperAdminService(members);

  @Test
  void shouldListEnabledSuperAdminsOfTheCurrentCompanyOnly() {
    when(members.listEnabledSuperAdminIds(7L)).thenReturn(List.of(1L, 4L));
    TenantScope.runCompany(7L, () -> assertThat(service.listEnabledIds()).containsExactly(1L, 4L));
    verify(members).listEnabledSuperAdminIds(7L);
  }

  @Test
  void shouldRefuseToList_withoutACompanyScope() {
    assertThatThrownBy(service::listEnabledIds).isInstanceOf(IllegalStateException.class);
    TenantScope.runSystem(
        "unit test",
        () ->
            assertThatThrownBy(service::listEnabledIds).isInstanceOf(IllegalStateException.class));
    verify(members, never()).listEnabledSuperAdminIds(any());
  }
}
