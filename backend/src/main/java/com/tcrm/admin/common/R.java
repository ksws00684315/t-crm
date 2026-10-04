package com.tcrm.admin.common;

public record R<T>(int code, String msg, T data) {
  public static <T> R<T> ok(T data) {
    return new R<>(0, "Success", data);
  }

  public static R<Void> error(int code, String msg) {
    return new R<>(code, msg, null);
  }
}
