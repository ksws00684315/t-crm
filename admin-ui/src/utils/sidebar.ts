/** Desktop collapse preference. A narrow viewport uses a drawer and does not overwrite this. */
export const SIDEBAR_STORAGE_KEY = "tcrm.admin.sidebar";

/** Matches the prompt: viewports under 992px become a drawer. */
export const SIDEBAR_NARROW_QUERY = "(max-width: 991px)";

export function loadSidebarCollapsed(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

export function saveSidebarCollapsed(collapsed: boolean): void {
  try {
    localStorage.setItem(SIDEBAR_STORAGE_KEY, collapsed ? "1" : "0");
  } catch {
    // the in-memory choice still applies until reload
  }
}

export function isMacPlatform(): boolean {
  if (typeof navigator === "undefined") return false;
  const platform = navigator.platform || "";
  const agent = navigator.userAgent || "";
  return /Mac|iPhone|iPad/i.test(platform) || /Mac OS/i.test(agent);
}

/** Subscribes to the narrow-viewport query. Calls `onChange` once with the current match. */
export function subscribeNarrow(onChange: (matches: boolean) => void): () => void {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    onChange(false);
    return () => undefined;
  }
  const media = window.matchMedia(SIDEBAR_NARROW_QUERY);
  const listener = (event: MediaQueryListEvent) => onChange(event.matches);
  media.addEventListener("change", listener);
  onChange(media.matches);
  return () => media.removeEventListener("change", listener);
}
