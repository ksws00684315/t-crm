package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.system.api.AccountNameDTO;
import com.tcrm.admin.system.mapper.SysUserMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountNameServiceTest {
  private final SysUserMapper users = mock(SysUserMapper.class);
  private final AccountNameService service = new AccountNameService(users);

  @Test
  void shouldSkipTheQuery_whenNoIdsAreRequested() {
    assertThat(service.listByIds(List.of())).isEmpty();
    assertThat(service.listByIds(null)).isEmpty();
    verify(users, never()).listNamesByIds(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void shouldReturnDisabledAndDeletedNicknames() {
    when(users.listNamesByIds(List.of(5L))).thenReturn(List.of(new AccountNameDTO(5L, "Test")));
    assertThat(service.listByIds(List.of(5L))).containsExactly(new AccountNameDTO(5L, "Test"));
  }
}
