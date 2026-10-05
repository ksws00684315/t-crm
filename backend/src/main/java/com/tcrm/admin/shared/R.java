package com.tcrm.admin.shared;

import org.slf4j.MDC;

public record R<T>(int code, String msg, T data, String errorCode, String traceId) {
  public static <T> R<T> ok(T data) {
    return new R<>(0, "Success", data, null, MDC.get("traceId"));
  }

  public static <T> R<T> error(ErrorCode error, T data) {
    return new R<>(error.status(), error.message(), data, error.name(), MDC.get("traceId"));
  }
}
