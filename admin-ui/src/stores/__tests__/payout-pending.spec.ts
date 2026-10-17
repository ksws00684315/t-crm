import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/commission/payout-account";
import { usePayoutPendingStore } from "../payout-pending";
import { PENDING_POLL_MS } from "../payout-pending";

vi.mock("@/api/commission/payout-account", () => ({
  listPayoutAccounts: vi.fn(),
  fetchPayoutPendingCount: vi.fn(),
  getPayoutAccount: vi.fn(),
  reviewPayoutAccount: vi.fn(),
  downloadPayoutQr: vi.fn()
}));

const reviewState = vi.hoisted(() => ({ allowed: false }));

vi.mock("@/stores/user", () => ({
  useUserStore: () => ({
    user: {
      role: "STAFF",
      dataScope: "OWN",
      permissions: reviewState.allowed ? ["payout-account:review"] : ["commission:view"]
    }
  })
}));

function setHidden(hidden: boolean): void {
  vi.spyOn(Document.prototype, "hidden", "get").mockReturnValue(hidden);
}

beforeEach(() => {
  vi.useFakeTimers();
  localStorage.clear();
  setActivePinia(createPinia());
  vi.clearAllMocks();
  setHidden(false);
});

afterEach(() => {
  const store = usePayoutPendingStore();
  store.stop();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("payout pending store", () => {
  it("fetches the count for a reviewer on start and on the 60s tick", async () => {
    reviewState.allowed = true;
    vi.mocked(api.fetchPayoutPendingCount).mockResolvedValue(2);
    const store = usePayoutPendingStore();
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(api.fetchPayoutPendingCount).toHaveBeenCalledTimes(1);
    expect(store.count).toBe(2);

    await vi.advanceTimersByTimeAsync(PENDING_POLL_MS);
    expect(api.fetchPayoutPendingCount).toHaveBeenCalledTimes(2);
  });

  it("does not poll or fetch for an account without the review permission", async () => {
    reviewState.allowed = false;
    const store = usePayoutPendingStore();
    store.start();
    await vi.advanceTimersByTimeAsync(PENDING_POLL_MS * 3);
    expect(api.fetchPayoutPendingCount).not.toHaveBeenCalled();
    expect(store.count).toBe(0);
  });

  it("skips ticks while the browser tab is hidden and catches up when visible", async () => {
    reviewState.allowed = true;
    vi.mocked(api.fetchPayoutPendingCount).mockResolvedValue(1);
    const store = usePayoutPendingStore();
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(api.fetchPayoutPendingCount).toHaveBeenCalledTimes(1);

    setHidden(true);
    await vi.advanceTimersByTimeAsync(PENDING_POLL_MS * 2);
    // Hidden: the ticks fire but must not ask the backend anything.
    expect(api.fetchPayoutPendingCount).toHaveBeenCalledTimes(1);

    // Becoming visible again catches up immediately.
    setHidden(false);
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(0);
    expect(api.fetchPayoutPendingCount).toHaveBeenCalledTimes(2);
  });

  it("keeps the previous count when a fetch fails", async () => {
    reviewState.allowed = true;
    vi.mocked(api.fetchPayoutPendingCount)
      .mockResolvedValueOnce(3)
      .mockRejectedValueOnce(new Error("network"));
    const store = usePayoutPendingStore();
    store.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(store.count).toBe(3);

    await store.refresh();
    expect(store.count).toBe(3);
  });

  it("refresh() is the manual hook for entering the page and finishing a review", async () => {
    reviewState.allowed = true;
    vi.mocked(api.fetchPayoutPendingCount).mockResolvedValue(5);
    const store = usePayoutPendingStore();
    await store.refresh();
    expect(store.count).toBe(5);
    expect(api.fetchPayoutPendingCount).toHaveBeenCalledTimes(1);
  });

  it("stop() clears the timer, the listener and the count", async () => {
    reviewState.allowed = true;
    vi.mocked(api.fetchPayoutPendingCount).mockResolvedValue(1);
    const store = usePayoutPendingStore();
    store.start();
    await vi.advanceTimersByTimeAsync(0);

    store.stop();
    expect(store.count).toBe(0);
    await vi.advanceTimersByTimeAsync(PENDING_POLL_MS * 2);
    expect(api.fetchPayoutPendingCount).toHaveBeenCalledTimes(1);

    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(0);
    expect(api.fetchPayoutPendingCount).toHaveBeenCalledTimes(1);
  });
});
