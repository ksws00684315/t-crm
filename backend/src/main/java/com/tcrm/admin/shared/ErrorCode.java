package com.tcrm.admin.shared;

/** Stable error registry. HTTP status expresses category; name expresses the cause. */
public enum ErrorCode {
  COMMON_VALIDATION_FAILED(400, "Invalid request"),
  COMMON_NOT_FOUND(404, "Not found"),
  COMMON_METHOD_NOT_ALLOWED(405, "Method not allowed"),
  COMMON_INTERNAL_ERROR(500, "Internal server error"),
  AUTH_UNAUTHORIZED(401, "Authentication required"),
  AUTH_FORBIDDEN(403, "Access denied"),
  AUTH_INVALID_CREDENTIALS(401, "Invalid username or password"),
  AUTH_ACCOUNT_LOCKED(429, "Too many failed login attempts; try again in 15 minutes"),
  AUTH_ACCOUNT_DISABLED(401, "Account disabled or unavailable"),
  AUTH_OLD_PASSWORD_INCORRECT(422, "Old password is incorrect"),
  USER_USERNAME_EXISTS(409, "Username already exists"),
  USER_CANNOT_DISABLE_SELF(422, "Cannot disable yourself"),
  USER_CANNOT_REMOVE_SELF_ROLE(422, "Cannot remove your own super administrator role"),
  USER_INVALID_DATA_SCOPE(422, "OWN data scope requires OPERATOR role");
  private final int status;
  private final String message;

  ErrorCode(int status, String message) {
    this.status = status;
    this.message = message;
  }

  public int status() {
    return status;
  }

  public String message() {
    return message;
  }
}
