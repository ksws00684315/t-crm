import { describe, expect, it } from "vitest";
import {
  DIAL_COUNTRIES,
  composeE164,
  countriesByName,
  countryName,
  defaultIso,
  filterCountries,
  findCountry,
  splitE164
} from "../dial-codes";

describe("dial code data", () => {
  it("has one entry per ISO code with a numeric dial code and both names", () => {
    expect(DIAL_COUNTRIES.length).toBeGreaterThan(200);
    const seen = new Set<string>();
    for (const country of DIAL_COUNTRIES) {
      expect(country.iso).toMatch(/^[A-Z]{2}$/);
      expect(country.dial).toMatch(/^\d{1,4}$/);
      expect(country.en.length).toBeGreaterThan(0);
      expect(country.zh.length).toBeGreaterThan(0);
      expect(seen.has(country.iso)).toBe(false);
      seen.add(country.iso);
    }
  });
});

describe("splitE164", () => {
  it("splits a pasted number with spaces", () => {
    expect(splitE164("+49 171 6422826")).toEqual({ iso: "DE", national: "1716422826" });
  });

  it("prefers the canonical country of a shared dial code", () => {
    expect(splitE164("+1 (415) 555-2671")).toEqual({ iso: "US", national: "4155552671" });
    expect(splitE164("+7 701 123 4567")).toEqual({ iso: "RU", national: "7011234567" });
  });

  it("takes the longest matching dial code", () => {
    expect(splitE164("+358 18 12345")).toEqual({ iso: "FI", national: "1812345" });
  });

  it("rejects values without a plus or without digits", () => {
    expect(splitE164("1716422826")).toBeNull();
    expect(splitE164("+")).toBeNull();
    expect(splitE164("")).toBeNull();
  });
});

describe("composeE164", () => {
  it("joins the dial code and the digits, dropping separators", () => {
    expect(composeE164("DE", "171 642-2826")).toBe("+491716422826");
  });

  it("returns empty when either part is missing", () => {
    expect(composeE164("DE", "")).toBe("");
    expect(composeE164("", "1716422826")).toBe("");
  });
});

describe("country helpers", () => {
  it("reads the name in the current language", () => {
    const germany = findCountry("DE");
    expect(germany).toBeDefined();
    expect(countryName(germany!, "en-US")).toBe("Germany");
    expect(countryName(germany!, "zh-CN")).toBe("德国");
  });

  it("returns every country once for the selector", () => {
    const list = countriesByName("zh-CN");
    expect(list.length).toBe(DIAL_COUNTRIES.length);
    expect(new Set(list.map(country => country.iso)).size).toBe(list.length);
  });

  it("searches the selector by name or dial code", () => {
    const byName = filterCountries("ger", "en-US").map(country => country.iso);
    expect(byName).toContain("DE");
    const byChineseName = filterCountries("德", "zh-CN").map(country => country.iso);
    expect(byChineseName).toContain("DE");
    const byDial = filterCountries("+49", "en-US").map(country => country.iso);
    expect(byDial).toContain("DE");
    const byDigits = filterCountries("49", "en-US").map(country => country.iso);
    expect(byDigits).toContain("DE");
    expect(filterCountries("", "en-US").length).toBe(DIAL_COUNTRIES.length);
  });

  it("defaults to the region subtag of the browser language", () => {
    expect(defaultIso("de-DE")).toBe("DE");
    expect(defaultIso("zh-Hans-CN")).toBe("CN");
    expect(defaultIso("xx-YY")).toBe("");
    expect(defaultIso("en")).toBe("");
  });
});
