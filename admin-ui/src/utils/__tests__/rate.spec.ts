import { describe, expect, it } from "vitest";
import { formatRate, percentToRate, rateToPercent, validatePercent } from "../rate";

describe("commission rate conversion", () => {
  it("turns a percentage into a 4-decimal fraction", () => {
    expect(percentToRate("15.5")).toBe("0.1550");
    expect(percentToRate("0")).toBe("0.0000");
    expect(percentToRate("100")).toBe("1.0000");
    expect(percentToRate("100.00")).toBe("1.0000");
    expect(percentToRate("0.01")).toBe("0.0001");
    expect(percentToRate("12.34")).toBe("0.1234");
    expect(percentToRate(" 7 ")).toBe("0.0700");
  });

  it("rejects empty, malformed and out-of-range percentages", () => {
    for (const value of ["", null, undefined, "100.01", "101", "-1", "1.234", "abc", "1e2", ".5"]) {
      expect(percentToRate(value)).toBeNull();
    }
  });

  it("turns a fraction back into the shortest percentage", () => {
    expect(rateToPercent("0.1550")).toBe("15.5");
    expect(rateToPercent("1.0000")).toBe("100");
    expect(rateToPercent("0.0001")).toBe("0.01");
    expect(rateToPercent("0.1234")).toBe("12.34");
    expect(rateToPercent("0.0000")).toBe("0");
    expect(rateToPercent("0.2")).toBe("20");
    expect(rateToPercent(null)).toBe("");
    expect(rateToPercent("x")).toBe("");
  });

  it("round-trips every 2-decimal percentage without drift", () => {
    for (let basisPoints = 0; basisPoints <= 10000; basisPoints += 7) {
      const percent = (basisPoints / 100).toFixed(2);
      const rate = percentToRate(percent);
      expect(rate).not.toBeNull();
      expect(Number(rateToPercent(rate))).toBe(Number(percent));
    }
  });

  it("formats for display and validates input", () => {
    expect(formatRate("0.1550")).toBe("15.5%");
    expect(formatRate(null)).toBe("-");
    expect(validatePercent("")).toBeNull();
    expect(validatePercent("15.5")).toBeNull();
    expect(validatePercent("100.5")).toContain("0～100");
  });
});
