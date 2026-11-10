import { describe, expect, it } from "vitest";
import { datePreset, dates, validDates } from "./board";

describe("analytics date ranges", () => {
  it("uses the Beijing calendar across the UTC month boundary", () => {
    const instant = new Date("2026-10-31T16:30:00Z");
    expect(dates(7, instant)).toEqual({ startDate: "2026-10-26", endDate: "2026-11-01" });
    expect(datePreset("today", instant)).toEqual({
      startDate: "2026-11-01",
      endDate: "2026-11-01"
    });
    expect(datePreset("yesterday", instant)).toEqual({
      startDate: "2026-10-31",
      endDate: "2026-10-31"
    });
    expect(datePreset("month", instant)).toEqual({
      startDate: "2026-11-01",
      endDate: "2026-11-01"
    });
  });

  it("accepts 90 inclusive days and rejects inverted, missing and oversized ranges", () => {
    const instant = new Date("2026-11-01T00:00:00Z");
    expect(validDates(dates(90, instant))).toBe(true);
    expect(validDates(dates(91, instant))).toBe(false);
    expect(validDates({ startDate: "2026-11-02", endDate: "2026-11-01" })).toBe(false);
    expect(validDates({ startDate: "", endDate: "2026-11-01" })).toBe(false);
  });
});
