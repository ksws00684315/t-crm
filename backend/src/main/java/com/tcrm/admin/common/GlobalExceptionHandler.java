package com.tcrm.admin.common;

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
  public ResponseEntity<R<Void>> business(BusinessException e) {
    return response(e.getCode(), e.getMessage());
  }

  @ExceptionHandler(NotLoginException.class)
  public ResponseEntity<R<Void>> notLogin(NotLoginException e) {
    return response(401, "Authentication required");
  }

  @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
  public ResponseEntity<R<Void>> forbidden(Exception e) {
    return response(403, "Access denied");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<R<Void>> validation(MethodArgumentNotValidException e) {
    return response(
        400,
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst()
            .orElse("Invalid request"));
  }

  @ExceptionHandler({
    ConstraintViolationException.class,
    HandlerMethodValidationException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public ResponseEntity<R<Void>> invalid(Exception e) {
    return response(400, "Invalid request");
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<R<Void>> duplicate(DuplicateKeyException e) {
    return response(409, "Username already exists");
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<R<Void>> notFound(Exception e) {
    return response(404, "Not found");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<R<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
    return response(405, "Method not allowed");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<R<Void>> unknown(Exception e) {
    LOG.error("Unhandled request failure", e);
    return response(500, "Internal server error");
  }

  private ResponseEntity<R<Void>> response(int code, String message) {
    return ResponseEntity.status(code).body(R.error(code, message));
  }
}
