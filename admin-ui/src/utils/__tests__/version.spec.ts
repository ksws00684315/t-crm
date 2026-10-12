import { afterEach, describe, expect, it, vi } from "vitest";
import {
  fetchRemoteVersion,
  monitorVersion,
  readSnoozeUntil,
  shouldPrompt,
  VERSION_CHECK_MS,
  VERSION_SNOOZE_KEY,
  VERSION_SNOOZE_MS,
  writeSnoozeUntil
} from "../version";

const base = {
  dev: false,
  current: "build-a",
  remote: "build-b" as string | null,
  now: 1_000,
  snoozeUntil: 0
};

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe("shouldPrompt", () => {
  it("prompts when the published version differs", () => {
    expect(shouldPrompt(base)).toBe(true);
  });

  it("stays quiet when the version matches or the check fails", () => {
    expect(shouldPrompt({ ...base, remote: "build-a" })).toBe(false);
    expect(shouldPrompt({ ...base, remote: null })).toBe(false);
  });

  it("does not check in development", () => {
    expect(shouldPrompt({ ...base, dev: true })).toBe(false);
  });

  it("stays quiet for 30 minutes after later, then prompts again", () => {
    const now = 1_700_000_000_000;
    const snoozeUntil = now + VERSION_SNOOZE_MS;
    expect(VERSION_SNOOZE_MS).toBe(30 * 60 * 1000);
    expect(shouldPrompt({ ...base, now, snoozeUntil })).toBe(false);
    expect(shouldPrompt({ ...base, now: snoozeUntil - 1, snoozeUntil })).toBe(false);
    expect(shouldPrompt({ ...base, now: snoozeUntil, snoozeUntil })).toBe(true);
  });
});

describe("monitorVersion", () => {
  it("does not call the network in development", () => {
    const fetchRemote = vi.fn();
    const stop = monitorVersion({
      dev: true,
      current: "build-a",
      now: () => 0,
      fetchRemote,
      readSnooze: () => 0,
      onPrompt: vi.fn()
    });
    stop();
    expect(fetchRemote).not.toHaveBeenCalled();
  });

  it("prompts on load, on a 5-minute interval, and when the page becomes visible", async () => {
    vi.useFakeTimers();
    const fetchRemote = vi.fn().mockResolvedValue("build-b");
    const onPrompt = vi.fn();
    const stop = monitorVersion({
      dev: false,
      current: "build-a",
      now: () => 1_000,
      fetchRemote,
      readSnooze: () => 0,
      onPrompt
    });
    await Promise.resolve();
    await Promise.resolve();
    expect(fetchRemote).toHaveBeenCalledTimes(1);
    expect(onPrompt).toHaveBeenCalledTimes(1);

    let visible = false;
    vi.spyOn(document, "visibilityState", "get").mockImplementation(() =>
      visible ? "visible" : "hidden"
    );
    document.dispatchEvent(new Event("visibilitychange"));
    await Promise.resolve();
    expect(fetchRemote).toHaveBeenCalledTimes(1);

    visible = true;
    document.dispatchEvent(new Event("visibilitychange"));
    await Promise.resolve();
    await Promise.resolve();
    expect(fetchRemote).toHaveBeenCalledTimes(2);

    await vi.advanceTimersByTimeAsync(VERSION_CHECK_MS);
    await Promise.resolve();
    expect(fetchRemote).toHaveBeenCalledTimes(3);
    expect(VERSION_CHECK_MS).toBe(5 * 60 * 1000);

    stop();
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(VERSION_CHECK_MS);
    expect(fetchRemote).toHaveBeenCalledTimes(3);
  });

  it("does not prompt again while the 30-minute snooze is active", async () => {
    const now = 5_000;
    const fetchRemote = vi.fn().mockResolvedValue("build-b");
    const onPrompt = vi.fn();
    const stop = monitorVersion({
      dev: false,
      current: "build-a",
      now: () => now,
      fetchRemote,
      readSnooze: () => now + VERSION_SNOOZE_MS,
      onPrompt
    });
    await Promise.resolve();
    await Promise.resolve();
    expect(fetchRemote).toHaveBeenCalledTimes(1);
    expect(onPrompt).not.toHaveBeenCalled();
    stop();
  });
});

describe("fetchRemoteVersion", () => {
  it("requests version.json with a cache-busting query and no-store", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ version: "build-b" })
    });
    vi.stubGlobal("fetch", fetchMock);
    await expect(fetchRemoteVersion(42)).resolves.toBe("build-b");
    expect(fetchMock).toHaveBeenCalledWith("/version.json?t=42", { cache: "no-store" });
  });

  it("returns null when the response is not a version string", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: true, json: async () => ({ version: 1 }) })
    );
    await expect(fetchRemoteVersion(1)).resolves.toBeNull();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, json: async () => ({}) }));
    await expect(fetchRemoteVersion(1)).resolves.toBeNull();

    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    await expect(fetchRemoteVersion(1)).resolves.toBeNull();
  });
});

describe("version snooze storage", () => {
  it("remembers the snooze time and ignores storage failures", () => {
    writeSnoozeUntil(1234);
    expect(localStorage.getItem(VERSION_SNOOZE_KEY)).toBe("1234");
    expect(readSnoozeUntil()).toBe(1234);

    localStorage.setItem(VERSION_SNOOZE_KEY, "nope");
    expect(readSnoozeUntil()).toBe(0);

    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(readSnoozeUntil()).toBe(0);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(() => writeSnoozeUntil(9)).not.toThrow();
  });
});
