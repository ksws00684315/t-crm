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
import com.tcrm.admin.security.api.CompanyDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {
  private static final String CAPTCHA_ID = "6f1c2a3b-0000-4000-8000-000000000001";
  private final AccountStore accounts = mock(AccountStore.class);
  private final BCryptPasswordEncoder passwords = mock(BCryptPasswordEncoder.class);
  private final LoginFailures failures = mock(LoginFailures.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final CaptchaService captchas = mock(CaptchaService.class);
  private final CompanySwitchAudit switchAudit = mock(CompanySwitchAudit.class);
  private final AuthService auth =
      new AuthService(accounts, passwords, failures, current, captchas, switchAudit);

  private AccountDTO account(Integer status) {
    return member(status, 1L, Role.STAFF, true);
  }

  private static AccountDTO member(Integer status, Long tenantId, Role role, boolean usable) {
    return new AccountDTO(
        7L,
        "operator",
        "test-hash",
        "Operator",
        null,
        role,
        DataScope.OWN,
        status,
        null,
        null,
        0,
        tenantId,
        tenantId == null ? null : "Company " + tenantId,
        usable);
  }

  private static AccountDTO platformAdmin() {
    return new AccountDTO(
        7L,
        "operator",
        "test-hash",
        "Operator",
        null,
        Role.PLATFORM_ADMIN,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        null,
        null,
        true);
  }

  private static CompanyDTO company(long id, Role role) {
    return new CompanyDTO(id, "Company " + id, role);
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
    var timed = new AuthService(accounts, passwords, failures, current, captchas, switchAudit);
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
  void shouldRejectLogin_whenNoCompanyIsUsable() {
    // ADR-022: the last company is disabled and there is no other usable company.
    when(accounts.getByUsername("operator")).thenReturn(member(1, 2L, Role.STAFF, false));
    when(accounts.companies(7L)).thenReturn(List.of());
    when(passwords.matches("test-input", "test-hash")).thenReturn(true);
    try (var stp = mockStatic(StpUtil.class)) {
      assertThatThrownBy(
              () -> auth.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8"))
          .hasMessage(ErrorCode.AUTH_ACCOUNT_DISABLED.message());
      stp.verify(() -> StpUtil.login(any()), never());
    }
    verify(passwords).matches("test-input", "test-hash");
    verify(failures).fail("operator", "198.51.100.8");
    verify(failures, never()).clear(any(), any());
    verify(accounts, never()).recordLogin(any(), any(), any());
  }

  @Test
  void shouldEnterLastCompany_whenItIsStillUsable() {
    when(accounts.getByUsername("operator")).thenReturn(member(1, 2L, Role.STAFF, true));
    when(accounts.companies(7L))
        .thenReturn(List.of(company(1L, Role.SUPER_ADMIN), company(2L, Role.STAFF)));
    when(passwords.matches("test-input", "test-hash")).thenReturn(true);
    try (var stp = mockStatic(StpUtil.class)) {
      var session = mock(SaSession.class);
      stp.when(StpUtil::getTokenSession).thenReturn(session);
      var result = auth.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8");
      assertThat(result.user().tenantId()).isEqualTo(2L);
      assertThat(result.companies()).extracting(CompanyDTO::tenantId).containsExactly(1L, 2L);
      verify(session).set(CurrentUserContext.CURRENT_TENANT, 2L);
      verify(accounts).recordLogin(eq(7L), eq(2L), any(Instant.class));
      verify(accounts, never()).getById(any(), any(Long.class));
    }
  }

  @Test
  void shouldEnterFirstCompanyByName_whenLastCompanyIsNoLongerUsable() {
    when(accounts.getByUsername("operator")).thenReturn(member(1, 2L, Role.STAFF, false));
    when(accounts.companies(7L)).thenReturn(List.of(company(3L, Role.SUPER_ADMIN)));
    when(accounts.getById(7L, 3L)).thenReturn(member(1, 3L, Role.SUPER_ADMIN, true));
    when(passwords.matches("test-input", "test-hash")).thenReturn(true);
    try (var stp = mockStatic(StpUtil.class)) {
      var session = mock(SaSession.class);
      stp.when(StpUtil::getTokenSession).thenReturn(session);
      var result = auth.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8");
      assertThat(result.user().tenantId()).isEqualTo(3L);
      assertThat(result.user().role()).isEqualTo(Role.SUPER_ADMIN);
      verify(session).set(CurrentUserContext.CURRENT_TENANT, 3L);
      verify(accounts).recordLogin(eq(7L), eq(3L), any(Instant.class));
    }
  }

  @Test
  void shouldLoginWithoutCompany_whenPlatformAdministrator() {
    when(accounts.getByUsername("operator")).thenReturn(platformAdmin());
    when(passwords.matches("test-input", "test-hash")).thenReturn(true);
    try (var stp = mockStatic(StpUtil.class)) {
      var session = mock(SaSession.class);
      stp.when(StpUtil::getTokenSession).thenReturn(session);
      var result = auth.login("operator", "test-input", CAPTCHA_ID, "AB7K", "198.51.100.8");
      assertThat(result.companies()).isEmpty();
      verify(session, never()).set(eq(CurrentUserContext.CURRENT_TENANT), any());
      verify(accounts, never()).companies(any());
      verify(accounts).recordLogin(eq(7L), eq(null), any(Instant.class));
    }
  }

  @Test
  void shouldChooseLastCompanyOrFirstByName() {
    var companies = List.of(company(5L, Role.STAFF), company(4L, Role.STAFF));
    assertThat(AuthService.chooseCompany(4L, companies)).isEqualTo(4L);
    assertThat(AuthService.chooseCompany(9L, companies)).isEqualTo(5L);
    assertThat(AuthService.chooseCompany(null, companies)).isEqualTo(5L);
    assertThat(AuthService.chooseCompany(4L, List.of())).isNull();
  }

  @Test
  void shouldSwitchTokenAndAuditInTarget_whenCompanyIsOwn() {
    when(current.user())
        .thenReturn(member(1, 1L, Role.SUPER_ADMIN, true), member(1, 2L, Role.STAFF, true));
    when(accounts.companies(7L))
        .thenReturn(List.of(company(1L, Role.SUPER_ADMIN), company(2L, Role.STAFF)));
    var switched = auth.switchCompany(2L);
    assertThat(switched.tenantId()).isEqualTo(2L);
    assertThat(switched.role()).isEqualTo(Role.STAFF);
    var order = inOrder(current, accounts, switchAudit);
    order.verify(current).switchTo(2L);
    order.verify(accounts).recordSwitch(eq(7L), eq(2L), any(Instant.class));
    order.verify(switchAudit).switched(new CompanySwitchAudit.Switched(1L));
  }

  @Test
  void shouldRejectSwitch_whenCompanyIsNotUsable() {
    when(current.user()).thenReturn(member(1, 1L, Role.SUPER_ADMIN, true));
    when(accounts.companies(7L)).thenReturn(List.of(company(1L, Role.SUPER_ADMIN)));
    assertThatThrownBy(() -> auth.switchCompany(2L))
        .hasMessage(ErrorCode.AUTH_COMPANY_NOT_MEMBER.message());
    verify(current, never()).switchTo(any(Long.class));
    verify(accounts, never()).recordSwitch(any(), any(Long.class), any());
    verifyNoInteractions(switchAudit);
  }

  @Test
  void shouldRejectSwitch_whenPlatformAdministrator() {
    when(current.user()).thenReturn(platformAdmin());
    assertThatThrownBy(() -> auth.switchCompany(1L)).hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
    verify(accounts, never()).companies(any());
    verify(current, never()).switchTo(any(Long.class));
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
    verify(accounts, never()).recordLogin(any(), any(), any());
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
    when(accounts.companies(7L)).thenReturn(List.of(company(1L, Role.STAFF)));
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
      verify(session).set(CurrentUserContext.CURRENT_TENANT, 1L);
      verify(accounts).recordLogin(eq(7L), eq(1L), any(Instant.class));
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
