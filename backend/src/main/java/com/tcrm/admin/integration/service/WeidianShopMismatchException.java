package com.tcrm.admin.integration.service;

/** Indicates that Weidian authorized a different shop than the CRM shop in the saved state. */
public class WeidianShopMismatchException extends RuntimeException {
  public WeidianShopMismatchException() {
    super("Weidian shop identity mismatch");
  }
}
