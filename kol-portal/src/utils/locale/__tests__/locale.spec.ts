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
    expect(loadStoredLocale()).toBe("en-US");
    expect(DEFAULT_LOCALE).toBe("en-US");
  });

  it("returns the saved language", () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, "zh-CN");
    expect(loadStoredLocale()).toBe("zh-CN");
  });

  it("falls back to the default language for an unsupported saved value", () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, "fr-FR");
    expect(loadStoredLocale()).toBe("en-US");
  });

  it("falls back to the default language, without throwing, when storage access fails", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("Access is denied for this document");
    });
    expect(loadStoredLocale()).toBe("en-US");
  });

  it("persists the choice under the tcrm.kol.locale key", () => {
    saveStoredLocale("zh-CN");
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("zh-CN");
  });

  it("ignores storage failures when saving", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("QuotaExceededError");
    });
    expect(() => saveStoredLocale("zh-CN")).not.toThrow();
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
