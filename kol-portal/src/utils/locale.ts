/** Languages the portal ships; every UI string must exist in both (checked by tests). */
export const SUPPORTED_LOCALES = ["en-US", "zh-CN"] as const;
export type LocaleId = (typeof SUPPORTED_LOCALES)[number];

/** The portal's default and fallback language (ADR-015: creator portal defaults to English). */
export const DEFAULT_LOCALE: LocaleId = "en-US";

export const LOCALE_STORAGE_KEY = "tcrm.kol.locale";

export function isSupportedLocale(value: unknown): value is LocaleId {
  return typeof value === "string" && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

/**
 * The saved language, or the default when nothing (valid) was saved. Storage access is wrapped in
 * try/catch: when localStorage is unavailable (blocked cookies, quota, ...) we fall back silently.
 */
export function loadStoredLocale(): LocaleId {
  try {
    const stored = localStorage.getItem(LOCALE_STORAGE_KEY);
    return isSupportedLocale(stored) ? stored : DEFAULT_LOCALE;
  } catch {
    return DEFAULT_LOCALE;
  }
}

/** Persists the choice; ignores storage failures so switching still works for the current session. */
export function saveStoredLocale(locale: LocaleId): void {
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, locale);
  } catch {
    // unavailable storage is not an error: the choice then lasts until the page is reloaded
  }
}
