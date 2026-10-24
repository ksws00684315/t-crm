import { financeError } from "./finance-engine";
import type { createFinanceDemo, FinanceDemoState } from "./finance-engine";

type Demo = ReturnType<typeof createFinanceDemo>;
const endpoint =
  import.meta.env.VITE_COMMISSION_PREVIEW_STORE_URL ??
  "http://127.0.0.1:18941/__commission_preview__/state";
let revision = 0;
let operations = Promise.resolve();

export function withPreviewState<T>(action: () => Promise<T>): Promise<T> {
  const operation = operations.then(action);
  operations = operation.then(
    () => undefined,
    () => undefined
  );
  return operation;
}

async function request(method: "GET" | "PUT" | "DELETE", body?: object): Promise<Response> {
  try {
    return await fetch(endpoint, {
      method,
      cache: "no-store",
      ...(body
        ? { headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
        : {})
    });
  } catch {
    financeError(503, "COMMON_INTERNAL_ERROR");
  }
}

/** Read before every demo operation so the two CRM origins show the same application state. */
export async function loadPreviewState(demo: Demo): Promise<void> {
  const response = await request("GET");
  if (!response.ok) financeError(response.status, "COMMON_INTERNAL_ERROR");
  const saved = (await response.json()) as { revision: number; state: FinanceDemoState | null };
  if (!Number.isSafeInteger(saved.revision) || saved.revision < 0)
    financeError(503, "COMMON_INTERNAL_ERROR");
  revision = saved.revision;
  if (saved.state) demo.hydrate(saved.state);
  else demo.reset();
}

export async function savePreviewState(demo: Demo): Promise<void> {
  const response = await request("PUT", { revision, state: demo.snapshot() });
  if (!response.ok)
    financeError(
      response.status,
      response.status === 409 ? "COMMON_CONFLICT" : "COMMON_INTERNAL_ERROR"
    );
  const saved = (await response.json()) as { revision: number };
  revision = saved.revision;
}

export async function clearPreviewState(): Promise<void> {
  const response = await request("DELETE");
  if (!response.ok) financeError(response.status, "COMMON_INTERNAL_ERROR");
}
