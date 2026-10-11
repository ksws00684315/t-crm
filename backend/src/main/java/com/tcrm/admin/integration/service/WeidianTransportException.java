package com.tcrm.admin.integration.service;

/** Transport failure with no URL, credential, or provider response text attached. */
public class WeidianTransportException extends RuntimeException {
  private final boolean retryable;

  public WeidianTransportException(boolean retryable) {
    super("Weidian request transport failed");
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }
}
