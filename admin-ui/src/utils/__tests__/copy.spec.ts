import { afterEach, describe, expect, it, vi } from "vitest";
import { copyText, truncateCopy } from "../copy";

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  Reflect.deleteProperty(document, "execCommand");
});

function stubExec(result: boolean) {
  const exec = vi.fn().mockReturnValue(result);
  Object.defineProperty(document, "execCommand", { configurable: true, value: exec });
  return exec;
}

describe("copyText", () => {
  it("uses the Clipboard API", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    await expect(copyText("KOL0001")).resolves.toBe(true);
    expect(writeText).toHaveBeenCalledWith("KOL0001");
  });

  it("falls back to execCommand when the Clipboard API is missing", async () => {
    vi.stubGlobal("navigator", {});
    const exec = stubExec(true);
    await expect(copyText("KOL0001")).resolves.toBe(true);
    expect(exec).toHaveBeenCalledWith("copy");
  });

  it("falls back when the Clipboard API rejects, and reports a failed legacy copy", async () => {
    const writeText = vi.fn().mockRejectedValue(new Error("denied"));
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    stubExec(false);
    await expect(copyText("KOL0001")).resolves.toBe(false);
  });

  it("truncates a long value", () => {
    expect(truncateCopy("KOL0001")).toBe("KOL0001");
    expect(truncateCopy("x".repeat(40))).toBe(`${"x".repeat(32)}…`);
  });
});
