import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it } from "vitest";
import { useLocaleStore } from "@/stores/locale";
import {
  fieldErrorMessage,
  genericErrorMessage,
  isKnownErrorCode,
  messageForErrorCode,
  networkErrorMessage
} from "../error-codes";

describe("error-code text", () => {
  it.each([
    "PREPAY_REQUEST_CONFLICT",
    "PREPAY_STATE_CONFLICT",
    "PREPAY_DETAILS_REQUIRED",
    "PREPAY_PAYMENT_CONFLICT",
    "PREPAY_FILE_INVALID",
    "PREPAY_FILE_TOO_LARGE"
  ])("provides both languages for %s", code => {
    expect(isKnownErrorCode(code)).toBe(true);
    expect(messageForErrorCode(code)).not.toBe(genericErrorMessage());
    useLocaleStore().setLocale("zh-CN");
    expect(messageForErrorCode(code)).not.toBe(genericErrorMessage());
    expect(messageForErrorCode(code)).toMatch(/[\u4e00-\u9fa5]/);
  });
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    useLocaleStore().setLocale("en-US");
  });

  it("returns English text for a known code by default", () => {
    expect(messageForErrorCode("AUTH_INVALID_CREDENTIALS")).toBe("Incorrect email or password.");
    expect(messageForErrorCode("AUTH_UNAUTHORIZED")).toBe(
      "Your session has expired. Please sign in again."
    );
  });

  it("returns Chinese text for the same code after switching languages", () => {
    useLocaleStore().setLocale("zh-CN");
    expect(messageForErrorCode("AUTH_INVALID_CREDENTIALS")).toBe("邮箱或密码错误。");
    expect(messageForErrorCode("AUTH_UNAUTHORIZED")).toBe("登录已失效，请重新登录。");
  });

  it("returns the generic text of the current language for unknown codes", () => {
    expect(messageForErrorCode("SOMETHING_ELSE")).toBe(
      "The operation failed. Please try again later."
    );
    useLocaleStore().setLocale("zh-CN");
    expect(messageForErrorCode("SOMETHING_ELSE")).toBe("操作失败，请稍后重试。");
    expect(messageForErrorCode(null)).toBe("操作失败，请稍后重试。");
  });

  it("switches the network and field fallback texts with the language", () => {
    expect(networkErrorMessage()).toBe(
      "Network error. Please check your connection and try again."
    );
    expect(fieldErrorMessage()).toBe("This field is not valid.");
    useLocaleStore().setLocale("zh-CN");
    expect(networkErrorMessage()).toBe("网络异常，请检查网络连接后重试。");
    expect(fieldErrorMessage()).toBe("该字段格式不正确。");
    expect(genericErrorMessage()).toBe("操作失败，请稍后重试。");
  });

  it("knows the backend codes but not the fallback keys or null", () => {
    expect(isKnownErrorCode("COMMON_VALIDATION_FAILED")).toBe(true);
    expect(isKnownErrorCode("AUTH_ACCOUNT_DISABLED")).toBe(true);
    expect(isKnownErrorCode("generic")).toBe(false);
    expect(isKnownErrorCode("network")).toBe(false);
    expect(isKnownErrorCode("field")).toBe(false);
    expect(isKnownErrorCode(null)).toBe(false);
  });
});
