package com.tcrm.admin.shared;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  /** Field errors come from hash sets and reflection, so fix their order for stable responses. */
  private static final Comparator<FieldErrorVO> FIELD_ORDER =
      Comparator.comparing(FieldErrorVO::field, Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(FieldErrorVO::message, Comparator.nullsFirst(Comparator.naturalOrder()));

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<R<?>> business(BusinessException e) {
    return response(e.getErrorCode());
  }

  @ExceptionHandler(NotLoginException.class)
  public ResponseEntity<R<?>> notLogin(NotLoginException e) {
    return response(ErrorCode.AUTH_UNAUTHORIZED);
  }

  @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
  public ResponseEntity<R<?>> forbidden(Exception e) {
    return response(ErrorCode.AUTH_FORBIDDEN);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<R<?>> validation(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest()
        .body(
            R.error(
                ErrorCode.COMMON_VALIDATION_FAILED,
                e.getBindingResult().getFieldErrors().stream()
                    .map(f -> new FieldErrorVO(f.getField(), f.getDefaultMessage()))
                    .sorted(FIELD_ORDER)
                    .toList()));
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class
  })
  public ResponseEntity<R<?>> invalid(Exception e) {
    return response(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<R<?>> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
    return response(ErrorCode.COMMON_UNSUPPORTED_MEDIA_TYPE);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<R<?>> constraints(ConstraintViolationException e) {
    return ResponseEntity.badRequest()
        .body(
            R.error(
                ErrorCode.COMMON_VALIDATION_FAILED,
                e.getConstraintViolations().stream()
                    .map(v -> new FieldErrorVO(v.getPropertyPath().toString(), v.getMessage()))
                    .sorted(FIELD_ORDER)
                    .toList()));
  }

  /**
   * Method validation runs when any parameter carries a constraint (for example {@code @Min} on a
   * path variable); a {@code @Valid} request body then reports through {@link ParameterErrors},
   * whose field errors keep the body's field names. Simple parameters report their own name.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<R<?>> parameters(HandlerMethodValidationException e) {
    return ResponseEntity.badRequest()
        .body(
            R.error(
                ErrorCode.COMMON_VALIDATION_FAILED,
                e.getParameterValidationResults().stream()
                    .flatMap(GlobalExceptionHandler::fieldErrors)
                    .sorted(FIELD_ORDER)
                    .toList()));
  }

  private static Stream<FieldErrorVO> fieldErrors(ParameterValidationResult result) {
    if (result instanceof ParameterErrors errors) {
      return errors.getFieldErrors().stream()
          .map(f -> new FieldErrorVO(f.getField(), f.getDefaultMessage()));
    }
    String name = result.getMethodParameter().getParameterName();
    return result.getResolvableErrors().stream()
        .map(error -> new FieldErrorVO(name, error.getDefaultMessage()));
  }

  /** Uploads above spring.servlet.multipart.max-file-size never reach the controller. */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<R<?>> uploadTooLarge(MaxUploadSizeExceededException e) {
    return response(ErrorCode.TASK_FILE_TOO_LARGE);
  }

  @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
  public ResponseEntity<R<?>> invalidMultipart(Exception e) {
    return response(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<R<?>> duplicate(DuplicateKeyException e) {
    return response(ErrorCode.COMMON_CONFLICT);
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<R<?>> notFound(Exception e) {
    return response(ErrorCode.COMMON_NOT_FOUND);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<R<?>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
    return response(ErrorCode.COMMON_METHOD_NOT_ALLOWED);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<R<?>> unknown(Exception e) {
    LOG.error("Unhandled request failure: {}", e.getClass().getSimpleName());
    return response(ErrorCode.COMMON_INTERNAL_ERROR);
  }

  private ResponseEntity<R<?>> response(ErrorCode error) {
    return ResponseEntity.status(error.status()).body(R.error(error, null));
  }
}
