import { onBeforeUnmount, onMounted } from "vue";
import { NOTIFICATION_POLL_MS } from "@/utils/notification";
import { createVisibilityPoller } from "@/utils/visibility-polling";

/**
 * Calls `load` immediately, then on a fixed interval. The interval pauses while the tab is hidden
 * and runs once as soon as the tab is visible again. Component-bound wrapper around
 * {@link createVisibilityPoller}; stores and other non-component callers use that directly.
 */
export function useVisibilityPolling(
  load: () => void | Promise<void>,
  intervalMs = NOTIFICATION_POLL_MS
): void {
  const poller = createVisibilityPoller(load, intervalMs);

  onMounted(() => poller.start());

  onBeforeUnmount(() => poller.stop());
}
