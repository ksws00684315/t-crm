package com.tcrm.admin.shared;

public class BusinessException extends RuntimeException {
  private final ErrorCode errorCode;
  private final Object data;
  private final boolean customMessage;

  public BusinessException(ErrorCode errorCode) {
    this(errorCode, null, null);
  }

  public BusinessException(ErrorCode errorCode, Object data) {
    this(errorCode, null, data);
  }

  /**
   * Overrides the English {@link ErrorCode#message()} when the API must return a Chinese (or other)
   * operator-facing {@code msg} — used by the external product-link APIs (design §6.5).
   */
  public BusinessException(ErrorCode errorCode, String message, Object data) {
    super(message == null || message.isBlank() ? errorCode.message() : message);
    this.errorCode = errorCode;
    this.data = data;
    this.customMessage = message != null && !message.isBlank();
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  public int getCode() {
    return errorCode.status();
  }

  /** Optional payload returned in {@code R.data} (for example missing field names). */
  public Object getData() {
    return data;
  }

  public boolean hasCustomMessage() {
    return customMessage;
  }
}
