/**
 * Interval polling that follows tab visibility, without any component lifecycle. One instance
 * backs the notification bell (through `useVisibilityPolling`) (task 24 §4.1).
 *
 * Behavior: `start` runs `load` once right away and then every `intervalMs`; a hidden tab stops
 * the timer (and a tick that fires anyway, say after a missed event, is skipped); becoming
 * visible runs `load` once at once and resumes the interval. `load` errors are swallowed so a
 * failed request never breaks the schedule. `start` is a no-op while already running.
 */
export interface VisibilityPoller {
  start(): void;
  stop(): void;
}

export function createVisibilityPoller(
  load: () => void | Promise<void>,
  intervalMs: number
): VisibilityPoller {
  let timer: ReturnType<typeof setInterval> | undefined;
  let listening = false;

  function run(): void {
    void Promise.resolve(load()).catch(() => undefined);
  }

  function pause(): void {
    if (timer !== undefined) {
      clearInterval(timer);
      timer = undefined;
    }
  }

  function resume(): void {
    pause();
    timer = setInterval(() => {
      // A tick that fires while hidden (e.g. the visibilitychange event was missed) is skipped.
      if (!document.hidden) run();
    }, intervalMs);
  }

  function onVisibilityChange(): void {
    if (document.visibilityState === "hidden") {
      pause();
      return;
    }
    run();
    resume();
  }

  return {
    start(): void {
      if (timer !== undefined || listening) return;
      listening = true;
      document.addEventListener("visibilitychange", onVisibilityChange);
      run();
      resume();
    },
    stop(): void {
      pause();
      if (listening) {
        document.removeEventListener("visibilitychange", onVisibilityChange);
        listening = false;
      }
    }
  };
}
