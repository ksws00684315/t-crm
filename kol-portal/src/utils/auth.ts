/** Passwords must match the backend's printable ASCII and BCrypt length constraints. */
export function validPassword(value: string): boolean {
  return /^[\x20-\x7e]{10,72}$/.test(value);
}

export function validEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
}

/** Accept only a single absolute in-app path; reject URL authority and encoded separators. */
export function safeRedirect(value: unknown): string {
  if (typeof value !== "string" || !value.startsWith("/")) return "/";
  try {
    const decoded = decodeURIComponent(value);
    if (
      [...decoded].some(char => char.charCodeAt(0) <= 32 || char === "\\") ||
      decoded.startsWith("//")
    )
      return "/";
    const path = decoded.split(/[?#]/)[0];
    if (["/login", "/activate", "/forgot", "/reset"].includes(path)) return "/";
    return value;
  } catch {
    return "/";
  }
}

/** Remote profile links may open only ordinary web URLs. */
export function safeSheetUrl(value: string | null | undefined): string | undefined {
  if (!value) return undefined;
  try {
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) ? url.href : undefined;
  } catch {
    return undefined;
  }
}
