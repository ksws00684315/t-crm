package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.mapper.SysTenantMapper;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import com.tcrm.admin.system.model.AccountType;
import com.tcrm.admin.system.model.SysUserDO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PlatformServiceTest {
  private final SysTenantMapper tenants = mock(SysTenantMapper.class);
  private final SysUserMapper users = mock(SysUserMapper.class);
  private final SysUserTenantMapper members = mock(SysUserTenantMapper.class);
  private final UserPermissionService permissions = mock(UserPermissionService.class);
  private final PostTemplateService postTemplates = mock(PostTemplateService.class);
  private final BCryptPasswordEncoder passwords = mock(BCryptPasswordEncoder.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final PlatformService service =
      new PlatformService(tenants, users, members, permissions, postTemplates, passwords, current);

  @BeforeAll
  static void tables() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserDO.class);
  }

  @Test
  void shouldReturnTheEmail_andAnswerMissingForPlatformAdministrators() {
    when(users.selectOne(any())).thenReturn(account(5L, AccountType.MEMBER, "ada@example.com"));
    assertThat(service.findUser("ada").email()).isEqualTo("ada@example.com");

    when(users.selectOne(any())).thenReturn(account(1L, AccountType.PLATFORM_ADMIN, null));
    assertThat(service.findUser("bootstrap")).isNull();
  }

  @Test
  void shouldKeepTheEmail_whenOnlyTheNameIsChanged() {
    // The form sends back the e-mail it showed; a name-only change must not clear it.
    when(users.selectById(5L)).thenReturn(account(5L, AccountType.MEMBER, "ada@example.com"));
    service.updateUser(5L, "Ada Lovelace", "ada@example.com");
    assertThat(updatedValues()).contains("Ada Lovelace", "ada@example.com");
  }

  @Test
  void shouldAnswerNotFound_forPlatformAdministratorAccounts() {
    when(users.selectById(1L)).thenReturn(account(1L, AccountType.PLATFORM_ADMIN, null));

    assertNotFound(() -> service.updateUser(1L, "Renamed", null));
    assertNotFound(() -> service.resetPassword(1L, "another-password"));
    assertNotFound(() -> service.updateUserStatus(1L, 1));
    verify(users, never()).update(any(), any());
    verify(passwords, never()).encode(any());
  }

  private static void assertNotFound(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  /** Values of the single update statement the service issued. */
  @SuppressWarnings("unchecked")
  private List<Object> updatedValues() {
    var wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(users).update(any(), wrapper.capture());
    return List.copyOf(
        ((AbstractWrapper<?, ?, ?>) wrapper.getValue()).getParamNameValuePairs().values());
  }

  private static SysUserDO account(long id, AccountType role, String email) {
    SysUserDO row = new SysUserDO();
    row.setId(id);
    row.setUsername("user" + id);
    row.setNickname("Nick" + id);
    row.setEmail(email);
    row.setRole(role);
    row.setStatus(1);
    row.setDeleted(false);
    return row;
  }
}
