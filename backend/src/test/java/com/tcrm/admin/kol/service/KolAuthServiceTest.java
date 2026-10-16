package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.model.KolAuthTokenDO;
import com.tcrm.admin.kol.service.KolAuthService.LoginResult;
import com.tcrm.admin.kol.service.KolAuthService.SelectionResult;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.security.api.LoginFailureCounter;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;

class KolAuthServiceTest {
  private static final String IP = "198.51.100.8";

  private final KolAccountStore accounts = mock(KolAccountStore.class);
  private final KolAuthTokens tokens = mock(KolAuthTokens.class);
  private final KolMails mails = mock(KolMails.class);
  private final BCryptPasswordEncoder passwords = mock(BCryptPasswordEncoder.class);
  private final LoginFailureCounter failures = mock(LoginFailureCounter.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> values = mock(ValueOperations.class);
  private final ObjectMapper json = new ObjectMapper();

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    lenient().when(redis.opsForValue()).thenReturn(values);
    lenient().when(redis.getExpire(anyString())).thenReturn(60L);
    lenient().when(passwords.encode(any())).thenReturn("timing-hash");
  }

  private KolAuthService service() {
    return new KolAuthService(
        accounts,
        tokens,
        mails,
        passwords,
        failures,
        current,
        audit,
        redis,
        json,
        TestTransactions.template());
  }

  private static KolAccountDTO account(
      long id, long tenantId, Integer status, Integer accountStatus, boolean tenantEnabled) {
    return new KolAccountDTO(
        id,
        "KOL000" + id,
        "Kol " + id,
        "kol@example.com",
        "US",
        Integer.valueOf(1).equals(accountStatus) ? "stored-hash" : null,
        status,
        accountStatus,
        0,
        null,
        tenantId,
        "Tenant " + tenantId,
        tenantEnabled);
  }

  private static KolAccountDTO usable(long id, long tenantId) {
    return account(id, tenantId, 1, 1, true);
  }

  /** The login binds the credential version in the token session. */
  private static void stubSession(org.mockito.MockedStatic<StpKolUtil> stp) {
    stp.when(StpKolUtil::getTokenSession)
        .thenReturn(mock(cn.dev33.satoken.session.SaSession.class));
  }

  @Test
  void shouldLoginDirectly_whenPasswordMatchesOneUsableAccount() {
    var kol = usable(7L, 1L);
    when(accounts.listByEmail("kol@example.com")).thenReturn(List.of(kol));
    when(passwords.matches("secret-pass", "stored-hash")).thenReturn(true);
    try (var stp = org.mockito.Mockito.mockStatic(StpKolUtil.class)) {
      stubSession(stp);
      stp.when(StpKolUtil::getTokenName).thenReturn("X-Kol-Token");
      stp.when(StpKolUtil::getTokenValue).thenReturn("unit-token");
      stp.when(StpKolUtil::getTokenTimeout).thenReturn(604800L);
      var result = service().login(" Kol@Example.com ", "secret-pass", IP);
      assertThat(result).isInstanceOf(LoginResult.class);
      var login = (LoginResult) result;
      assertThat(login.token()).isEqualTo("unit-token");
      assertThat(login.tokenName()).isEqualTo("X-Kol-Token");
      assertThat(login.kol().id()).isEqualTo(7L);
      verify(failures).clear("kol@example.com", IP);
      verify(accounts).updateLastLoginTime(eq(7L), any(Instant.class));
    }
  }

  @Test
  void shouldRejectLogin_whenPasswordMatchesNoAccount() {
    when(accounts.listByEmail("kol@example.com"))
        .thenReturn(List.of(usable(7L, 1L), usable(8L, 2L)));
    when(passwords.matches(anyString(), anyString())).thenReturn(false);
    assertThatThrownBy(() -> service().login("kol@example.com", "wrong-pass", IP))
        .hasMessage(ErrorCode.AUTH_INVALID_CREDENTIALS.message());
    verify(failures).fail("kol@example.com", IP);
  }

