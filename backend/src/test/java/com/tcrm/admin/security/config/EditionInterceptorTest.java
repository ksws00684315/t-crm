package com.tcrm.admin.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.security.api.CompanyStore;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.EditionScope;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.fixtures.EditionFixture;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * Company edition check (ADR-038 §2.2, task 143 §2.3.1): open, not open and undeclared controllers
 * for both editions, and the endpoints that are never gated.
 */
class EditionInterceptorTest {
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final CompanyStore companies = mock(CompanyStore.class);
  private final EditionInterceptor interceptor = new EditionInterceptor(current, companies);

  @EditionScope({Edition.DISTRIBUTION, Edition.KOL_LIBRARY})
  static class SharedController {
    public void handle() {}
  }

  @EditionScope({Edition.KOL_LIBRARY})
  static class LibraryController {
    public void handle() {}
  }

  static class UndeclaredController {
    public void handle() {}
  }

  private static HandlerMethod handler(Class<?> type) throws Exception {
    Method method = type.getDeclaredMethod("handle");
    return new HandlerMethod(type.getDeclaredConstructor().newInstance(), method);
  }

  private boolean run(Class<?> type, String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    return interceptor.preHandle(request, new MockHttpServletResponse(), handler(type));
  }

  private void company(Edition edition) {
    when(current.currentTenantId()).thenReturn(5L);
    when(companies.editionOf(5L)).thenReturn(edition);
  }

  @Test
  void shouldAllowBothEditions_whenControllerIsOpenToBoth() throws Exception {
    company(Edition.KOL_LIBRARY);
    assertThat(run(SharedController.class, "/admin-api/system/users")).isTrue();
    company(Edition.DISTRIBUTION);
    assertThat(run(SharedController.class, "/admin-api/system/users")).isTrue();
  }

  @Test
  void shouldRefuseDistributionController_inCreatorLibraryCompany() throws Exception {
    company(Edition.KOL_LIBRARY);
    assertThatThrownBy(() -> run(UndeclaredController.class, "/admin-api/kol/kols"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.AUTH_EDITION_FORBIDDEN);
    assertThatCode(() -> run(LibraryController.class, "/admin-api/kol-library/kols"))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldAllowDistributionController_inDistributionCompany() throws Exception {
    company(Edition.DISTRIBUTION);
    assertThat(run(UndeclaredController.class, "/admin-api/kol/kols")).isTrue();
    // A creator-library declaration is a subset: the distribution company is refused that one.
    assertThatThrownBy(() -> run(LibraryController.class, "/admin-api/kol-library/kols"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.AUTH_EDITION_FORBIDDEN);
  }

  @Test
  void shouldTreatUndeclaredController_asDistributionOnly() {
    assertThat(EditionInterceptor.allowedEditions(UndeclaredController.class))
        .containsExactly(Edition.DISTRIBUTION);
    assertThat(EditionInterceptor.allowedEditions(SharedController.class))
        .containsExactly(Edition.DISTRIBUTION, Edition.KOL_LIBRARY);
  }

  @Test
  void shouldNotGatePlatformPublicOrExternalEndpoints() throws Exception {
    company(Edition.KOL_LIBRARY);
    assertThat(run(UndeclaredController.class, "/admin-api/platform/tenants")).isTrue();
    assertThat(run(UndeclaredController.class, "/admin-api/external/copy/tokens")).isTrue();
    assertThat(run(UndeclaredController.class, "/admin-api/auth/login")).isTrue();
    assertThat(run(UndeclaredController.class, "/admin-api/auth/captchas")).isTrue();
    assertThat(run(UndeclaredController.class, "/admin-api/public/site-config")).isTrue();
    assertThat(run(UndeclaredController.class, "/admin-api/integration/weidian/oauth/callback"))
        .isTrue();
    assertThat(EditionInterceptor.requiresCompanyEdition("/admin-api/kol/kols")).isTrue();
  }

  @Test
  void shouldAllowPlatformAdministratorWithoutACompany() throws Exception {
    when(current.currentTenantId()).thenReturn(null);
    assertThat(run(UndeclaredController.class, "/admin-api/kol/kols")).isTrue();
  }

  @Test
  void shouldReadDeclaredScope_whenDeclarationIsLibraryOnly() {
    assertThat(EditionInterceptor.allowedEditions(EditionFixture.class))
        .containsExactly(Edition.KOL_LIBRARY);
  }
}
