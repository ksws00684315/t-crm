package com.tcrm.admin.system.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The plain Jakarta validator (no Spring request context) captures the JVM default locale when the
 * factory is built; pin English before instantiation so the assertions hold on any machine.
 */
class PlatformRequestValidationTest {
  private static Locale originalDefault;
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @BeforeAll
  static void useEnglish() {
    originalDefault = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
  }

  @AfterAll
  static void restoreLocale() {
    Locale.setDefault(originalDefault);
  }

  @Test
  void shouldRejectPlatformRole_andKeepCompanyRoles() {
    var platform =
        new CreateUserRequest(
            "operator", "password123", "Nick", null, Role.PLATFORM_ADMIN, DataScope.ALL);
    assertThat(validator.validate(platform))
        .anyMatch(
            violation ->
                violation.getPropertyPath().toString().equals("role")
                    && violation.getMessage().equals("must be a company role"));
    var company =
        new CreateUserRequest(
            "operator", "password123", "Nick", null, Role.OPERATOR, DataScope.OWN);
    assertThat(validator.validate(company)).isEmpty();
    var missing =
        new CreateUserRequest("operator", "password123", "Nick", null, null, DataScope.ALL);
    assertThat(validator.validate(missing))
        .anyMatch(violation -> violation.getPropertyPath().toString().equals("role"));
    assertThat(new CompanyRole.Validator().isValid(null, null)).isTrue();
    assertThat(new CompanyRole.Validator().isValid(Role.PLATFORM_ADMIN, null)).isFalse();
    assertThat(new CompanyRole.Validator().isValid(Role.FINANCE, null)).isTrue();
  }

  @Test
  void shouldValidateTenantAndAdministratorRequests() {
    assertThat(validator.validate(new CreateTenantRequest("acme", "Acme", null))).isEmpty();
    assertThat(validator.validate(new CreateTenantRequest("A", "Acme", null))).isNotEmpty();
    assertThat(validator.validate(new CreateTenantRequest("a", "Acme", null))).isNotEmpty();
    assertThat(validator.validate(new CreateTenantRequest("acme", " ", "x".repeat(501))))
        .isNotEmpty();
    assertThat(validator.validate(new EditTenantRequest("Acme", null))).isEmpty();
    assertThat(validator.validate(new EditTenantRequest("", null))).isNotEmpty();
    assertThat(
            validator.validate(
                new CreateTenantAdminRequest("admin", "password123", "Nick", "a@b.co")))
        .isEmpty();
    assertThat(validator.validate(new CreateTenantAdminRequest("ad", "short", "", "not-an-email")))
        .isNotEmpty();
  }
}
