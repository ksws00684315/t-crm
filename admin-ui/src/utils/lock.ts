/** Screen lock persistence (research report P0-3). Survives reloads so F5 cannot skip the lock. */
export const LOCK_KEY = "tcrm-admin-locked";
export const LOCK_IDLE_KEY = "tcrm-admin-lock-idle";
/** Minutes of inactivity before auto lock; 0 turns auto lock off. */
export const LOCK_IDLE_OPTIONS = [0, 5, 15, 30, 60] as const;
export type LockIdleMinutes = (typeof LOCK_IDLE_OPTIONS)[number];
export const DEFAULT_LOCK_IDLE: LockIdleMinutes = 0;

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

/** True when the user has been inactive for at least `minutes` (never when auto lock is off). */
export function isIdle(lastActive: number, now: number, minutes: number): boolean {
  return minutes > 0 && now - lastActive >= minutes * 60_000;
}
