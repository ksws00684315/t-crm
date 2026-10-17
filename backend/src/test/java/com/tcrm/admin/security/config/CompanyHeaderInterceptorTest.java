package com.tcrm.admin.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.AntPathMatcher;

class CompanyHeaderInterceptorTest {
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final CompanyHeaderInterceptor interceptor = new CompanyHeaderInterceptor(current);

  private static AccountDTO in(Long tenantId, Role role) {
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
        tenantId == null ? null : "Company",
        true);
  }

  private boolean handle(String header) {
    var request = new MockHttpServletRequest("GET", "/admin-api/kol/kols");
    if (header != null) {
      request.addHeader(CompanyHeaderInterceptor.HEADER, header);
    }
    return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
  }

  @Test
  void shouldPass_whenHeaderMatchesTheSessionCompany() {
    when(current.user()).thenReturn(in(2L, Role.STAFF));
    assertThat(handle("2")).isTrue();
    assertThat(handle(" 2 ")).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "3", "not-a-number", "02"})
  void shouldRejectWithSwitched_whenHeaderDiffersFromTheSessionCompany(String header) {
    when(current.user()).thenReturn(in(2L, Role.SUPER_ADMIN));
    assertThatThrownBy(() -> handle(header))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.AUTH_COMPANY_SWITCHED.message());
  }

  @Test
  void shouldNotCheck_whenHeaderIsMissingOrBlank() {
    assertThat(handle(null)).isTrue();
    assertThat(handle("  ")).isTrue();
    verifyNoInteractions(current);
  }

  @Test
  void shouldReject_whenCallerHasNoCompany() {
    when(current.user()).thenReturn(in(null, Role.PLATFORM_ADMIN));
    assertThatThrownBy(() -> handle("1")).hasMessage(ErrorCode.AUTH_COMPANY_SWITCHED.message());
  }

  @Test
  void shouldSkipAuthAndPlatformPaths_butCoverBusinessPaths() {
    var paths = new AntPathMatcher();
    for (String excluded :
        new String[] {
          "/admin-api/auth/company", "/admin-api/auth/me", "/admin-api/platform/users"
        }) {
      assertThat(matchesAny(paths, CompanyHeaderInterceptor.EXCLUDED, excluded)).isTrue();
    }
    for (String covered : new String[] {"/admin-api/kol/kols", "/admin-api/system/users"}) {
      assertThat(matchesAny(paths, CompanyHeaderInterceptor.PATTERNS, covered)).isTrue();
      assertThat(matchesAny(paths, CompanyHeaderInterceptor.EXCLUDED, covered)).isFalse();
    }
  }

  private static boolean matchesAny(AntPathMatcher paths, String[] patterns, String path) {
    for (String pattern : patterns) {
      if (paths.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }
}
