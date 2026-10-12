import { beforeEach, describe, expect, it } from "vitest";
import {
  LOCK_IDLE_KEY,
  LOCK_KEY,
  LOCK_LAST_ACTIVE_KEY,
  isIdle,
  loadIdleMinutes,
  loadLastActive,
  loadLocked,
  maybeSaveLastActive,
  saveIdleMinutes,
  saveLastActive,
  saveLocked
} from "../lock";

describe("lock utils", () => {
  beforeEach(() => localStorage.clear());

  it("never reports idle when auto lock is off", () => {
    expect(isIdle(0, 10 * 60 * 60_000, 0)).toBe(false);
  });

  it("reports idle once the minutes have passed", () => {
    expect(isIdle(0, 5 * 60_000 - 1, 5)).toBe(false);
    expect(isIdle(0, 5 * 60_000, 5)).toBe(true);
  });

  it("persists the lock flag and idle choice under tcrm.admin.* keys", () => {
    saveLocked(true);
    expect(localStorage.getItem(LOCK_KEY)).toBe("1");
    expect(loadLocked()).toBe(true);
    saveLocked(false);
    expect(loadLocked()).toBe(false);
    saveIdleMinutes(15);
    expect(localStorage.getItem(LOCK_IDLE_KEY)).toBe("15");
    expect(loadIdleMinutes()).toBe(15);
    localStorage.setItem(LOCK_IDLE_KEY, "7");
    expect(loadIdleMinutes()).toBe(0);
  });

  it("throttles last-active writes and shares them across tabs", () => {
    const t0 = 1_000_000;
    expect(maybeSaveLastActive(t0, 0, 30_000)).toBe(t0);
    expect(localStorage.getItem(LOCK_LAST_ACTIVE_KEY)).toBe(String(t0));
    // Within the throttle window the storage value stays put.
    expect(maybeSaveLastActive(t0 + 10_000, t0, 30_000)).toBe(t0);
    expect(localStorage.getItem(LOCK_LAST_ACTIVE_KEY)).toBe(String(t0));
    expect(maybeSaveLastActive(t0 + 30_000, t0, 30_000)).toBe(t0 + 30_000);
    expect(loadLastActive()).toBe(t0 + 30_000);
  });

  it("does not lock when a sibling tab is still active (shared lastActive)", () => {
    const now = 10_000_000;
    const idleTabLocal = now - 20 * 60_000; // this tab has been idle for 20 minutes
    saveLastActive(now - 60_000); // another tab touched shared state 1 minute ago
    const effective = Math.max(idleTabLocal, loadLastActive(now));
    expect(isIdle(effective, now, 5)).toBe(false);
  });

  it("does lock when every tab has been idle long enough", () => {
    const now = 10_000_000;
    const idleTabLocal = now - 20 * 60_000;
    saveLastActive(now - 10 * 60_000);
    const effective = Math.max(idleTabLocal, loadLastActive(now));
    expect(isIdle(effective, now, 5)).toBe(true);
  });
});
