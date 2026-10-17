import { defineStore } from "pinia";
import { ref } from "vue";
import { fetchPayoutPendingCount } from "@/api/commission/payout-account";
import { useUserStore } from "@/stores/user";
import { accessOf } from "@/utils/permission";

/** Menu-badge polling interval (task 15b §4.2.5). */
export const PENDING_POLL_MS = 60_000;

/**
 * Pending payout-account reviews for the menu badge. Polling runs while the layout is mounted and
 * the account holds `payout-account:review`; a hidden browser tab skips ticks and catches up when
 * it becomes visible again. Failures keep the last count so a hiccup does not clear the badge.
 */
export const usePayoutPendingStore = defineStore("payoutPending", () => {
  const count = ref(0);
  let timer: ReturnType<typeof setInterval> | null = null;
  let watchingVisibility = false;

  /** The user object, not the session helper: pages read permissions the same way. */
  function enabled(): boolean {
    return accessOf(useUserStore().user).can("payout-account:review");
  }

  async function fetchCount(): Promise<void> {
    if (!enabled()) return;
    try {
      count.value = await fetchPayoutPendingCount();
    } catch {
      // Keep the previous count; the next tick or manual refresh retries.
    }
  }

  function onVisibilityChange(): void {
    if (!document.hidden) void fetchCount();
  }

  function start(): void {
    if (timer) return;
    void fetchCount();
    timer = setInterval(() => {
      // A hidden tab pauses polling instead of firing requests nobody sees.
      if (!document.hidden) void fetchCount();
    }, PENDING_POLL_MS);
    if (!watchingVisibility) {
      document.addEventListener("visibilitychange", onVisibilityChange);
      watchingVisibility = true;
    }
  }

  function stop(): void {
    if (timer) clearInterval(timer);
    timer = null;
    if (watchingVisibility) {
      document.removeEventListener("visibilitychange", onVisibilityChange);
      watchingVisibility = false;
    }
    count.value = 0;
  }

  /** Immediate refresh: entering the review page and finishing a review. */
  function refresh(): Promise<void> {
    return fetchCount();
  }

  return { count, start, stop, refresh };
});
