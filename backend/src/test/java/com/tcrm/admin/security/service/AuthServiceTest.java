package com.tcrm.admin.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {
  private static final String CAPTCHA_ID = "6f1c2a3b-0000-4000-8000-000000000001";
  private final AccountStore accounts = mock(AccountStore.class);
  private final BCryptPasswordEncoder passwords = mock(BCryptPasswordEncoder.class);
  private final LoginFailures failures = mock(LoginFailures.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final CaptchaService captchas = mock(CaptchaService.class);
  private final AuthService auth =
      new AuthService(accounts, passwords, failures, current, captchas);

  private AccountDTO account(Integer status) {
    return new AccountDTO(
        7L,
        "operator",
        "test-hash",
        "Operator",
        null,
        Role.STAFF,
        DataScope.OWN,
        status,
        null,
        null,
        0,
        null,
        null,
        true);
  }

  @Test
  void shouldRejectCredentials_whenUnknownOrDisabled() {
    when(accounts.getByUsername("operator")).thenReturn(null, account(0), account(null));
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(
              () -> auth.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8"))
          .isInstanceOf(BusinessException.class)
          .hasMessage(ErrorCode.AUTH_INVALID_CREDENTIALS.message());
    }
    verify(failures, times(3)).fail("operator", "198.51.100.8");
  }

  @Test
  void shouldVerifyPasswordHash_whenAccountIsUnknownOrDisabled() {
    when(passwords.encode(any())).thenReturn("timing-hash");
    var timed = new AuthService(accounts, passwords, failures, current, captchas);
    when(accounts.getByUsername("operator")).thenReturn(null, account(0));
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(
              () -> timed.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8"))
          .isInstanceOf(BusinessException.class);
    }
    // One comparison per attempt: unknown users use the startup-generated hash, disabled users
    // their own.
    verify(passwords).matches("test-input", "timing-hash");
    verify(passwords).matches("test-input", "test-hash");
  }

  @Test
  void shouldRejectLogin_whenCompanyIsDisabled() {
    when(accounts.getByUsername("operator"))
        .thenReturn(
            new AccountDTO(
                7L,
                "operator",
                "test-hash",
                "Operator",
                null,
                Role.STAFF,
                DataScope.OWN,
                1,
                null,
                null,
                0,
                2L,
                "Stopped",
                false));
    when(passwords.matches("test-input", "test-hash")).thenReturn(true);
    assertThatThrownBy(
            () -> auth.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8"))
        .hasMessage(ErrorCode.AUTH_INVALID_CREDENTIALS.message());
    verify(passwords).matches("test-input", "test-hash");
    verify(failures).fail("operator", "198.51.100.8");
    verify(accounts, never()).updateLastLoginTime(any(), any());
  }

  @Test
  void shouldRejectDisabledAccount_whenPasswordIsCorrect() {
    when(accounts.getByUsername("operator")).thenReturn(account(0));
    when(passwords.matches("test-input", "test-hash")).thenReturn(true);
    assertThatThrownBy(
            () -> auth.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8"))
        .hasMessage(ErrorCode.AUTH_INVALID_CREDENTIALS.message());
    verify(failures).fail("operator", "198.51.100.8");
  }

  @Test
  void shouldCountFailure_whenPasswordIsWrong() {
    when(accounts.getByUsername("operator")).thenReturn(account(1));
    assertThatThrownBy(
            () -> auth.login("operator", "incorrect", CAPTCHA_ID, "AB7K", "198.51.100.8"))
        .isInstanceOf(BusinessException.class);
    verify(failures).fail("operator", "198.51.100.8");
    verify(accounts, never()).updateLastLoginTime(any(), any());
  }

  @Test
  void shouldAvoidAuthentication_whenAccountIsLocked() {
    doThrow(new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED))
        .when(failures)
        .check("operator", "198.51.100.8");
    assertThatThrownBy(
            () -> auth.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8"))
        .hasMessage(ErrorCode.AUTH_ACCOUNT_LOCKED.message());
    verifyNoInteractions(accounts);
    verify(passwords, never()).matches(any(), any());
  }

  @Test
  void shouldCreateSessionAndClearFailures_whenLoginSucceeds() {
    var account = account(1);
    when(accounts.getByUsername("operator")).thenReturn(account);
    when(passwords.matches("test-input", "test-hash")).thenReturn(true);
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getTokenName).thenReturn("X-Admin-Token");
      stp.when(StpUtil::getTokenValue).thenReturn("unit-token");
      stp.when(StpUtil::getTokenTimeout).thenReturn(28800L);
      var session = mock(SaSession.class);
      stp.when(StpUtil::getTokenSession).thenReturn(session);
      var result = auth.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8");
      assertThat(result.user().username()).isEqualTo(account.username());
      assertThat(result.user().lastLoginTime())
          .isBetween(Instant.now().minusSeconds(5), Instant.now());
      assertThat(result.token()).isEqualTo("unit-token");
      assertThat(result.tokenName()).isEqualTo("X-Admin-Token");
      assertThat(result.expiresIn()).isEqualTo(28800);
      stp.verify(() -> StpUtil.login(7L));
      verify(session).set(CurrentUserContext.CREDENTIAL_VERSION, account.credentialVersion());
      verify(failures).clear("operator", "198.51.100.8");
      verify(accounts).updateLastLoginTime(eq(7L), any(Instant.class));
    }
  }

  @Test
  void shouldRejectPasswordChange_whenOldPasswordIsWrong() {
    when(current.user()).thenReturn(account(1));
    assertThatThrownBy(() -> auth.password("incorrect", "new-test-input"))
        .hasMessage(ErrorCode.AUTH_OLD_PASSWORD_INCORRECT.message());
    verify(accounts, never()).updatePassword(any(), any());
  }

  @Test
  void shouldInvalidateAllSessions_whenPasswordChanges() {
    when(current.user()).thenReturn(account(1));
    when(passwords.matches("old-test-input", "test-hash")).thenReturn(true);
    when(passwords.encode("new-test-input")).thenReturn("new-test-hash");
    try (var stp = mockStatic(StpUtil.class)) {
      auth.password("old-test-input", "new-test-input");
      verify(accounts).updatePassword(7L, "new-test-hash");
      stp.verify(() -> StpUtil.logout(7L));
    }
  }

  @Test
  void shouldLogoutCurrentSession_whenLogoutRequested() {
    try (var stp = mockStatic(StpUtil.class)) {
      auth.logout();
      stp.verify(StpUtil::logout);
    }
  }

  @Test
  void shouldVerifyCaptchaBeforeLockCheckAndPassword_whenLoggingIn() {
    when(accounts.getByUsername("operator")).thenReturn(null);
    when(passwords.matches(any(), any())).thenReturn(false);
    assertThatThrownBy(
            () -> auth.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8"))
        .isInstanceOf(BusinessException.class);
    var order = inOrder(captchas, failures, passwords);
    order.verify(captchas).verify(CAPTCHA_ID, "AB7K");
    order.verify(failures).check("operator", "198.51.100.8");
    order.verify(passwords).matches(any(), any());
  }

  @Test
  void shouldNotCountFailureOrCheckPassword_whenCaptchaIsInvalid() {
    doThrow(new BusinessException(ErrorCode.AUTH_CAPTCHA_INVALID))
        .when(captchas)
        .verify(any(), any());
    assertThatThrownBy(
            () -> auth.login("operator", "test-input", CAPTCHA_ID, "XXXX", "198.51.100.8"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.AUTH_CAPTCHA_INVALID.message());
    verifyNoInteractions(failures, accounts);
    verify(passwords, never()).matches(any(), any());
  }
}
