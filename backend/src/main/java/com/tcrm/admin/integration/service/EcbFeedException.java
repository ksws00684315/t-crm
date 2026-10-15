package com.tcrm.admin.integration.service;

/** The ECB feed could not be fetched or was rejected. The message is short English, safe to log. */
public class EcbFeedException extends RuntimeException {
  public EcbFeedException(String message) {
    super(message);
  }
}
