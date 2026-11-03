import { i18n, translate } from "@/locales";
import type { LocaleId } from "@/utils/locale";
import { isSupportedLocale } from "@/utils/locale";

/** Current UI language for Intl formatters; falls back when i18n is not ready. */
function currentLocale(): LocaleId {
  const value = i18n.global.locale.value;
  return isSupportedLocale(value) ? value : "zh-CN";
}

/** Formats an ISO 8601 instant for display in the operator's local time zone and language. */
export function formatInstant(value: string | null | undefined): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";
  return new Intl.DateTimeFormat(currentLocale(), {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(date);
}

/** Display an instant through minute precision, as requested for creator invitations. */
export function formatInstantToMinute(value: string | null | undefined): string {
  const full = formatInstant(value);
  return full === "-" ? full : full.slice(0, -3);
}

/**
 * "Name (code)" with locale-aware brackets: fullwidth for zh-CN, halfwidth for en-US.
 * Prefer this over hardcoding （） or () in templates.
 */
export function formatNameWithCode(
  name: string | null | undefined,
  code: string | null | undefined
): string {
  return translate("common.nameWithCode", { name: name ?? "", code: code ?? "" });
}

/** "Code (name)" with the same locale-aware brackets as {@link formatNameWithCode}. */
export function formatCodeWithName(
  code: string | null | undefined,
  name: string | null | undefined
): string {
  return translate("common.codeWithName", { code: code ?? "", name: name ?? "" });
}

const CURRENCY_SYMBOLS: Record<string, string> = { CNY: "¥", USD: "$", EUR: "€", GBP: "£" };

const AMOUNT_PATTERN = /^([+-]?)(\d*)(?:\.(\d+))?$/;

/** Inserts thousands separators into the integer part without converting to a float. */
function groupIntegerPart(integerPart: string): string {
  return integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/**
 * Symbol-style money for both consoles (owner decision 2026-11-02): "¥1,234.56", "-¥5.00",
 * "1,234 JPY". The symbol follows the business currency; unknown codes use "amount CODE".
 * Grouping happens on the decimal string so no backend precision is lost to floating point.
 */
export function formatAmountWithCurrency(
  amount: string | null | undefined,
  currency?: string | null
): string {
  if (amount === null || amount === undefined || amount === "") return "-";
  const text = String(amount).trim();
  const code = (currency || "CNY").toUpperCase();
  const match = AMOUNT_PATTERN.exec(text);
  if (!match) return text;
  const [, sign = "", rawInteger = "", fraction] = match;
  const integerPart = groupIntegerPart(rawInteger.replace(/^0+(?=\d)/, "") || "0");
  const value = fraction === undefined ? integerPart : `${integerPart}.${fraction}`;
  const symbol = CURRENCY_SYMBOLS[code];
  return symbol ? `${sign}${symbol}${value}` : `${sign}${value} ${code}`;
}

/** Kept as a named alias so existing call sites share the same formatting rules. */
export function formatPrice(
  price: string | null | undefined,
  currency: string | null | undefined
): string {
  return formatAmountWithCurrency(price, currency);
}
