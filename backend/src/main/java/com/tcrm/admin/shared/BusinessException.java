package com.tcrm.admin.shared;

public class BusinessException extends RuntimeException {
  private final ErrorCode errorCode;
  private final Object data;

  public BusinessException(ErrorCode errorCode) {
    this(errorCode, null);
  }

  public BusinessException(ErrorCode errorCode, Object data) {
    super(errorCode.message());
    this.errorCode = errorCode;
    this.data = data;
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
}
