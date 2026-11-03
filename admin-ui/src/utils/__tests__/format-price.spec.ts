import { describe, expect, it } from "vitest";
import { formatPrice } from "../format";

describe("formatPrice", () => {
  it("prefixes the symbol of known currencies with thousands separators", () => {
    expect(formatPrice("12.50", "CNY")).toBe("¥12.50");
    expect(formatPrice("1234.56", "CNY")).toBe("¥1,234.56");
    expect(formatPrice("60.00", "USD")).toBe("$60.00");
    expect(formatPrice("18.35", "EUR")).toBe("€18.35");
    expect(formatPrice("7.5", "GBP")).toBe("£7.5");
  });

  it("appends the code of other currencies and treats a missing one as CNY", () => {
    expect(formatPrice("1200", "JPY")).toBe("1,200 JPY");
    expect(formatPrice("3.00", null)).toBe("¥3.00");
  });

  it("shows a dash without a price", () => {
    expect(formatPrice("", "USD")).toBe("-");
    expect(formatPrice(null, "USD")).toBe("-");
  });
});
