package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ISO 3166-1 alpha-2 country code, case-insensitive; null is valid (combine with NotBlank). The
 * message template is resolved per request language from ValidationMessages*.properties.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CountryCode.Validator.class)
public @interface CountryCode {
  String message() default "{tcrm.constraint.country-code}";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /** Accepts exactly two letters that form an assigned ISO country code. */
  class Validator implements ConstraintValidator<CountryCode, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value == null || (value.strip().length() == 2 && KolRules.isCountry(value.strip()));
    }
  }
}
