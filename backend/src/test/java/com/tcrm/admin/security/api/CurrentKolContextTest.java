package com.tcrm.admin.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.session.SaSession;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CurrentKolContextTest {
  private static KolAccountDTO kol(Integer status, Integer accountStatus, boolean tenantEnabled) {
    return new KolAccountDTO(
        7L,
        "KOL0007",
        "Alice",
        "kol@example.com",
        "US",
        "stored-hash",
        status,
        accountStatus,
        0,
        Instant.EPOCH,
        2L,
        "Tenant",
        tenantEnabled);
  }

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void shouldExposeKolIdentity_whenAccountIsUsable() {
    var accounts = mock(KolAccountStore.class);
    var kol = kol(1, 1, true);
    when(accounts.getById(7L)).thenReturn(kol);
    try (var stp = mockStatic(StpKolUtil.class)) {
      stp.when(StpKolUtil::getLoginIdAsLong).thenReturn(7L);
      bindCredentialVersion(stp, 0);
      assertThat(new CurrentKolContext(accounts).kol()).isEqualTo(kol);
    }
  }

  @Test
  void shouldInvalidateSession_whenKolIsMissingDisabledUnactivatedOrCompanyDisabled() {
    var accounts = mock(KolAccountStore.class);
    when(accounts.getById(7L))
        .thenReturn(null)
        .thenReturn(kol(0, 1, true))
        .thenReturn(kol(1, 0, true))
        .thenReturn(kol(1, 1, false));
    try (var stp = mockStatic(StpKolUtil.class)) {
      stp.when(StpKolUtil::getLoginIdAsLong).thenReturn(7L);
      var current = new CurrentKolContext(accounts);
      for (int i = 0; i < 4; i++) {
        assertThatThrownBy(current::kol)
            .isInstanceOf(BusinessException.class)
            .hasMessage(ErrorCode.AUTH_ACCOUNT_DISABLED.message());
      }
      stp.verify(() -> StpKolUtil.kickout(7L), times(4));
    }
  }

  @Test
  void shouldRevokeToken_whenIssuedForAnOlderCredentialVersion() {
    var accounts = mock(KolAccountStore.class);
    when(accounts.getById(7L)).thenReturn(kol(1, 1, true).withCredentialVersion(3));
    try (var stp = mockStatic(StpKolUtil.class)) {
      stp.when(StpKolUtil::getLoginIdAsLong).thenReturn(7L);
      stp.when(StpKolUtil::getTokenValue).thenReturn("old-token");
      bindCredentialVersion(stp, 2);
      assertThatThrownBy(() -> new CurrentKolContext(accounts).kol())
          .isInstanceOf(BusinessException.class)
          .hasMessage(ErrorCode.AUTH_UNAUTHORIZED.message());
      stp.verify(() -> StpKolUtil.logoutByTokenValue("old-token"));
    }
  }

  @Test
  void shouldRevokeToken_whenSessionHasNoCredentialVersion() {
    var accounts = mock(KolAccountStore.class);
    when(accounts.getById(7L)).thenReturn(kol(1, 1, true));
    try (var stp = mockStatic(StpKolUtil.class)) {
      stp.when(StpKolUtil::getLoginIdAsLong).thenReturn(7L);
      stp.when(StpKolUtil::getTokenValue).thenReturn("legacy-token");
      stp.when(StpKolUtil::getTokenSession).thenReturn(mock(SaSession.class));
      assertThatThrownBy(() -> new CurrentKolContext(accounts).kol())
          .isInstanceOf(BusinessException.class)
          .hasMessage(ErrorCode.AUTH_UNAUTHORIZED.message());
      stp.verify(() -> StpKolUtil.logoutByTokenValue("legacy-token"));
    }
  }

  @Test
  void shouldQueryStoreOnce_whenSeveralLookupsHappenInOneRequest() {
    var accounts = mock(KolAccountStore.class);
    when(accounts.getById(7L)).thenReturn(kol(1, 1, true));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try (var stp = mockStatic(StpKolUtil.class)) {
      stp.when(StpKolUtil::getLoginIdAsLong).thenReturn(7L);
      bindCredentialVersion(stp, 0);
      var current = new CurrentKolContext(accounts);
      current.kol();
      current.kol();
      verify(accounts, times(1)).getById(7L);
      stp.verify(StpKolUtil::getTokenSession, times(1));
    }
  }

  @Test
  void shouldQueryStoreEveryTime_whenNoRequestIsBound() {
    var accounts = mock(KolAccountStore.class);
    when(accounts.getById(7L)).thenReturn(kol(1, 1, true));
    try (var stp = mockStatic(StpKolUtil.class)) {
      stp.when(StpKolUtil::getLoginIdAsLong).thenReturn(7L);
      bindCredentialVersion(stp, 0);
      var current = new CurrentKolContext(accounts);
      current.kol();
      current.kol();
      verify(accounts, times(2)).getById(7L);
    }
  }

  private static void bindCredentialVersion(org.mockito.MockedStatic<StpKolUtil> stp, int version) {
    var session = mock(SaSession.class);
    when(session.get(CurrentKolContext.CREDENTIAL_VERSION)).thenReturn(version);
    stp.when(StpKolUtil::getTokenSession).thenReturn(session);
  }
}
