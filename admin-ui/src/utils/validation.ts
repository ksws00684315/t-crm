import type { FormItemRule } from "element-plus";
import { FIELD_ERROR_MESSAGE } from "@/api/error-codes";
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
  if (blank(value)) return "请输入用户名";
  if (!USERNAME_PATTERN.test(value)) return "用户名需为 3-64 位字母、数字、下划线、点或连字符";
  return null;
}

/** Password being set (create, reset, change). */
export function validateNewPassword(value: string): string | null {
  if (blank(value)) return "请输入密码";
  if (value.length < PASSWORD_MIN || value.length > PASSWORD_MAX) {
    return `密码长度需为 ${PASSWORD_MIN}-${PASSWORD_MAX} 位`;
  }
  if (!PRINTABLE_ASCII.test(value)) return "密码只能包含英文字母、数字和常见符号";
  return null;
}

/** Password being presented (login, old password): only presence and the 72 character cap. */
export function validateExistingPassword(value: string): string | null {
  if (blank(value)) return "请输入密码";
  if (value.length > PASSWORD_MAX) return `密码不能超过 ${PASSWORD_MAX} 位`;
  return null;
}

export function validateNickname(value: string): string | null {
  if (blank(value)) return "请输入昵称";
  if (value.length > 64) return "昵称不能超过 64 个字符";
  return null;
}

export function validateEmail(value: string | null | undefined): string | null {
  if (!value) return null;
  if (value.length > 254) return "邮箱不能超过 254 个字符";
  if (!EMAIL_PATTERN.test(value)) return "邮箱格式不正确";
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
    if (knownFields.includes(field)) result[field] = FIELD_ERROR_MESSAGE;
  }
  return result;
}
