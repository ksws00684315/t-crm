package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerOrderTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  record Sample(@NotBlank String zeta, @NotBlank String alpha, @NotBlank String mid) {}

  @SuppressWarnings("unchecked")
  private static List<FieldErrorVO> data(org.springframework.http.ResponseEntity<R<?>> response) {
    return (List<FieldErrorVO>) response.getBody().data();
  }

  @Test
  void shouldSortBodyFieldErrors_whenBindingResultIsUnordered() throws Exception {
    var binding = new BeanPropertyBindingResult(new Object(), "target");
    binding.addError(new FieldError("target", "zeta", "must not be blank"));
    binding.addError(new FieldError("target", "alpha", "must not be null"));
    binding.addError(new FieldError("target", "alpha", "must not be blank"));
    binding.addError(new FieldError("target", "mid", "must not be blank"));
    var method = GlobalExceptionHandlerOrderTest.class.getDeclaredMethod("sample", Sample.class);
    var exception = new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);
    assertThat(data(handler.validation(exception)))
        .containsExactly(
            new FieldErrorVO("alpha", "must not be blank"),
            new FieldErrorVO("alpha", "must not be null"),
            new FieldErrorVO("mid", "must not be blank"),
            new FieldErrorVO("zeta", "must not be blank"));
  }

  @Test
  void shouldSortConstraintViolations_whenSetIsUnordered() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var violations = factory.getValidator().validate(new Sample(null, null, null));
      assertThat(violations).hasSize(3);
      var exception = new ConstraintViolationException(violations);
      var fields = data(handler.constraints(exception)).stream().map(FieldErrorVO::field).toList();
      assertThat(fields).isSorted().hasSize(3);
    }
  }

  void sample(Sample sample) {}
}
