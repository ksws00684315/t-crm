import { describe, expect, it } from "vitest";
import {
  channelText,
  orderRefundStatusText,
  orderRefundTagType,
  orderSourceText,
  orderStatusText,
  orderTimeRangeToQuery,
  refundStatusText,
  refundTagType,
  refundTypeText,
  syncSourceText
} from "../order";

describe("order display texts", () => {
  // Texts come from the zh-CN pack, the default locale in tests.
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
});

describe("orderTimeRangeToQuery (task 70 B01)", () => {
  it("converts a picked platform-order range into UTC instants, end unchanged", () => {
    expect(
      orderTimeRangeToQuery([
        new Date("2026-10-15T16:00:00.000Z"),
        new Date("2026-10-16T16:00:00.000Z")
      ])
    ).toEqual({
      orderTimeFrom: "2026-10-15T16:00:00.000Z",
      orderTimeTo: "2026-10-16T16:00:00.000Z"
    });
  });

  it("omits both bounds without a complete range", () => {
    expect(orderTimeRangeToQuery(null)).toEqual({});
    expect(
      orderTimeRangeToQuery([undefined as unknown as Date, new Date("2026-10-16T16:00:00.000Z")])
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
