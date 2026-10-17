import { describe, expect, it } from "vitest";
import {
  attributionStatusText,
  attributionTagType,
  attributionTypeText,
  manualAttributionRequest,
  orderStatusText,
  payRangeToQuery
} from "../order";

describe("order display texts", () => {
  // Texts come from the zh-CN pack, the default locale in tests.
  it("reads the pack for every attribution status and keeps null unknown", () => {
    // toEqual is not used for the whole map so a missing key points at the exact status.
    expect(attributionStatusText("ATTRIBUTED")).toBe("已归属");
    expect(attributionStatusText("NON_KOL")).toBe("非达人");
    expect(attributionStatusText("CONFLICT")).toBe("冲突");
    expect(attributionStatusText("UNMATCHED")).toBe("未匹配");
    expect(attributionStatusText("PENDING")).toBe("待支付");
    expect(attributionStatusText(null)).toBe("-");
  });

  it("reads the pack for every order status", () => {
    for (const status of [
      "UNPAID",
      "PAID",
      "SHIPPED",
      "RECEIVED",
      "COMPLETED",
      "CLOSED",
      "UNKNOWN"
    ] as const) {
      expect(orderStatusText(status)).not.toBe(status);
    }
    expect(orderStatusText(null)).toBe("-");
  });

  it("maps line attribution types to their pack names", () => {
    expect(attributionTypeText(0)).toBe("未匹配");
    expect(attributionTypeText(1)).toBe("商品 ID");
    expect(attributionTypeText(2)).toBe("人工");
    expect(attributionTypeText(3)).toBe("非达人");
    expect(attributionTypeText(4)).toBe("冲突");
    expect(attributionTypeText(null)).toBe("-");
  });

  it("separates settled, manual-work and muted tag colours", () => {
    expect(attributionTagType("ATTRIBUTED")).toBe("success");
    expect(attributionTagType("NON_KOL")).toBe("success");
    expect(attributionTagType("CONFLICT")).toBe("danger");
    expect(attributionTagType("UNMATCHED")).toBe("warning");
    expect(attributionTagType("PENDING")).toBe("info");
    expect(attributionTagType(null)).toBe("info");
  });
});

describe("manualAttributionRequest", () => {
  it("builds the assign body with a trimmed reason", () => {
    expect(manualAttributionRequest({ kolId: "100" }, "  confirmed by ops  ")).toEqual({
      kolId: "100",
      reason: "confirmed by ops"
    });
  });

  it("builds the non-KOL body", () => {
    expect(manualAttributionRequest({ nonKol: true }, "direct sale")).toEqual({
      nonKol: true,
      reason: "direct sale"
    });
  });

  it("rejects a missing reason, whitespace and over 255 characters", () => {
    expect(manualAttributionRequest({ kolId: "100" }, "")).toBeNull();
    expect(manualAttributionRequest({ nonKol: true }, "   ")).toBeNull();
    expect(manualAttributionRequest({ kolId: "100" }, "x".repeat(256))).toBeNull();
    expect(manualAttributionRequest(null, "valid reason")).toBeNull();
  });
});

describe("payRangeToQuery", () => {
  it("converts a locally picked range into UTC instants, end unchanged", () => {
    // 2026-10-15 16:00 UTC; any local zone yields the same absolute instant.
    const from = new Date("2026-10-15T16:00:00.000Z");
    const to = new Date("2026-10-16T16:00:00.000Z");
    // payTo stays exactly what was picked: the backend treats it as exclusive.
    expect(payRangeToQuery([from, to])).toEqual({
      payFrom: "2026-10-15T16:00:00.000Z",
      payTo: "2026-10-16T16:00:00.000Z"
    });
  });

  it("omits both bounds without a complete range", () => {
    expect(payRangeToQuery(null)).toEqual({});
    expect(
      payRangeToQuery([new Date("2026-10-15T16:00:00.000Z"), undefined as unknown as Date])
    ).toEqual({});
  });
});
