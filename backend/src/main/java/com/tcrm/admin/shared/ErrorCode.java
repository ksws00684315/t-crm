package com.tcrm.admin.shared;

/** Stable error registry. HTTP status expresses category; name expresses the cause. */
public enum ErrorCode {
  COMMON_VALIDATION_FAILED(400, "Invalid request"),
  COMMON_NOT_FOUND(404, "Not found"),
  COMMON_METHOD_NOT_ALLOWED(405, "Method not allowed"),
  COMMON_CONFLICT(409, "Conflicts with existing data"),
  COMMON_INTERNAL_ERROR(500, "Internal server error"),
  AUTH_UNAUTHORIZED(401, "Authentication required"),
  AUTH_FORBIDDEN(403, "Access denied"),
  AUTH_INVALID_CREDENTIALS(401, "Invalid username or password"),
  AUTH_ACCOUNT_LOCKED(429, "Too many failed login attempts; try again in 15 minutes"),
  AUTH_CAPTCHA_INVALID(400, "Captcha is wrong or expired"),
  AUTH_CAPTCHA_RATE_LIMITED(429, "Too many captcha requests; try again later"),
  AUTH_ACCOUNT_DISABLED(401, "Account disabled or unavailable"),
  AUTH_OLD_PASSWORD_INCORRECT(422, "Old password is incorrect"),
  USER_USERNAME_EXISTS(409, "Username already exists"),
  USER_CANNOT_DISABLE_SELF(422, "Cannot disable yourself"),
  USER_CANNOT_REMOVE_SELF_ROLE(422, "Cannot remove your own super administrator role"),
  USER_INVALID_DATA_SCOPE(422, "OWN data scope requires STAFF role"),
  USER_PERMISSION_INVALID(400, "Unknown permission code"),
  USER_LAST_SUPER_ADMIN(422, "At least one enabled super administrator must remain"),
  TENANT_CODE_EXISTS(409, "Tenant code already exists"),
  KOL_EMAIL_EXISTS(409, "KOL email already exists"),
  KOL_CODE_EXISTS(409, "KOL code already exists"),
  KOL_ALREADY_ACTIVATED(409, "KOL has already activated the account"),
  KOL_AUTH_TOKEN_INVALID(400, "Invitation or reset token is invalid, expired, or already used"),
  KOL_LOGIN_SELECTION_INVALID(400, "Company selection credential is invalid or expired"),
  KOL_INVALID_BD_USER(422, "BD must be an enabled STAFF account with OWN data scope and kol:edit"),
  KOL_REGISTER_CODE_INVALID(404, "Register link is invalid or no longer usable"),
  KOL_EMAIL_CODE_TOO_FREQUENT(429, "Email verification code was requested too recently"),
  KOL_EMAIL_CODE_INVALID(400, "Email verification code is wrong, expired, or already used"),
  KOL_REGISTER_TOO_FREQUENT(429, "Too many registration attempts; try again later"),
  KOL_MULTIPLE_PRIMARY(400, "At most one social account can be the primary one"),
  LINK_TEMPLATE_INVALID(400, "Link template must be an enabled template of this company"),
  LINK_TEMPLATE_NOT_FOUND(404, "Link template not found"),
  LINK_TEMPLATE_CODE_EXISTS(409, "Template code already exists"),
  TASK_QUEUE_FULL(429, "Too many tasks waiting; try again later"),
  TASK_FILE_INVALID(400, "Import file must be a valid .xlsx workbook"),
  TASK_FILE_TOO_LARGE(400, "Import file exceeds 20 MB"),
  TASK_FILE_TOO_MANY_ROWS(400, "Import file exceeds the row limit"),
  TASK_FILE_HEADER_MISMATCH(400, "Import file header does not match the template"),
  TASK_FILE_EMPTY(400, "Import file contains no data rows"),
  TASK_FILE_EXPIRED(404, "Task file has been cleaned up after the retention period"),
  SHOP_LIMIT_REACHED(422, "At most 3 shops are allowed"),
  SHOP_WEIDIAN_ID_EXISTS(409, "Weidian shop ID already exists"),
  SHOP_DISABLED(422, "Shop is disabled"),
  SHOP_NOT_FOUND(404, "Shop not found"),
  PRODUCT_NOT_FOUND(404, "Product not found"),
  PRODUCT_CODE_EXISTS(409, "Product code already exists"),
  PRODUCT_WEIDIAN_ID_EXISTS(409, "Weidian item ID already exists in this shop"),
  PRODUCT_CODE_IMMUTABLE(422, "Product code cannot be changed"),
  PRODUCT_IS_EXCLUSIVE_ITEM(422, "Exclusive item cannot be registered as an original product"),
  ORDER_SYNC_RUNNING(409, "Order synchronization is already running for this shop"),
  LINK_REBUILD_RUNNING(409, "A link index rebuild is already running"),
  WEIDIAN_APP_NOT_CONFIGURED(422, "Weidian service application is not configured"),
  WEIDIAN_SHOP_NOT_AUTHORIZED(422, "Weidian shop is not connected"),
  WEIDIAN_CREDENTIAL_INVALID(422, "Weidian appkey or secret is invalid"),
  WEIDIAN_API_FAILED(502, "Weidian API request failed"),
  WEIDIAN_RATE_LIMITED(429, "Weidian API rate limit wait timed out");
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
