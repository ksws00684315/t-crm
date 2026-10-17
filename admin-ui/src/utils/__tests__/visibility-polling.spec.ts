import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createVisibilityPoller } from "../visibility-polling";

const INTERVAL = 1_000;

function setHidden(hidden: boolean): void {
  vi.spyOn(Document.prototype, "hidden", "get").mockReturnValue(hidden);
  vi.spyOn(Document.prototype, "visibilityState", "get").mockReturnValue(
    hidden ? "hidden" : "visible"
  );
}

beforeEach(() => {
  vi.useFakeTimers();
  setHidden(false);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("createVisibilityPoller", () => {
  it("runs the load once right away when started", async () => {
    const load = vi.fn();
    const poller = createVisibilityPoller(load, INTERVAL);
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(load).toHaveBeenCalledTimes(1);
    poller.stop();
  });

  it("runs the load on every interval tick", async () => {
    const load = vi.fn();
    const poller = createVisibilityPoller(load, INTERVAL);
    poller.start();
    await vi.advanceTimersByTimeAsync(INTERVAL * 3);
    expect(load).toHaveBeenCalledTimes(4);
    poller.stop();
  });

  it("stops ticking while the tab is hidden and catches up when it becomes visible", async () => {
    const load = vi.fn();
    const poller = createVisibilityPoller(load, INTERVAL);
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(load).toHaveBeenCalledTimes(1);

    setHidden(true);
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(INTERVAL * 5);
    expect(load).toHaveBeenCalledTimes(1);

    setHidden(false);
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(0);
    expect(load).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it("skips a tick that fires while hidden even without a visibilitychange event", async () => {
    const load = vi.fn();
    const poller = createVisibilityPoller(load, INTERVAL);
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    // A missed event must not turn into background requests.
    setHidden(true);
    await vi.advanceTimersByTimeAsync(INTERVAL * 3);
    expect(load).toHaveBeenCalledTimes(1);
    poller.stop();
  });

  it("resumes the interval after the tab is visible again", async () => {
    const load = vi.fn();
    const poller = createVisibilityPoller(load, INTERVAL);
    poller.start();
    setHidden(true);
    document.dispatchEvent(new Event("visibilitychange"));
    setHidden(false);
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(0);
    expect(load).toHaveBeenCalledTimes(2);

    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(load).toHaveBeenCalledTimes(3);
    poller.stop();
  });

  it("does not run again after stop", async () => {
    const load = vi.fn();
    const poller = createVisibilityPoller(load, INTERVAL);
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    poller.stop();

    await vi.advanceTimersByTimeAsync(INTERVAL * 3);
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(0);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("keeps one schedule when started twice and can restart after stop", async () => {
    const load = vi.fn();
    const poller = createVisibilityPoller(load, INTERVAL);
    poller.start();
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(load).toHaveBeenCalledTimes(1);

    poller.stop();
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(load).toHaveBeenCalledTimes(2);

    await vi.advanceTimersByTimeAsync(INTERVAL);
    // One interval only: two live schedules would have called load twice per tick.
    expect(load).toHaveBeenCalledTimes(3);
    poller.stop();
  });

  it("swallows load failures and keeps polling", async () => {
    const load = vi.fn().mockRejectedValue(new Error("network"));
    const poller = createVisibilityPoller(load, INTERVAL);
    poller.start();
    await vi.advanceTimersByTimeAsync(INTERVAL);
    expect(load).toHaveBeenCalledTimes(2);
    poller.stop();
  });
});
