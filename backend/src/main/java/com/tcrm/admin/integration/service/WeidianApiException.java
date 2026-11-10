package com.tcrm.admin.integration.service;

/** Safe, typed failure from a Weidian token or business API operation. */
public class WeidianApiException extends RuntimeException {
  /**
   * Weidian did not answer with a usable response: the transport failed, the token response was
   * incomplete, or the token lifetime was invalid. The failure concerns the whole shop (task 145
   * review).
   */
  public static final int NO_USABLE_RESPONSE = -1;

  /**
   * Weidian answered {@code status_code = 0} but without a result. Only the current call is
   * affected: a caller such as order sync must skip just that order instead of failing the shop
   * (task 145 review).
   */
  public static final int MISSING_RESULT = -2;

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
