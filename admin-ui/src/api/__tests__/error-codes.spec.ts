import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { ERROR_MESSAGES, messageForErrorCode } from "../error-codes";

/** Reads the enum constants of the backend `ErrorCode` straight from its source file. */
function backendErrorCodes(): string[] {
  // `pnpm test` runs with admin-ui as the working directory.
  const file = resolve(
    process.cwd(),
    "../backend/src/main/java/com/tcrm/admin/shared/ErrorCode.java"
  );
  const source = readFileSync(file, "utf8");
  const start = source.indexOf("enum ErrorCode");
  // Constants end at the first `);` that closes a line; messages may themselves contain `;`.
  const open = source.indexOf("{", start) + 1;
  const end = source.slice(open).search(/\);\s*$/m);
  const body = source.slice(open, open + end + 1);
  return [...body.matchAll(/^\s*([A-Z][A-Z0-9_]+)\(\d{3},/gm)].map(match => match[1]);
}

describe("error code messages", () => {
  it("covers every value of the backend ErrorCode enum", () => {
    const codes = backendErrorCodes();
    expect(codes.length).toBeGreaterThanOrEqual(14);
    const missing = codes.filter(code => !(code in ERROR_MESSAGES));
    expect(missing).toEqual([]);
  });

  it("uses Chinese text only", () => {
    for (const text of Object.values(ERROR_MESSAGES)) {
      expect(text).toMatch(/[\u4e00-\u9fa5]/);
    }
  });

  it("falls back to the generic text for unknown or missing codes", () => {
    expect(messageForErrorCode("NO_SUCH_CODE")).toBe("操作失败，请稍后重试");
    expect(messageForErrorCode(null)).toBe("操作失败，请稍后重试");
    expect(messageForErrorCode("toString")).toBe("操作失败，请稍后重试");
  });
});
