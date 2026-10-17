package com.tcrm.admin.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CurrentUserContextTest {
  @Test
  void shouldExposeAccountIdentity_whenAccountIsEnabled() {
    var accounts = mock(AccountStore.class);
    var user =
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
            null,
            null,
            true);
    when(accounts.getById(7L)).thenReturn(user);
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      bindCredentialVersion(stp, 0);
      var current = new CurrentUserContext(accounts);
      assertThat(current.user()).isEqualTo(user);
      assertThat(current.id()).isEqualTo(7L);
      assertThat(current.role()).isEqualTo(Role.STAFF);
      assertThat(current.dataScope()).isEqualTo(DataScope.OWN);
    }
  }

  @Test
  void shouldInvalidateSession_whenAccountIsMissingOrDisabled() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L))
        .thenReturn(
            null,
            new AccountDTO(
                7L,
                "operator",
                "test-hash",
                "Operator",
                null,
                Role.STAFF,
                DataScope.OWN,
                0,
                null,
                null,
                0,
                null,
                null,
                true));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      stp.when(StpUtil::getTokenValue).thenReturn("this-token");
      bindCredentialVersion(stp, 0);
      var current = new CurrentUserContext(accounts);
      for (int i = 0; i < 2; i++) {
        assertThatThrownBy(current::user)
            .isInstanceOf(BusinessException.class)
            .hasMessage(ErrorCode.AUTH_ACCOUNT_DISABLED.message());
      }
      // ADR-022: only this token ends; the person's tokens in other companies stay valid.
      stp.verify(() -> StpUtil.logoutByTokenValue("this-token"), org.mockito.Mockito.times(2));
      stp.verify(() -> StpUtil.kickout(any()), never());
    }
  }

  private static AccountDTO account(long id, Role role, int status) {
    return new AccountDTO(
        id,
        "user" + id,
        "test-hash",
        "User",
        null,
        role,
        DataScope.ALL,
        status,
        null,
        null,
        0,
        null,
        null,
        true);
  }

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void shouldQueryStoreOnce_whenSeveralLookupsHappenInOneRequest() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(account(7L, Role.STAFF, 1));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      stp.when(StpUtil::isLogin).thenReturn(true);
      bindCredentialVersion(stp, 0);
      var current = new CurrentUserContext(accounts);
      current.user();
      current.id();
      current.role();
      current.dataScope();
      current.permissionNames(7L);
      current.currentTenantId();
      verify(accounts, times(1)).getById(7L);
    }
  }

  @Test
  void shouldQueryStoreAgain_whenANewRequestStarts() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(account(7L, Role.STAFF, 1));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      bindCredentialVersion(stp, 0);
      var current = new CurrentUserContext(accounts);
      for (int i = 0; i < 2; i++) {
        RequestContextHolder.setRequestAttributes(
            new ServletRequestAttributes(new MockHttpServletRequest()));
        current.user();
      }
      verify(accounts, times(2)).getById(7L);
    }
  }

  @Test
  void shouldQueryStoreEveryTime_whenNoRequestIsBound() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(account(7L, Role.STAFF, 1));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      bindCredentialVersion(stp, 0);
      var current = new CurrentUserContext(accounts);
      current.user();
      current.user();
      verify(accounts, times(2)).getById(7L);
    }
  }

  @Test
  void shouldKeepRejectingWithoutRequery_whenDisabledAccountIsRepeatedInOneRequest() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(account(7L, Role.STAFF, 0));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      stp.when(StpUtil::getTokenValue).thenReturn("this-token");
      bindCredentialVersion(stp, 0);
      var current = new CurrentUserContext(accounts);
      assertThatThrownBy(current::user).isInstanceOf(BusinessException.class);
      assertThatThrownBy(current::user).isInstanceOf(BusinessException.class);
      verify(accounts, times(1)).getById(7L);
      stp.verify(() -> StpUtil.logoutByTokenValue("this-token"), times(2));
    }
  }

  @Test
  void shouldRevokeToken_whenIssuedForAnOlderCredentialVersion() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L))
        .thenReturn(
            new AccountDTO(
                7L,
                "user7",
                "test-hash",
                "User",
                null,
                Role.STAFF,
                DataScope.ALL,
                1,
                null,
                null,
                3,
                null,
                null,
                true));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      stp.when(StpUtil::getTokenValue).thenReturn("old-token");
      bindCredentialVersion(stp, 2);
      var current = new CurrentUserContext(accounts);
      assertThatThrownBy(current::user)
          .isInstanceOf(BusinessException.class)
          .hasMessage(ErrorCode.AUTH_UNAUTHORIZED.message());
      stp.verify(() -> StpUtil.logoutByTokenValue("old-token"));
    }
  }

  @Test
  void shouldEndOnlyThisToken_whenCompanyIsDisabled() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L, 2L))
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
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      stp.when(StpUtil::isLogin).thenReturn(true);
      stp.when(StpUtil::getTokenValue).thenReturn("this-token");
      bindSession(stp, 0, 2L);
      var current = new CurrentUserContext(accounts);
      assertThatThrownBy(current::user)
          .isInstanceOf(BusinessException.class)
          .hasMessage(ErrorCode.AUTH_ACCOUNT_DISABLED.message());
      stp.verify(() -> StpUtil.logoutByTokenValue("this-token"));
      stp.verify(() -> StpUtil.kickout(any()), never());
      assertThat(current.roleNames(7L)).isEmpty();
      assertThat(current.permissionNames(7L)).isEmpty();
      assertThat(current.roleNames("not-a-number")).isEmpty();
      verify(accounts, never()).getById(7L);
    }
  }

  @Test
  void shouldResolveRoleAndCodesInBoundCompany_whenTokenCarriesACompany() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L, 2L)).thenReturn(member(2L, Role.STAFF, Set.of("kol:view")));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      stp.when(StpUtil::isLogin).thenReturn(true);
      bindSession(stp, 0, 2L);
      var current = new CurrentUserContext(accounts);
      assertThat(current.user().tenantId()).isEqualTo(2L);
      assertThat(current.roleNames(7L)).containsExactly("STAFF");
      assertThat(current.permissionNames(7L)).containsExactly("kol:view");
      assertThat(current.currentTenantId()).isEqualTo(2L);
      verify(accounts, never()).getById(7L);
    }
  }

  @Test
  void shouldBindLastCompany_whenTokenPredatesCompanyBinding() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(member(9L, Role.SUPER_ADMIN, Set.of()));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      var session = bindSession(stp, 0, null);
      var current = new CurrentUserContext(accounts);
      assertThat(current.user().tenantId()).isEqualTo(9L);
      verify(session).set(CurrentUserContext.CURRENT_TENANT, 9L);
    }
  }

  @Test
  void shouldSeeNewCompanyInSameRequest_whenSwitched() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L, 1L)).thenReturn(member(1L, Role.SUPER_ADMIN, Set.of()));
    when(accounts.getById(7L, 2L)).thenReturn(member(2L, Role.STAFF, Set.of()));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      var session = bindSession(stp, 0, 1L);
      var current = new CurrentUserContext(accounts);
      assertThat(current.user().role()).isEqualTo(Role.SUPER_ADMIN);
      current.switchTo(2L);
      verify(session).set(CurrentUserContext.CURRENT_TENANT, 2L);
      when(session.get(CurrentUserContext.CURRENT_TENANT)).thenReturn(2L);
      assertThat(current.user().role()).isEqualTo(Role.STAFF);
      assertThat(current.user().tenantId()).isEqualTo(2L);
    }
  }

  @Test
  void shouldReturnNoCompany_whenNobodyIsLoggedIn() {
    var accounts = mock(AccountStore.class);
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::isLogin).thenReturn(false);
      assertThat(new CurrentUserContext(accounts).currentTenantId()).isNull();
      verifyNoInteractions(accounts);
    }
  }

  @Test
  void shouldEndOnlyTokensOfThatCompany_whenLoggingOutOneCompany() {
    var logic = mock(StpLogic.class);
    var inA = mock(SaSession.class);
    when(inA.get(CurrentUserContext.CURRENT_TENANT)).thenReturn(1L);
    var inB = mock(SaSession.class);
    when(inB.get(CurrentUserContext.CURRENT_TENANT)).thenReturn(2L);
    when(logic.getTokenSessionByToken("token-a", false)).thenReturn(inA);
    when(logic.getTokenSessionByToken("token-b", false)).thenReturn(inB);
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getStpLogic).thenReturn(logic);
      stp.when(() -> StpUtil.getTokenValueListByLoginId(7L))
          .thenReturn(List.of("token-a", "token-b", "token-gone"));
      CurrentUserContext.logoutCompany(7L, 2L);
      stp.verify(() -> StpUtil.logoutByTokenValue("token-b"));
      stp.verify(() -> StpUtil.logoutByTokenValue("token-a"), never());
      stp.verify(() -> StpUtil.logoutByTokenValue("token-gone"), never());
    }
  }

  @Test
  void shouldRevokeToken_whenSessionHasNoCredentialVersion() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(account(7L, Role.STAFF, 1));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      stp.when(StpUtil::getTokenValue).thenReturn("legacy-token");
      stp.when(StpUtil::getTokenSession).thenReturn(mock(SaSession.class));
      var current = new CurrentUserContext(accounts);
      assertThatThrownBy(current::user)
          .isInstanceOf(BusinessException.class)
          .hasMessage(ErrorCode.AUTH_UNAUTHORIZED.message());
      stp.verify(() -> StpUtil.logoutByTokenValue("legacy-token"));
    }
  }

  @Test
  void shouldCheckCredentialVersionOnce_whenSeveralLookupsHappenInOneRequest() {
    var accounts = mock(AccountStore.class);
    when(accounts.getById(7L)).thenReturn(account(7L, Role.STAFF, 1));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
      bindCredentialVersion(stp, 0);
      var current = new CurrentUserContext(accounts);
      current.user();
      current.id();
      current.role();
      stp.verify(StpUtil::getTokenSession, times(1));
    }
  }

  private static void bindCredentialVersion(org.mockito.MockedStatic<StpUtil> stp, int version) {
    bindSession(stp, version, null);
  }

  private static SaSession bindSession(
      org.mockito.MockedStatic<StpUtil> stp, int version, Long tenantId) {
    var session = mock(SaSession.class);
    when(session.get(CurrentUserContext.CREDENTIAL_VERSION)).thenReturn(version);
    when(session.get(CurrentUserContext.CURRENT_TENANT)).thenReturn(tenantId);
    stp.when(StpUtil::getTokenSession).thenReturn(session);
    return session;
  }

  private static AccountDTO member(Long tenantId, Role role, Set<String> codes) {
    return new AccountDTO(
        7L,
        "user7",
        "test-hash",
        "User",
        null,
        role,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        tenantId,
        "Company " + tenantId,
        true,
        codes,
        null);
  }

  @Test
  void shouldSeparateTokenNamespaces_whenCreatingKolLogic() {
    assertThat(StpKolUtil.STP.getLoginType()).isEqualTo("kol");
  }
}
