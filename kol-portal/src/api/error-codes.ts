/**
 * Maps backend error codes to English user-facing text. The backend `msg` is never shown.
 * Creator-specific codes are added together with the creator API in later tasks.
 */
export const ERROR_MESSAGES: Readonly<Record<string, string>> = {
  COMMON_VALIDATION_FAILED:
    "Some of the information you entered is not valid. Please check and try again.",
  COMMON_NOT_FOUND: "The requested item does not exist.",
  COMMON_METHOD_NOT_ALLOWED: "This action is not supported.",
  COMMON_CONFLICT: "The data has changed. Please refresh and try again.",
  COMMON_INTERNAL_ERROR: "Something went wrong on our side. Please try again later.",
  AUTH_UNAUTHORIZED: "Your session has expired. Please sign in again.",
  AUTH_FORBIDDEN: "You do not have permission to do this.",
  AUTH_INVALID_CREDENTIALS: "Incorrect account or password.",
  AUTH_ACCOUNT_LOCKED: "Too many failed sign-in attempts. Please try again later.",
  AUTH_ACCOUNT_DISABLED: "This account is disabled or unavailable."
};

export const GENERIC_ERROR_MESSAGE = "The operation failed. Please try again later.";
export const NETWORK_ERROR_MESSAGE = "Network error. Please check your connection and try again.";
export const FIELD_ERROR_MESSAGE = "This field is not valid.";

export function isKnownErrorCode(errorCode: string | null | undefined): boolean {
  return !!errorCode && Object.prototype.hasOwnProperty.call(ERROR_MESSAGES, errorCode);
}

/** English text for an error code; unknown or missing codes get the generic text. */
export function messageForErrorCode(errorCode: string | null | undefined): string {
  return errorCode && isKnownErrorCode(errorCode)
    ? ERROR_MESSAGES[errorCode]
    : GENERIC_ERROR_MESSAGE;
}
