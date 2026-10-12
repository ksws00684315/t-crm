import { beforeEach, describe, expect, it } from "vitest";
import {
  LOCK_IDLE_KEY,
  isIdle,
  loadIdleMinutes,
  loadLocked,
  saveIdleMinutes,
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

  it("persists the lock flag and idle choice, rejecting unknown values", () => {
    saveLocked(true);
    expect(loadLocked()).toBe(true);
    saveLocked(false);
    expect(loadLocked()).toBe(false);
    saveIdleMinutes(15);
    expect(loadIdleMinutes()).toBe(15);
    localStorage.setItem(LOCK_IDLE_KEY, "7");
    expect(loadIdleMinutes()).toBe(0);
  });
});
