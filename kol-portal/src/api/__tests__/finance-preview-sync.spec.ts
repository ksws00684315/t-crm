import { afterEach, describe, expect, it, vi } from "vitest";
import { createFinanceDemo } from "../commission/finance-engine";
import {
  loadPreviewState,
  savePreviewState,
  withPreviewState
} from "../commission/finance-preview-sync";

afterEach(() => vi.unstubAllGlobals());
describe("shared commission preview state", () => {
  it("hydrates another client and sends the loaded revision with mutations", async () => {
    const demo = createFinanceDemo();
    const state = demo.snapshot();
    state.config.minimum = "250.00";
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ revision: 7, state })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ revision: 8 })));
    vi.stubGlobal("fetch", fetcher);
    await loadPreviewState(demo);
    expect(demo.snapshot().config.minimum).toBe("250.00");
    await savePreviewState(demo);
    expect(JSON.parse(fetcher.mock.calls[1][1].body).revision).toBe(7);
  });
  it("rejects stale writes and unavailable storage", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("{}", { status: 409 })));
    await expect(savePreviewState(createFinanceDemo())).rejects.toBeDefined();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    await expect(loadPreviewState(createFinanceDemo())).rejects.toBeDefined();
  });
  it("serializes concurrent operations and recovers after rejection", async () => {
    const order: number[] = [];
    const first = withPreviewState(async () => {
      order.push(1);
      await Promise.resolve();
      order.push(2);
      throw new Error("failed");
    });
    const second = withPreviewState(async () => {
      order.push(3);
    });
    await expect(first).rejects.toThrow("failed");
    await second;
    expect(order).toEqual([1, 2, 3]);
  });
});
