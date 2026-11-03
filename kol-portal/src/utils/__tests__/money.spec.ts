import { describe, expect, it } from "vitest";
import { centsMoney, formatMoney, moneyCents, sumDecimals } from "../money";

describe("money cents helpers", () => {
  it.each([
    ["-0.50", -50n],
    ["-1.50", -150n],
    ["0", 0n],
    ["12", 1200n],
    ["12.5", 1250n],
    ["12.34", 1234n]
  ])("parses %s as %s cents", (value, expected) => {
    expect(moneyCents(value)).toBe(expected);
  });

  it.each([
    [-50n, "-0.50"],
    [-150n, "-1.50"],
    [0n, "0.00"],
    [1200n, "12.00"],
    [1234n, "12.34"]
  ])("formats %s cents as %s", (cents, expected) => {
    expect(centsMoney(cents)).toBe(expected);
  });

  it("keeps the sign through a parse-format round trip", () => {
    expect(centsMoney(moneyCents("-0.50"))).toBe("-0.50");
    expect(centsMoney(moneyCents("-1.50"))).toBe("-1.50");
  });
});

describe("formatMoney", () => {
  it.each([
    ["1234.56", "CNY", "¥1,234.56"],
    ["1234567.89", "CNY", "¥1,234,567.89"],
    ["0.00", "CNY", "¥0.00"],
    ["-1234.56", "CNY", "-¥1,234.56"],
    ["-0.50", "CNY", "-¥0.50"],
    ["1234.56", "USD", "$1,234.56"],
    ["1234.56", "EUR", "€1,234.56"],
    ["1234.56", "GBP", "£1,234.56"],
    ["1234", "JPY", "1,234 JPY"],
    ["-1234.5", "JPY", "-1,234.5 JPY"],
    ["1234.50", "AUD", "1,234.50 AUD"],
    ["1234.5000", "CNY", "¥1,234.5000"],
    ["0001234.50", "CNY", "¥1,234.50"]
  ])("formats %s as %s in %s", (amount, currency, expected) => {
    expect(formatMoney(amount, currency)).toBe(expected);
  });

  it("defaults to CNY and keeps the backend precision", () => {
    expect(formatMoney("128.00")).toBe("¥128.00");
    expect(formatMoney("128")).toBe("¥128");
    expect(formatMoney("12.3456")).toBe("¥12.3456");
  });

  it("renders empty and invalid input as a dash", () => {
    expect(formatMoney(null)).toBe("-");
    expect(formatMoney(undefined)).toBe("-");
    expect(formatMoney("")).toBe("-");
    expect(formatMoney("not-a-number")).toBe("-");
    expect(formatMoney("1e3")).toBe("-");
  });
});

describe("sumDecimals", () => {
  it("adds decimals without float drift", () => {
    expect(sumDecimals(["0.1", "0.2"])).toBe("0.3");
    expect(sumDecimals(["123456789012345678.90", "0.10"])).toBe("123456789012345679.00");
  });

  it("keeps the widest fraction length", () => {
    expect(sumDecimals(["1.5", "2.250"])).toBe("3.750");
    expect(sumDecimals(["1", "2"])).toBe("3");
  });

  it("supports negative values and ignores empty entries", () => {
    expect(sumDecimals(["10.00", "-2.50", null, ""])).toBe("7.50");
    expect(sumDecimals(["-1.10", "1.10"])).toBe("0.00");
    expect(sumDecimals([null, undefined, ""])).toBeNull();
  });
});
