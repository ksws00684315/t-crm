import { defineStore } from "pinia";
import { ref } from "vue";
import {
  loadIdleMinutes,
  loadLocked,
  saveIdleMinutes,
  saveLocked,
  type LockIdleMinutes
} from "@/utils/lock";

/**
 * Screen lock. Locking keeps the token, tabs and page state; it only hides the workspace until the
 * password (and captcha, ADR-017) is entered again. Signing out clears it.
 */
export const useLockStore = defineStore("lock", () => {
  const locked = ref(loadLocked());
  const idleMinutes = ref<LockIdleMinutes>(loadIdleMinutes());

  function lock(): void {
    locked.value = true;
    saveLocked(true);
  }

  function unlock(): void {
    locked.value = false;
    saveLocked(false);
  }

  function setIdleMinutes(minutes: LockIdleMinutes): void {
    idleMinutes.value = minutes;
    saveIdleMinutes(minutes);
  }

  /** Another tab locked or unlocked (storage event). */
  function syncFromStorage(): void {
    locked.value = loadLocked();
  }

  return { locked, idleMinutes, lock, unlock, setIdleMinutes, syncFromStorage };
});
