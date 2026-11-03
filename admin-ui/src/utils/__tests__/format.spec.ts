import { afterEach, describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import {
  formatCodeWithName,
  formatInstant,
  formatInstantToMinute,
  formatNameWithCode,
  formatAmountWithCurrency
} from "../format";

describe("formatAmountWithCurrency", () => {
  it("uses symbol style with thousands separators and keeps backend precision", () => {
    expect(formatAmountWithCurrency("50.00")).toBe("¥50.00");
    expect(formatAmountWithCurrency("1234.56")).toBe("¥1,234.56");
    expect(formatAmountWithCurrency("0.00")).toBe("¥0.00");
    expect(formatAmountWithCurrency("0")).toBe("¥0");
    expect(formatAmountWithCurrency("-5.00")).toBe("-¥5.00");
    expect(formatAmountWithCurrency("-1234567890123456.7890")).toBe("-¥1,234,567,890,123,456.7890");
    expect(formatAmountWithCurrency("9007199254740993.00")).toBe("¥9,007,199,254,740,993.00");
  });

  it("follows the business currency and falls back to CNY", () => {
    expect(formatAmountWithCurrency("1.25", "USD")).toBe("$1.25");
    expect(formatAmountWithCurrency("18.35", "EUR")).toBe("€18.35");
    expect(formatAmountWithCurrency("7.5", "GBP")).toBe("£7.5");
    expect(formatAmountWithCurrency("1200", "JPY")).toBe("1,200 JPY");
    expect(formatAmountWithCurrency("1200", "AUD")).toBe("1,200 AUD");
    expect(formatAmountWithCurrency("3.00", null)).toBe("¥3.00");
    expect(formatAmountWithCurrency("3.00", "")).toBe("¥3.00");
  });

  it("distinguishes zero from missing money", () => {
    expect(formatAmountWithCurrency(null)).toBe("-");
    expect(formatAmountWithCurrency(undefined)).toBe("-");
    expect(formatAmountWithCurrency("")).toBe("-");
  });
});

describe("formatInstant", () => {
  afterEach(() => {
    i18n.global.locale.value = "zh-CN";
  });

  it("formats with the current UI language instead of a hard-coded zh-CN", () => {
    const iso = "2026-04-01T12:34:56.000Z";
    i18n.global.locale.value = "zh-CN";
    const zh = formatInstant(iso);
    i18n.global.locale.value = "en-US";
    const en = formatInstant(iso);
    expect(zh).not.toBe("-");
    expect(en).not.toBe("-");
    // Locales differ in separators / ordering; both must include the calendar parts.
    expect(zh).toMatch(/2026/);
    expect(en).toMatch(/2026/);
    expect(zh).not.toEqual(en);
  });

  it("returns a dash for empty or invalid values", () => {
    expect(formatInstant(null)).toBe("-");
    expect(formatInstant("")).toBe("-");
    expect(formatInstant("not-a-date")).toBe("-");
  });

  it("trims seconds for formatInstantToMinute", () => {
    i18n.global.locale.value = "en-US";
    const full = formatInstant("2026-04-01T12:34:56.000Z");
    expect(formatInstantToMinute("2026-04-01T12:34:56.000Z")).toBe(full.slice(0, -3));
    expect(formatInstantToMinute(null)).toBe("-");
  });
});

describe("formatNameWithCode / formatCodeWithName", () => {
  afterEach(() => {
    i18n.global.locale.value = "zh-CN";
  });

  it("uses fullwidth brackets in Chinese and halfwidth in English", () => {
    i18n.global.locale.value = "zh-CN";
    expect(formatNameWithCode("Alice", "KOL-001")).toBe("Alice（KOL-001）");
    expect(formatCodeWithName("KOL-001", "Alice")).toBe("KOL-001（Alice）");
    i18n.global.locale.value = "en-US";
    expect(formatNameWithCode("Alice", "KOL-001")).toBe("Alice (KOL-001)");
    expect(formatCodeWithName("KOL-001", "Alice")).toBe("KOL-001 (Alice)");
  });
});
