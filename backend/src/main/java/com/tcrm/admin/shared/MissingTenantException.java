package com.tcrm.admin.shared;

/**
 * A business query ran with neither an explicit company scope nor a logged-in company. This is a
 * programming error, not a client error, so the global handler answers 500.
 */
public class MissingTenantException extends RuntimeException {
  public MissingTenantException(String message) {
    super(message);
  }
}
