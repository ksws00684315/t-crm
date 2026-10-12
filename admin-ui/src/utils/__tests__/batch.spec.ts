import { describe, expect, it, vi } from "vitest";
import { runBatch } from "../batch";

describe("runBatch", () => {
  it("runs each item in order and keeps going after a failure", async () => {
    const order: number[] = [];
    const progress = vi.fn();
    const result = await runBatch(
      [1, 2, 3],
      async item => {
        order.push(item);
        if (item === 2) throw new Error("nope");
      },
      progress
    );
    expect(order).toEqual([1, 2, 3]);
    expect(result.succeeded).toEqual([1, 3]);
    expect(result.failed.map(failure => failure.item)).toEqual([2]);
    expect(progress).toHaveBeenLastCalledWith(3, 3);
  });
});
