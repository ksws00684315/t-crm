import { describe, expect, it } from "vitest";
import enUS from "@/locales/en-US";
import zhCN from "@/locales/zh-CN";
import { bdTransferCancelCode } from "../bd-transfer";

describe("bdTransferCancelCode", () => {
  it("maps every reason the backend stores to a code that both language packs know", () => {
    const expected: Record<string, string> = {
      执行时归属已变化: "OWNERSHIP_CHANGED",
      目标商务不可用: "TARGET_UNAVAILABLE",
      目标商务已停用: "TARGET_DISABLED",
      被立即转移取代: "REPLACED_BY_IMMEDIATE",
      手动取消: "MANUAL"
    };
    for (const [reason, code] of Object.entries(expected)) {
      expect(bdTransferCancelCode(reason)).toBe(code);
      expect(zhCN.bdTransfer.cancelReasons).toHaveProperty(code, reason);
      expect(enUS.bdTransfer.cancelReasons).toHaveProperty(code);
    }
  });

  it("tolerates surrounding whitespace", () => {
    expect(bdTransferCancelCode(" 手动取消 ")).toBe("MANUAL");
  });

  it("returns null for unknown, empty and missing reasons", () => {
    expect(bdTransferCancelCode("something else")).toBeNull();
    expect(bdTransferCancelCode("")).toBeNull();
    expect(bdTransferCancelCode(null)).toBeNull();
    expect(bdTransferCancelCode(undefined)).toBeNull();
  });
});