  @Test
  void shouldCompareOnceAgainstStartupHash_whenEmailIsUnknownOrUnactivated() {
    var unactivated = account(7L, 1L, 1, 0, true);
    when(accounts.listByEmail("kol@example.com"))
        .thenReturn(List.of())
        .thenReturn(List.of(unactivated));
    var auth = service();
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> auth.login("kol@example.com", "any-input", IP))
          .hasMessage(ErrorCode.AUTH_INVALID_CREDENTIALS.message());
    }
    verify(passwords, times(2)).matches("any-input", "timing-hash");
    verify(failures, times(2)).fail("kol@example.com", IP);
  }

  @Test
  void shouldAnswerDisabled_whenPasswordMatchesOnlyDisabledAccounts() {
    var disabledKol = account(7L, 1L, 0, 1, true);
    var disabledTenant = account(8L, 2L, 1, 1, false);
    when(accounts.listByEmail("kol@example.com")).thenReturn(List.of(disabledKol, disabledTenant));
    when(passwords.matches("secret-pass", "stored-hash")).thenReturn(true);
    var auth = service();
    assertThatThrownBy(() -> auth.login("kol@example.com", "secret-pass", IP))
        .hasMessage(ErrorCode.AUTH_ACCOUNT_DISABLED.message());
    assertThatThrownBy(() -> auth.login("kol@example.com", "secret-pass", IP))
        .hasMessage(ErrorCode.AUTH_ACCOUNT_DISABLED.message());
    // Correct credentials are not a failure and must not lock the account.
    verify(failures, never()).fail(anyString(), anyString());
  }

  @Test
  void shouldReturnSelection_whenPasswordMatchesSeveralUsableAccounts() {
    when(accounts.listByEmail("kol@example.com"))
        .thenReturn(List.of(usable(7L, 1L), usable(8L, 2L)));
    when(passwords.matches("secret-pass", "stored-hash")).thenReturn(true);
    var result = service().login("kol@example.com", "secret-pass", IP);
    assertThat(result).isInstanceOf(SelectionResult.class);
    var selection = (SelectionResult) result;
    assertThat(selection.selectionToken()).hasSize(43);
    assertThat(selection.companies())
        .extracting(KolAuthService.CompanyChoice::tenantId)
        .containsExactly(1L, 2L);
    var valueCaptor = ArgumentCaptor.forClass(String.class);
    verify(values)
        .set(
            eq("kol:auth:selection:" + selection.selectionToken()),
            valueCaptor.capture(),
            any(java.time.Duration.class));
    assertThat(valueCaptor.getValue()).isEqualTo("[\"7\",\"8\"]");
    verify(failures).clear("kol@example.com", IP);
  }

  @Test
  void shouldExchangeSelectionOnce_andOnlyForAVerifiedAccount() {
    when(values.getAndDelete("kol:auth:selection:sel-token")).thenReturn("[\"7\",\"8\"]");
    when(accounts.getById(7L)).thenReturn(usable(7L, 1L));
    when(accounts.getById(8L)).thenReturn(usable(8L, 2L));
    try (var stp = org.mockito.Mockito.mockStatic(StpKolUtil.class)) {
      stubSession(stp);
      stp.when(StpKolUtil::getTokenValue).thenReturn("unit-token");
      stp.when(StpKolUtil::getTokenTimeout).thenReturn(604800L);
      assertThat(service().select("sel-token", 2L).kol().id()).isEqualTo(8L);
      // The credential was consumed: a second call is invalid.
      when(values.getAndDelete("kol:auth:selection:sel-token")).thenReturn(null);
      assertThatThrownBy(() -> service().select("sel-token", 2L))
          .hasMessage(ErrorCode.KOL_LOGIN_SELECTION_INVALID.message());
    }
  }

  @Test
  void shouldRejectSelection_whenTenantWasNotVerifiedByPassword() {
    when(values.getAndDelete("kol:auth:selection:sel-token")).thenReturn("[\"7\"]");
    when(accounts.getById(7L)).thenReturn(usable(7L, 1L));
    assertThatThrownBy(() -> service().select("sel-token", 3L))
        .hasMessage(ErrorCode.KOL_LOGIN_SELECTION_INVALID.message());
  }

  @Test
  void shouldRejectPasswordChange_whenOldPasswordIsWrong() {
    when(current.kol()).thenReturn(usable(7L, 1L));
    when(passwords.matches("wrong-old", "stored-hash")).thenReturn(false);
    assertThatThrownBy(() -> service().changePassword("wrong-old", "new-pass-123", IP))
        .hasMessage(ErrorCode.AUTH_OLD_PASSWORD_INCORRECT.message());
    verify(accounts, never()).updatePassword(anyLong(), anyString());
  }

  @Test
  void shouldReissueLoginAndAudit_whenPasswordChanges() {
    var kol = usable(7L, 1L);
    when(current.kol()).thenReturn(kol);
    when(passwords.matches("old-pass-123", "stored-hash")).thenReturn(true);
    when(passwords.encode("new-pass-123")).thenReturn("new-hash");
    when(accounts.getById(7L)).thenReturn(account(7L, 1L, 1, 1, true).withCredentialVersion(1));
    try (var stp = org.mockito.Mockito.mockStatic(StpKolUtil.class)) {
      stubSession(stp);
      stp.when(StpKolUtil::getTokenValue).thenReturn("fresh-token");
      stp.when(StpKolUtil::getTokenTimeout).thenReturn(604800L);
      var result = service().changePassword("old-pass-123", "new-pass-123", IP);
      assertThat(result.kol().credentialVersion()).isEqualTo(1);
      verify(accounts).updatePassword(7L, "new-hash");
      stp.verify(() -> StpKolUtil.logout(7L));
      stp.verify(() -> StpKolUtil.login(7L));
      verify(audit)
          .recordKolOperation(
              eq(1L),
              eq("kol@example.com"),
              eq("KolAuth"),
              eq("Change password"),
              eq("/kol-api/me/password"),
              eq(IP),
              anyString());
    }
  }

  @Test
  void shouldRejectActivation_whenTokenIsUnknownUsedExpiredOrAccountUnusable() {
    when(tokens.findLive("unknown")).thenReturn(null);
    var row = new KolAuthTokenDO();
    row.setId(11L);
    row.setKolId(7L);
    when(tokens.findLive("known")).thenReturn(row);
    when(accounts.getById(7L))
        .thenReturn(null)
        .thenReturn(account(7L, 1L, 1, 1, true))
        .thenReturn(account(7L, 1L, 0, 0, true))
        .thenReturn(account(7L, 1L, 1, 0, true));
    var auth = service();
    assertThatThrownBy(() -> auth.activate("unknown", "new-pass-123", IP))
        .hasMessage(ErrorCode.KOL_AUTH_TOKEN_INVALID.message());
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> auth.activate("known", "new-pass-123", IP))
          .hasMessage(ErrorCode.KOL_AUTH_TOKEN_INVALID.message());
    }
    verifyNoInteractions(mails);
  }

  @Test
  void shouldActivateAndLogin_whenInviteTokenIsValid() {
    var row = new KolAuthTokenDO();
    row.setId(11L);
    row.setKolId(7L);
    when(tokens.findLive("invite-token")).thenReturn(row);
    when(tokens.markUsed(11L)).thenReturn(1);
    when(passwords.encode("new-pass-123")).thenReturn("new-hash");
    when(accounts.getById(7L))
        .thenReturn(account(7L, 1L, 1, 0, true))
        .thenReturn(account(7L, 1L, 1, 1, true).withCredentialVersion(1));
    try (var stp = org.mockito.Mockito.mockStatic(StpKolUtil.class)) {
      stubSession(stp);
      stp.when(StpKolUtil::getTokenValue).thenReturn("fresh-token");
      stp.when(StpKolUtil::getTokenTimeout).thenReturn(604800L);
      var result = service().activate("invite-token", "new-pass-123", IP);
      assertThat(result.kol().credentialVersion()).isEqualTo(1);
      verify(accounts).activate(7L, "new-hash");
      verify(audit)
          .recordKolOperation(
              anyLong(),
              anyString(),
              eq("KolAuth"),
              eq("Activate"),
              anyString(),
              eq(IP),
              anyString());
    }
  }

  @Test
  void shouldNotActivate_whenConcurrentRequestConsumedTheToken() {
    var row = new KolAuthTokenDO();
    row.setId(11L);
    row.setKolId(7L);
    when(tokens.findLive("invite-token")).thenReturn(row);
    when(tokens.markUsed(11L)).thenReturn(0);
    when(accounts.getById(7L)).thenReturn(account(7L, 1L, 1, 0, true));
    assertThatThrownBy(() -> service().activate("invite-token", "new-pass-123", IP))
        .hasMessage(ErrorCode.KOL_AUTH_TOKEN_INVALID.message());
    verify(accounts, never()).activate(anyLong(), anyString());
  }

  @Test
  void shouldSendNoMail_whenForgotEmailHasNoEligibleAccount() {
    when(accounts.listByEmail("kol@example.com"))
        .thenReturn(List.of())
        .thenReturn(List.of(account(7L, 1L, 1, 0, true)))
        .thenReturn(List.of(account(7L, 1L, 0, 1, true)))
        .thenReturn(List.of(account(7L, 1L, 1, 1, false)));
    var auth = service();
    for (int i = 0; i < 4; i++) {
      auth.forgot("kol@example.com", IP);
    }
    verifyNoInteractions(mails);
    verify(tokens, never()).issue(anyLong(), anyLong(), anyString(), any());
  }

  @Test
  void shouldIssueOneLinkPerCompany_whenForgotEmailMatchesSeveralAccounts() {
    when(accounts.listByEmail("kol@example.com"))
        .thenReturn(List.of(usable(7L, 1L), usable(8L, 2L)));
    when(tokens.issue(eq(1L), eq(7L), eq(KolAuthTokenDO.PURPOSE_RESET), any()))
        .thenReturn("raw-one");
    when(tokens.issue(eq(2L), eq(8L), eq(KolAuthTokenDO.PURPOSE_RESET), any()))
        .thenReturn("raw-two");
    service().forgot("kol@example.com", IP);
    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(mails).sendReset(eq("kol@example.com"), captor.capture());
    assertThat((List<?>) captor.getValue()).hasSize(2);
  }

  @Test
  void shouldStaySilent_whenForgotBudgetIsExhausted() {
    when(redis.opsForValue().increment(anyString())).thenReturn(21L);
    when(accounts.listByEmail("kol@example.com")).thenReturn(List.of(usable(7L, 1L)));
    service().forgot("kol@example.com", IP);
    verifyNoInteractions(mails);

    when(redis.opsForValue().increment(anyString())).thenReturn(1L, 4L);
    service().forgot("kol@example.com", IP);
    verifyNoInteractions(mails);
  }

  @Test
  void shouldResetPasswordAndInvalidateOtherLinks_whenResetTokenIsValid() {
    var row = new KolAuthTokenDO();
    row.setId(11L);
    row.setKolId(7L);
    when(tokens.findLive("reset-token")).thenReturn(row);
    when(tokens.markUsed(11L)).thenReturn(1);
    when(passwords.encode("new-pass-123")).thenReturn("new-hash");
    when(accounts.getById(7L)).thenReturn(usable(7L, 1L));
    try (var stp = org.mockito.Mockito.mockStatic(StpKolUtil.class)) {
      service().reset("reset-token", "new-pass-123", IP);
      verify(accounts).updatePassword(7L, "new-hash");
      verify(tokens).invalidateUnused(7L, KolAuthTokenDO.PURPOSE_RESET);
      stp.verify(() -> StpKolUtil.logout(7L));
      verify(audit)
          .recordKolOperation(
              anyLong(),
              anyString(),
              eq("KolAuth"),
              eq("Reset password"),
              anyString(),
              eq(IP),
              anyString());
    }
  }

  @Test
  void shouldRejectReset_whenTokenIsInvalid() {
    when(tokens.findLive("bad-token")).thenReturn(null);
    assertThatThrownBy(() -> service().reset("bad-token", "new-pass-123", IP))
        .hasMessage(ErrorCode.KOL_AUTH_TOKEN_INVALID.message());
    verify(tokens, never()).markUsed(anyLong());
  }
}
