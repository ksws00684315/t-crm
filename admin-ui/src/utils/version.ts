export const VERSION_CHECK_MS = 5 * 60 * 1000;
export const VERSION_SNOOZE_MS = 30 * 60 * 1000;
export const VERSION_SNOOZE_KEY = "tcrm.admin.version-snooze";

export function shouldPrompt(input: {
  dev: boolean;
  current: string;
  remote: string | null;
  now: number;
  snoozeUntil: number;
}): boolean {
  if (input.dev || !input.remote || input.remote === input.current) return false;
  return input.now >= input.snoozeUntil;
}

export function readSnoozeUntil(): number {
  try {
    const value = Number(localStorage.getItem(VERSION_SNOOZE_KEY));
    return Number.isFinite(value) ? value : 0;
  } catch {
    return 0;
  }
}

export function writeSnoozeUntil(until: number): void {
  try {
    localStorage.setItem(VERSION_SNOOZE_KEY, String(until));
  } catch {
    // Private mode can reject storage. The card can still be dismissed for this page view.
  }
}

export async function fetchRemoteVersion(now: number): Promise<string | null> {
  try {
    const response = await fetch(`/version.json?t=${now}`, { cache: "no-store" });
    if (!response.ok) return null;
    const body = (await response.json()) as { version?: unknown };
    return typeof body.version === "string" ? body.version : null;
  } catch {
    return null;
  }
}

/** Starts the 5-minute and visibility checks. Development does not call the network. */
export function monitorVersion(options: {
  dev: boolean;
  current: string;
  now: () => number;
  fetchRemote: (now: number) => Promise<string | null>;
  readSnooze: () => number;
  onPrompt: () => void;
}): () => void {
  if (options.dev) return () => undefined;
  const tick = () => {
    void options.fetchRemote(options.now()).then(remote => {
      if (
        shouldPrompt({
          dev: false,
          current: options.current,
          remote,
          now: options.now(),
          snoozeUntil: options.readSnooze()
        })
      ) {
        options.onPrompt();
      }
    });
  };
  tick();
  const timer = window.setInterval(tick, VERSION_CHECK_MS);
  const onVisible = () => {
    if (document.visibilityState === "visible") tick();
  };
  document.addEventListener("visibilitychange", onVisible);
  return () => {
    window.clearInterval(timer);
    document.removeEventListener("visibilitychange", onVisible);
  };
}
