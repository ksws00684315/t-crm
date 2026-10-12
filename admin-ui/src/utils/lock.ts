/**
 * Screen lock persistence (research report P0-3). Survives reloads so F5 cannot skip the lock.
 * Keys use the `tcrm.admin.*` prefix so they sit with other admin preferences.
 */
export const LOCK_KEY = "tcrm.admin.locked";
export const LOCK_IDLE_KEY = "tcrm.admin.lock-idle";
/** Shared across tabs so an idle background tab does not lock while another tab is active. */
export const LOCK_LAST_ACTIVE_KEY = "tcrm.admin.lock-last-active";
/** Minutes of inactivity before auto lock; 0 turns auto lock off. */
export const LOCK_IDLE_OPTIONS = [0, 5, 15, 30, 60] as const;
export type LockIdleMinutes = (typeof LOCK_IDLE_OPTIONS)[number];
export const DEFAULT_LOCK_IDLE: LockIdleMinutes = 0;
/** How often a tab may write last-active to localStorage (storage events are cheap enough either way). */
export const LAST_ACTIVE_WRITE_THROTTLE_MS = 30_000;

export function loadLocked(): boolean {
  try {
    return localStorage.getItem(LOCK_KEY) === "1";
  } catch {
    return false;
  }
}

export function saveLocked(locked: boolean): void {
  try {
    if (locked) localStorage.setItem(LOCK_KEY, "1");
    else localStorage.removeItem(LOCK_KEY);
  } catch {
    // storage unavailable: the lock still holds for this page view
  }
}

export function loadIdleMinutes(): LockIdleMinutes {
  try {
    const value = Number(localStorage.getItem(LOCK_IDLE_KEY));
    return (LOCK_IDLE_OPTIONS as readonly number[]).includes(value)
      ? (value as LockIdleMinutes)
      : DEFAULT_LOCK_IDLE;
  } catch {
    return DEFAULT_LOCK_IDLE;
  }
}

export function saveIdleMinutes(minutes: LockIdleMinutes): void {
  try {
    localStorage.setItem(LOCK_IDLE_KEY, String(minutes));
  } catch {
    // ignore: the choice lasts until reload
  }
}

export function loadLastActive(now: number = Date.now()): number {
  try {
    const value = Number(localStorage.getItem(LOCK_LAST_ACTIVE_KEY));
    return Number.isFinite(value) && value > 0 ? value : now;
  } catch {
    return now;
  }
}

export function saveLastActive(timestamp: number): void {
  try {
    localStorage.setItem(LOCK_LAST_ACTIVE_KEY, String(timestamp));
  } catch {
    // ignore
  }
}

/**
 * Writes `timestamp` only when at least `throttleMs` have passed since `lastWrite`, so busy tabs
 * do not hammer storage. Returns the write time to keep (unchanged when throttled).
 */
export function maybeSaveLastActive(
  timestamp: number,
  lastWrite: number,
  throttleMs: number = LAST_ACTIVE_WRITE_THROTTLE_MS
): number {
  if (timestamp - lastWrite < throttleMs) return lastWrite;
  saveLastActive(timestamp);
  return timestamp;
}

/** True when the user has been inactive for at least `minutes` (never when auto lock is off). */
export function isIdle(lastActive: number, now: number, minutes: number): boolean {
  return minutes > 0 && now - lastActive >= minutes * 60_000;
}
