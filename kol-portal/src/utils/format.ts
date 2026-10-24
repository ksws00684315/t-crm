import { i18n } from "@/locales";
import { DEFAULT_LOCALE, isSupportedLocale } from "@/utils/locale";

/** Matches the admin formatter: local browser time, active language and second precision. */
export function formatInstant(value: string | null | undefined): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";
  const locale = i18n.global.locale.value;
  return new Intl.DateTimeFormat(isSupportedLocale(locale) ? locale : DEFAULT_LOCALE, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(date);
}
