import { describe, expect, it } from "vitest";
import {
  attributionStatusText,
  attributionTagType,
  attributionTypeText,
  channelText,
  manualAttributionRequest,
  orderRefundStatusText,
  orderRefundTagType,
  orderSourceText,
  orderStatusText,
  payRangeToQuery,
  refundStatusText,
  refundTagType,
  refundTypeText,
  syncSourceText
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

describe("refund status, type and channel texts (task 40)", () => {
  it("reads the pack for the order-level refund statuses (08.02)", () => {
    expect(orderRefundStatusText("NONE")).toBe("无退款");
    expect(orderRefundStatusText("PROCESSING")).toBe("退款中");
    expect(orderRefundStatusText("PARTIAL_SUCCESS")).toBe("部分退款成功");
    expect(orderRefundStatusText("FULL_SUCCESS")).toBe("全额退款成功");
    expect(orderRefundStatusText(null)).toBe("-");
  });

  it("reads the pack for refund types and single-refund statuses", () => {
    expect(refundTypeText("REFUND_ONLY")).toBe("仅退款");
    expect(refundTypeText("RETURN_REFUND")).toBe("退货退款");
    expect(refundTypeText(null)).toBe("-");
    expect(refundStatusText("PROCESSING")).toBe("退款中");
    expect(refundStatusText("SUCCESS")).toBe("退款成功");
    expect(refundStatusText("CLOSED")).toBe("已关闭");
  });

  it("never gives a refunding state the success colour (08.02)", () => {
    expect(refundTagType("SUCCESS")).toBe("success");
    expect(refundTagType("PROCESSING")).toBe("warning");
    expect(refundTagType("CLOSED")).toBe("info");
    expect(orderRefundTagType("NONE")).toBe("info");
    expect(orderRefundTagType("PROCESSING")).toBe("warning");
    expect(orderRefundTagType("PARTIAL_SUCCESS")).toBe("danger");
    expect(orderRefundTagType("FULL_SUCCESS")).toBe("danger");
  });

  it("names the channel and the two data sources", () => {
    expect(channelText("WEIDIAN")).toBe("微店");
    expect(channelText(null)).toBe("-");
    expect(orderSourceText("API")).toBe("接口同步");
    expect(orderSourceText("IMPORT")).toBe("导入");
    expect(syncSourceText(null)).toBe("-");
  });
});
