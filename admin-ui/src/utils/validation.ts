import type { FormItemRule } from "element-plus";
import { fieldErrorMessage } from "@/api/error-codes";
import { translate } from "@/locales";
import type { ApiError } from "@/utils/http/error";

/*
 * Front-end validation mirrors the backend constraints one to one:
 *   username  @NotBlank @Pattern([A-Za-z0-9_.-]{3,64})        (CreateUserRequest, LoginRequest)
 *   password  @NotBlank @Size(10..72) @Pattern([\x20-\x7E]+)   (CreateUserRequest, ResetPasswordRequest,
 *                                                               PasswordRequest.newPassword)
 *   login password / old password  @NotBlank @Size(max 72)
 *   nickname  @NotBlank @Size(max 64)
 *   email     @Email @Size(max 254), optional
 * Each validator returns an error text, or null when the value is acceptable.
 */

export const USERNAME_PATTERN = /^[A-Za-z0-9_.-]{3,64}$/;
export const PASSWORD_MIN = 10;
export const PASSWORD_MAX = 72;
const PRINTABLE_ASCII = /^[\x20-\x7E]+$/;
/** Same shape Hibernate's @Email accepts for practical addresses. */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const blank = (value: string | null | undefined) => !value || value.trim().length === 0;

export function validateUsername(value: string): string | null {
  if (blank(value)) return translate("validation.usernameRequired");
  if (!USERNAME_PATTERN.test(value)) return translate("validation.usernamePattern");
  return null;
}

/** Password being set (create, reset, change). */
export function validateNewPassword(value: string): string | null {
  if (blank(value)) return translate("validation.passwordRequired");
  if (value.length < PASSWORD_MIN || value.length > PASSWORD_MAX) {
    return translate("validation.passwordLength", { min: PASSWORD_MIN, max: PASSWORD_MAX });
  }
  if (!PRINTABLE_ASCII.test(value)) return translate("validation.passwordCharset");
  return null;
}

/** Password being presented (login, old password): only presence and the 72 character cap. */
export function validateExistingPassword(value: string): string | null {
  if (blank(value)) return translate("validation.passwordRequired");
  if (value.length > PASSWORD_MAX) {
    return translate("validation.passwordTooLong", { max: PASSWORD_MAX });
  }
  return null;
}

export function validateCaptcha(value: string): string | null {
  return blank(value) ? translate("validation.captchaRequired") : null;
}

export function validateNickname(value: string): string | null {
  if (blank(value)) return translate("validation.nicknameRequired");
  if (value.length > 64) return translate("validation.nicknameTooLong");
  return null;
}

export function validateTenantCode(value: string): string | null {
  if (!value) return translate("validation.tenantCodeRequired");
  if (!/^[a-z0-9-]{2,32}$/.test(value)) return translate("validation.tenantCodePattern");
  return null;
}

export function validateTenantName(value: string): string | null {
  if (blank(value)) return translate("validation.nameRequired");
  if (value.length > 64) return translate("validation.nameTooLong");
  return null;
}

export function validateRemark(value: string | null | undefined): string | null {
  if (value && value.length > 500) return translate("validation.remarkTooLong");
  return null;
}

export function validateEmail(value: string | null | undefined): string | null {
  if (!value) return null;
  if (value.length > 254) return translate("validation.emailTooLong");
  if (!EMAIL_PATTERN.test(value)) return translate("validation.emailInvalid");
  return null;
}

/** Wraps a validator as an Element Plus form rule. */
export function rule(
  validator: (value: string) => string | null,
  trigger: "blur" | "change" = "blur"
): FormItemRule {
  return {
    trigger,
    validator: (_rule, value: string | null | undefined, callback) => {
      const message = validator(value ?? "");
      callback(message ? new Error(message) : undefined);
    }
  };
}

/**
 * Turns the field names of a COMMON_VALIDATION_FAILED response into our own generic text per
 * field. The backend field messages are deliberately ignored.
 */
export function serverFieldErrors(
  error: unknown,
  knownFields: readonly string[]
): Record<string, string> {
  const result: Record<string, string> = {};
  const fields = (error as Partial<ApiError> | null)?.fields ?? [];
  for (const field of fields) {
    if (knownFields.includes(field)) result[field] = fieldErrorMessage();
  }
  return result;
}
