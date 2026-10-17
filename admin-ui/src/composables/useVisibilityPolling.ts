import { onBeforeUnmount, onMounted } from "vue";
import { NOTIFICATION_POLL_MS } from "@/utils/notification";

/**
 * Calls `load` immediately, then on a fixed interval. The interval pauses while the tab is hidden
 * and runs once as soon as the tab is visible again.
 */
export function useVisibilityPolling(
  load: () => void | Promise<void>,
  intervalMs = NOTIFICATION_POLL_MS
): void {
  let timer: ReturnType<typeof setInterval> | undefined;

  function run() {
    void Promise.resolve(load()).catch(() => undefined);
  }

  function stop() {
    if (timer !== undefined) {
      clearInterval(timer);
      timer = undefined;
    }
  }

  function start() {
    stop();
    timer = setInterval(run, intervalMs);
  }

  function onVisibility() {
    if (document.visibilityState === "hidden") {
      stop();
      return;
    }
    run();
    start();
  }

  onMounted(() => {
    run();
    start();
    document.addEventListener("visibilitychange", onVisibility);
  });

  onBeforeUnmount(() => {
    stop();
    document.removeEventListener("visibilitychange", onVisibility);
  });
}
