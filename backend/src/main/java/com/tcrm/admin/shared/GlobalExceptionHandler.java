package com.tcrm.admin.shared;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
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
                    .toList()));
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public ResponseEntity<R<?>> invalid(Exception e) {
    return response(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<R<?>> constraints(ConstraintViolationException e) {
    return ResponseEntity.badRequest()
        .body(
            R.error(
                ErrorCode.COMMON_VALIDATION_FAILED,
                e.getConstraintViolations().stream()
                    .map(v -> new FieldErrorVO(v.getPropertyPath().toString(), v.getMessage()))
                    .toList()));
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<R<?>> parameters(HandlerMethodValidationException e) {
    return ResponseEntity.badRequest()
        .body(
            R.error(
                ErrorCode.COMMON_VALIDATION_FAILED,
                e.getParameterValidationResults().stream()
                    .flatMap(
                        r ->
                            r.getResolvableErrors().stream()
                                .map(
                                    error ->
                                        new FieldErrorVO(
                                            r.getMethodParameter().getParameterName(),
                                            error.getDefaultMessage())))
                    .toList()));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<R<?>> duplicate(DuplicateKeyException e) {
    return response(ErrorCode.USER_USERNAME_EXISTS);
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
