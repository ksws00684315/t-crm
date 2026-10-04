package com.tcrm.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tcrm.admin.common.BusinessException;
import com.tcrm.admin.security.*;
import com.tcrm.admin.system.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {
  @Test
  void disabledAndUnknownAccountsFailWithoutCreatingSession() {
    var users = mock(SysUserMapper.class);
    var passwords = mock(BCryptPasswordEncoder.class);
    var failures = mock(LoginFailures.class);
    var auth = new AuthService(users, passwords, failures, mock(CurrentUserContext.class));
    var disabled = new SysUser();
    disabled.status = 0;
    when(users.selectOne(any())).thenReturn(disabled, (SysUser) null);
    assertEquals(
        401,
        assertThrows(BusinessException.class, () -> auth.login("disabled", "test-input"))
            .getCode());
    assertEquals(
        401,
        assertThrows(BusinessException.class, () -> auth.login("unknown", "test-input")).getCode());
    verify(failures).fail("disabled");
    verify(failures).fail("unknown");
    verifyNoInteractions(passwords);
  }

  @Test
  void wrongPasswordCountsFailure() {
    var users = mock(SysUserMapper.class);
    var passwords = mock(BCryptPasswordEncoder.class);
    var failures = mock(LoginFailures.class);
    var user = new SysUser();
    user.status = 1;
    user.passwordHash = "test-hash";
    when(users.selectOne(any())).thenReturn(user);
    var auth = new AuthService(users, passwords, failures, mock(CurrentUserContext.class));
    assertThrows(BusinessException.class, () -> auth.login("operator", "incorrect"));
    verify(failures).fail("operator");
    verify(users, never()).updateById(any(SysUser.class));
  }

  @Test
  void missingBootstrapCredentialsNeverCreateDefaultAccount() {
    var users = mock(SysUserMapper.class);
    when(users.selectCount(any())).thenReturn(0L);
    new BootstrapAdmin(users, new BCryptPasswordEncoder(4), "", "").run(null);
    verify(users, never()).insert(any(SysUser.class));
  }
}
