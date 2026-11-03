import { describe, expect, it } from "vitest";
import { localDateRangeToUtc, recentLocalDateRange } from "../orders";

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

  it("rejects reversed ranges", () => {
    expect(() => localDateRangeToUtc("2026-10-17", "2026-10-16")).toThrow(RangeError);
  });
});
