import { describe, expect, it } from "vitest";
import { formatMoney, localDateRangeToUtc, recentLocalDateRange } from "../orders";

describe("order date filters", () => {
  it("defaults to the latest 30 local calendar dates, including today", () => {
    expect(recentLocalDateRange(30, new Date(2026, 9, 16, 18, 30))).toEqual([
      "2026-09-17",
      "2026-10-16"
    ]);
  });

  it("converts inclusive local dates to an exclusive UTC end", () => {
    const [from, to] = localDateRangeToUtc("2026-10-16", "2026-10-16");
    expect(from).toBe(new Date(2026, 9, 16).toISOString());
    expect(to).toBe(new Date(2026, 9, 17).toISOString());
  });

  it("rejects reversed ranges and formats CNY with two decimal places", () => {
    expect(() => localDateRangeToUtc("2026-10-17", "2026-10-16")).toThrow(RangeError);
    expect(formatMoney("128", "en-US")).toBe("¥128.00");
  });

  it("leads a negative amount with the sign and groups long amounts", () => {
    expect(formatMoney("-125.25", "en-US")).toBe("-¥125.25");
    expect(formatMoney("12345678.9", "en-US")).toBe("¥12,345,678.90");
    expect(formatMoney("-0.50", "zh-CN")).toBe("-¥0.50");
    expect(formatMoney("0.00", "en-US")).toBe("¥0.00");
  });
});
