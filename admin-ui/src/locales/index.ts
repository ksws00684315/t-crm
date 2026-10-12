import { createI18n } from "vue-i18n";
import { DEFAULT_LOCALE } from "@/utils/locale";
import zhCN, { type MessageSchema } from "./zh-CN";
import enUS from "./en-US";

export { default as zhCN } from "./zh-CN";
export type { MessageSchema };

/**
 * The one i18n instance. It starts at the default language; the locale store owns the actual
 * language (persisted choice, <html lang>) and applies it as soon as it is created.
 */
export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: DEFAULT_LOCALE,
  fallbackLocale: DEFAULT_LOCALE,
  messages: {
    "en-US": enUS,
    "zh-CN": zhCN
  }
});

/**
 * `t` for keys built at runtime (route meta titles, error codes), where the compile-time key
 * check cannot apply. Keys known statically keep using the typed `t`. Named values fill `{name}`
 * placeholders (trace id, counts).
 */
export function translate(key: string, values?: Record<string, unknown>): string {
  return (i18n.global.t as unknown as (k: string, v?: Record<string, unknown>) => string)(
    key,
    values
  );
}
