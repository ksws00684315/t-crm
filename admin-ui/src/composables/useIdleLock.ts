import { onBeforeUnmount, onMounted, watch } from "vue";
import { useLockStore } from "@/stores/lock";
import {
  isIdle,
  loadLastActive,
  LOCK_KEY,
  LOCK_LAST_ACTIVE_KEY,
  maybeSaveLastActive,
  saveLastActive
} from "@/utils/lock";

const ACTIVITY_EVENTS = ["pointerdown", "pointermove", "keydown", "wheel", "touchstart"] as const;
const CHECK_INTERVAL_MS = 15_000;

/**
 * Locks the screen after the configured inactivity (layout only).
 * lastActive is shared via localStorage (throttled writes) so a background tab that has gone idle
 * does not lock the screen while another tab is still being used.
 *
 * Unlock (this tab or via storage from another tab) resets lastActive immediately and bypasses the
 * write throttle, otherwise the next check still sees the pre-lock timestamp and re-locks.
 */
export function useIdleLock(): void {
  const lock = useLockStore();
  let lastActive = loadLastActive();
  let lastWrite = 0;
  let timer: ReturnType<typeof setInterval> | null = null;

  function touch(): void {
    if (lock.locked) return;
    const now = Date.now();
    lastActive = now;
    lastWrite = maybeSaveLastActive(now, lastWrite);
  }

  function check(): void {
    if (lock.locked) return;
    const shared = loadLastActive();
    if (shared > lastActive) lastActive = shared;
    if (isIdle(lastActive, Date.now(), lock.idleMinutes)) lock.lock();
  }

  /** After unlock, treat "now" as activity so the next check does not re-lock from the stale stamp. */
  function resetLastActiveOnUnlock(): void {
    const now = Date.now();
    lastActive = now;
    saveLastActive(now);
    lastWrite = now;
  }

  function onStorage(event: StorageEvent): void {
    if (event.key === null || event.key === LOCK_KEY) {
      lock.syncFromStorage();
    }
    if (event.key === LOCK_LAST_ACTIVE_KEY && event.newValue) {
      const shared = Number(event.newValue);
      if (Number.isFinite(shared) && shared > lastActive) lastActive = shared;
    }
  }

  watch(
    () => lock.locked,
    (locked, wasLocked) => {
      if (wasLocked === true && locked === false) resetLastActiveOnUnlock();
    }
  );

  onMounted(() => {
    lock.syncFromStorage();
    // Announce that this tab is active so a sibling that just woke up does not race-lock.
    touch();
    for (const name of ACTIVITY_EVENTS) window.addEventListener(name, touch, { passive: true });
    window.addEventListener("storage", onStorage);
    timer = setInterval(check, CHECK_INTERVAL_MS);
  });

  onBeforeUnmount(() => {
    for (const name of ACTIVITY_EVENTS) window.removeEventListener(name, touch);
    window.removeEventListener("storage", onStorage);
    if (timer != null) clearInterval(timer);
  });
}
