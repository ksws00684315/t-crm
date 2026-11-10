import { describe, expect, it } from "vitest";
import { adjacentPeriod, dates, monthRange, periodRange, validDates } from "./board";

describe("analytics date ranges", () => {
  it("uses the Beijing calendar across the UTC month boundary", () => {
    const instant = new Date("2026-10-31T16:30:00Z");
    expect(dates(7, instant)).toEqual({ startDate: "2026-10-26", endDate: "2026-11-01" });
    expect(periodRange("live", instant)).toEqual({
      startDate: "2026-11-01",
      endDate: "2026-11-01"
    });
    expect(periodRange("month", instant)).toEqual({
      startDate: "2026-11-01",
      endDate: "2026-11-01"
    });
  });

  it("moves full periods across month boundaries and stops at today", () => {
    const today = "2026-03-03";
    const march = monthRange("2026-03", today);
    const february = adjacentPeriod("month", march, -1, today);
    expect(february).toEqual({ startDate: "2026-02-01", endDate: "2026-02-28" });
    expect(adjacentPeriod("month", february!, 1, today)).toEqual(march);
    expect(adjacentPeriod("month", march, 1, today)).toBeUndefined();
    const seven = { startDate: "2026-02-25", endDate: today };
    expect(adjacentPeriod("sevenDays", seven, -1, today)).toEqual({
      startDate: "2026-02-18",
      endDate: "2026-02-24"
    });
    expect(adjacentPeriod("live", seven, -1, today)).toBeUndefined();
  });

  it("accepts 90 inclusive days and rejects inverted, missing and oversized ranges", () => {
    const instant = new Date("2026-11-01T00:00:00Z");
    expect(validDates(dates(90, instant))).toBe(true);
    expect(validDates(dates(91, instant))).toBe(false);
    expect(validDates({ startDate: "2026-11-02", endDate: "2026-11-01" })).toBe(false);
    expect(validDates({ startDate: "", endDate: "2026-11-01" })).toBe(false);
  });
});
