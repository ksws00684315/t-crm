/** Light, dark, or follow the operating system. Default is light. */
export const THEME_MODES = ["light", "dark", "system"] as const;
export type ThemeMode = (typeof THEME_MODES)[number];
export type ResolvedTheme = "light" | "dark";

export const THEME_STORAGE_KEY = "tcrm.admin.theme";

const SYSTEM_QUERY = "(prefers-color-scheme: dark)";

export function isThemeMode(value: unknown): value is ThemeMode {
  return value === "light" || value === "dark" || value === "system";
}

/** Saved choice, or light when nothing valid is stored or storage throws. */
export function loadStoredTheme(): ThemeMode {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return isThemeMode(stored) ? stored : "light";
  } catch {
    return "light";
  }
}

/** Persists the choice. A storage failure leaves the in-memory choice in place. */
export function saveStoredTheme(mode: ThemeMode): void {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, mode);
  } catch {
    // unavailable storage is not an error: the choice lasts until reload
  }
}

/** light → dark → system → light. */
export function nextTheme(mode: ThemeMode): ThemeMode {
  if (mode === "light") return "dark";
  if (mode === "dark") return "system";
  return "light";
}

export function resolveTheme(mode: ThemeMode, prefersDark: boolean): ResolvedTheme {
  if (mode === "system") return prefersDark ? "dark" : "light";
  return mode;
}

/** Element Plus dark variables apply when <html> has the `dark` class. */
export function applyThemeClass(resolved: ResolvedTheme): void {
  document.documentElement.classList.toggle("dark", resolved === "dark");
}

export function readPrefersDark(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return false;
  return window.matchMedia(SYSTEM_QUERY).matches;
}

/**
 * Listens for OS theme changes. Returns an unsubscribe function. Calls `onChange` once with the
 * current value when matchMedia exists.
 */
export function subscribeSystemTheme(onChange: (prefersDark: boolean) => void): () => void {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return () => undefined;
  }
  const media = window.matchMedia(SYSTEM_QUERY);
  const listener = (event: MediaQueryListEvent) => onChange(event.matches);
  media.addEventListener("change", listener);
  onChange(media.matches);
  return () => media.removeEventListener("change", listener);
}
