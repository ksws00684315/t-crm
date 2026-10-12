package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.Role;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A company role. Platform administrators are created only by bootstrap, not by this API. The
 * message template is resolved per request language from ValidationMessages*.properties.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CompanyRole.Validator.class)
public @interface CompanyRole {
  String message() default "{tcrm.constraint.company-role}";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /** Null is left to {@code @NotNull}; {@code PLATFORM_ADMIN} is rejected. */
  class Validator implements ConstraintValidator<CompanyRole, Role> {
    @Override
    public boolean isValid(Role value, ConstraintValidatorContext context) {
      return value == null || value != Role.PLATFORM_ADMIN;
    }
  }
}
