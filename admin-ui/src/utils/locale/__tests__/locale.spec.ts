import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  DEFAULT_LOCALE,
  isSupportedLocale,
  LOCALE_STORAGE_KEY,
  loadStoredLocale,
  saveStoredLocale
} from "@/utils/locale";

describe("stored locale", () => {
  beforeEach(() => localStorage.clear());

  it("returns the default language when nothing is saved", () => {
    expect(loadStoredLocale()).toBe("zh-CN");
    expect(DEFAULT_LOCALE).toBe("zh-CN");
  });

  it("returns the saved language", () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, "en-US");
    expect(loadStoredLocale()).toBe("en-US");
  });

  it("falls back to the default language for an unsupported saved value", () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, "fr-FR");
    expect(loadStoredLocale()).toBe("zh-CN");
  });

  it("falls back to the default language, without throwing, when storage access fails", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("Access is denied for this document");
    });
    expect(loadStoredLocale()).toBe("zh-CN");
  });

  it("persists the choice under the tcrm.admin.locale key", () => {
    saveStoredLocale("en-US");
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("en-US");
    expect(LOCALE_STORAGE_KEY).toBe("tcrm.admin.locale");
  });

  it("ignores storage failures when saving", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("QuotaExceededError");
    });
    expect(() => saveStoredLocale("en-US")).not.toThrow();
  });
});

describe("isSupportedLocale", () => {
  it("accepts the two supported languages only", () => {
    expect(isSupportedLocale("en-US")).toBe(true);
    expect(isSupportedLocale("zh-CN")).toBe(true);
    expect(isSupportedLocale("fr-FR")).toBe(false);
    expect(isSupportedLocale(null)).toBe(false);
  });
});
