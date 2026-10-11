import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import { LOCALE_STORAGE_KEY } from "@/utils/locale";
import { useLocaleStore } from "../locale";

function freshStore() {
  // A fresh pinia stands for a page reload: the store reads the saved language again.
  setActivePinia(createPinia());
  return useLocaleStore();
}

describe("locale store", () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.lang = "en";
  });

  it("starts at en-US when nothing is saved", () => {
    const store = freshStore();
    expect(store.locale).toBe("en-US");
    expect(i18n.global.locale.value).toBe("en-US");
    expect(document.documentElement.lang).toBe("en-US");
  });

  it("switching to zh-CN applies it everywhere and persists the choice", () => {
    const store = freshStore();
    store.setLocale("zh-CN");
    expect(store.locale).toBe("zh-CN");
    expect(i18n.global.locale.value).toBe("zh-CN");
    expect(document.documentElement.lang).toBe("zh-CN");
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("zh-CN");
  });

  it("restores the saved language when the store is created again (reload)", () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, "zh-CN");
    const store = freshStore();
    expect(store.locale).toBe("zh-CN");
    expect(i18n.global.locale.value).toBe("zh-CN");
    expect(document.documentElement.lang).toBe("zh-CN");
  });

  it("ignores an unsupported saved value", () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, "fr-FR");
    expect(freshStore().locale).toBe("en-US");
  });

  it("switches back to en-US", () => {
    const store = freshStore();
    store.setLocale("zh-CN");
    store.setLocale("en-US");
    expect(store.locale).toBe("en-US");
    expect(document.documentElement.lang).toBe("en-US");
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("en-US");
  });
});
