package com.tcrm.admin.integration.service;

/** Safe, typed failure from a Weidian token or business API operation. */
public class WeidianApiException extends RuntimeException {
  private final int statusCode;
  private final boolean retryable;

  public WeidianApiException(int statusCode, String safeMessage, boolean retryable) {
    super(safeMessage);
    this.statusCode = statusCode;
    this.retryable = retryable;
  }

  public int statusCode() {
    return statusCode;
  }

  public boolean retryable() {
    return retryable;
  }
}
