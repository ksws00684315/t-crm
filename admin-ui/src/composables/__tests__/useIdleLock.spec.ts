import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { defineComponent, nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useIdleLock } from "../useIdleLock";
import { useLockStore } from "@/stores/lock";
import {
  LOCK_KEY,
  LOCK_LAST_ACTIVE_KEY,
  loadLastActive,
  saveIdleMinutes,
  saveLastActive,
  saveLocked
} from "@/utils/lock";

const Host = defineComponent({
  setup() {
    useIdleLock();
    return {};
  },
  template: "<div />"
});

describe("useIdleLock", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
    setActivePinia(createPinia());
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("does not re-lock after unlock when there are no further activity events", async () => {
    const start = 10_000_000;
    vi.setSystemTime(start);
    // Auto-lock after 5 minutes. Mount will touch() once; then we age past the idle window.
    saveIdleMinutes(5);
    saveLocked(false);

    const wrapper = mount(Host);
    await flushPromises();
    const lock = useLockStore();
    expect(lock.idleMinutes).toBe(5);
    expect(lock.locked).toBe(false);

    // 5 minutes of inactivity (no pointer/keyboard events) → auto lock.
    await vi.advanceTimersByTimeAsync(5 * 60_000);
    expect(lock.locked).toBe(true);
    expect(localStorage.getItem(LOCK_KEY)).toBe("1");

    // Unlock without any activity events (keyboard unlock path does not call touch).
    lock.unlock();
    await nextTick();
    expect(lock.locked).toBe(false);
    expect(loadLastActive()).toBe(Date.now());

    // Next check period with no further activity must not re-lock.
    await vi.advanceTimersByTimeAsync(15_000);
    expect(lock.locked).toBe(false);
    expect(localStorage.getItem(LOCK_KEY)).toBeNull();

    wrapper.unmount();
  });

  it("resets lastActive when another tab unlocks via storage", async () => {
    const now = 20_000_000;
    vi.setSystemTime(now);
    saveIdleMinutes(5);
    saveLastActive(now - 6 * 60_000);
    saveLocked(true);

    const wrapper = mount(Host);
    await flushPromises();
    const lock = useLockStore();
    expect(lock.locked).toBe(true);

    // Sibling tab unlocks: remove lock flag (same as saveLocked(false)).
    localStorage.removeItem(LOCK_KEY);
    window.dispatchEvent(
      new StorageEvent("storage", { key: LOCK_KEY, oldValue: "1", newValue: null })
    );
    await nextTick();

    expect(lock.locked).toBe(false);
    expect(Number(localStorage.getItem(LOCK_LAST_ACTIVE_KEY))).toBe(Date.now());

    await vi.advanceTimersByTimeAsync(15_000);
    expect(lock.locked).toBe(false);

    wrapper.unmount();
  });
});
