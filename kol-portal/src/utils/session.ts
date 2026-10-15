import type { KolProfile } from "@/api/auth";

/** What survives a page reload. */
export interface StoredSession {
  token: string;
  kol?: KolProfile;
  /** Epoch milliseconds after which the token is considered expired locally. */
  expiresAt: number;
}

export const SESSION_KEY = "tcrm-kol-session";

export function saveSession(session: StoredSession): void {
  localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearStoredSession(): void {
  localStorage.removeItem(SESSION_KEY);
}

/** Returns the stored session, or null when absent, malformed or expired (and removes it then). */
export function loadSession(now: number = Date.now()): StoredSession | null {
  const raw = localStorage.getItem(SESSION_KEY);
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (isStoredSession(value) && value.expiresAt > now) return value;
  } catch {
    // fall through: unreadable data is treated as signed out
  }
  clearStoredSession();
  return null;
}

function isStoredSession(value: unknown): value is StoredSession {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Partial<StoredSession>;
  return (
    typeof candidate.token === "string" &&
    candidate.token.length > 0 &&
    typeof candidate.expiresAt === "number"
  );
}
