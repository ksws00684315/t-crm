import { onBeforeUnmount, onMounted } from "vue";
import { useLockStore } from "@/stores/lock";
import { isIdle, LOCK_KEY } from "@/utils/lock";

const ACTIVITY_EVENTS = ["pointerdown", "pointermove", "keydown", "wheel", "touchstart"] as const;
const CHECK_INTERVAL_MS = 15_000;

/** Locks the screen after the configured inactivity (layout only). Also follows other tabs. */
export function useIdleLock(): void {
  const lock = useLockStore();
  let lastActive = Date.now();
  let timer: ReturnType<typeof setInterval> | null = null;

  function touch(): void {
    if (!lock.locked) lastActive = Date.now();
  }

  function check(): void {
    if (!lock.locked && isIdle(lastActive, Date.now(), lock.idleMinutes)) lock.lock();
  }

  function onStorage(event: StorageEvent): void {
    if (event.key === null || event.key === LOCK_KEY) {
      lock.syncFromStorage();
      lastActive = Date.now();
    }
  }

  onMounted(() => {
    lock.syncFromStorage();
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
