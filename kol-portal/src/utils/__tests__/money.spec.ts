import { describe, expect, it } from "vitest";
import { centsMoney, moneyCents } from "../money";

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
