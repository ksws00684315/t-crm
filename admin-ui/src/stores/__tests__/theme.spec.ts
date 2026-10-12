import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { THEME_STORAGE_KEY } from "@/utils/theme";
import { useThemeStore } from "../theme";

type Listener = (event: MediaQueryListEvent) => void;

function installMatchMedia(matches: boolean) {
  const listeners: Listener[] = [];
  const media = {
    matches,
    media: "(prefers-color-scheme: dark)",
    addEventListener: (_type: string, listener: Listener) => {
      listeners.push(listener);
    },
    removeEventListener: (_type: string, listener: Listener) => {
      const index = listeners.indexOf(listener);
      if (index >= 0) listeners.splice(index, 1);
    },
    setMatches(next: boolean) {
      media.matches = next;
      for (const listener of listeners) listener({ matches: next } as MediaQueryListEvent);
    }
  };
  vi.stubGlobal("matchMedia", () => media);
  return media;
}

function freshStore() {
  setActivePinia(createPinia());
  return useThemeStore();
}

describe("theme store", () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove("dark");
    vi.unstubAllGlobals();
    installMatchMedia(false);
  });

  it("starts in light mode and does not mark the document dark", () => {
    const store = freshStore();
    expect(store.mode).toBe("light");
    expect(store.resolved).toBe("light");
    expect(document.documentElement.classList.contains("dark")).toBe(false);
  });

  it("cycles light, dark, then system, and persists each choice", () => {
    const store = freshStore();
    store.cycle();
    expect(store.mode).toBe("dark");
    expect(store.resolved).toBe("dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");

    store.cycle();
    expect(store.mode).toBe("system");
    expect(store.resolved).toBe("light");
    expect(document.documentElement.classList.contains("dark")).toBe(false);
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("system");

    store.cycle();
    expect(store.mode).toBe("light");
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");
  });

  it("follows the operating system while the mode is system", () => {
    const media = installMatchMedia(false);
    const store = freshStore();
    store.setMode("system");
    expect(store.resolved).toBe("light");
    expect(document.documentElement.classList.contains("dark")).toBe(false);

    media.setMatches(true);
    expect(store.resolved).toBe("dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);

    media.setMatches(false);
    expect(store.resolved).toBe("light");
    expect(document.documentElement.classList.contains("dark")).toBe(false);
  });

  it("keeps an explicit dark choice when the operating system changes", () => {
    const media = installMatchMedia(true);
    const store = freshStore();
    store.setMode("dark");
    media.setMatches(false);
    expect(store.resolved).toBe("dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("restores the saved mode", () => {
    localStorage.setItem(THEME_STORAGE_KEY, "dark");
    const store = freshStore();
    expect(store.mode).toBe("dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("falls back to light when the saved value is not a mode", () => {
    localStorage.setItem(THEME_STORAGE_KEY, "blue");
    expect(freshStore().mode).toBe("light");
  });

  it("does not throw when storage reads and writes fail", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const store = freshStore();
    expect(store.mode).toBe("light");
    expect(() => store.cycle()).not.toThrow();
    expect(store.mode).toBe("dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });
});
