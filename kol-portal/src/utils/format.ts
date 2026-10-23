import { i18n } from "@/locales";
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

const CURRENCY_SYMBOLS: Record<string, string> = { CNY: "¥", USD: "$" };
export function formatPrice(
  price: string | null | undefined,
  currency: string | null | undefined
): string {
  if (price == null || price === "") return "—";
  return `${CURRENCY_SYMBOLS[currency ?? "CNY"] ?? ""}${price}${currency === "USDT" ? " USDT" : ""}`;
}

export function formatRate(rate: string | null | undefined): string {
  if (!rate || !/^\d\.\d{4}$/.test(rate)) return "—";
  const digits = rate.replace(".", "");
  const value = BigInt(digits);
  return `${value / 100n}${
    value % 100n
      ? "." +
        String(value % 100n)
          .padStart(2, "0")
          .replace(/0+$/, "")
      : ""
  }%`;
}
