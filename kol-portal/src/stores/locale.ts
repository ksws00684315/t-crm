import { defineStore } from "pinia";
import { ref } from "vue";
import { i18n } from "@/locales";
import { loadStoredLocale, saveStoredLocale, type LocaleId } from "@/utils/locale";

/**
 * The portal's language: the current locale and how to switch it. Switching applies the choice to
 * the i18n instance and <html lang> in one step, so UI text, Element Plus and the document title
 * follow immediately. The persisted choice is read (and applied) when the store is created; main.ts
 * creates the store before mounting, so a reload restores the saved language before first render.
 */
export const useLocaleStore = defineStore("locale", () => {
  const locale = ref<LocaleId>(loadStoredLocale());

  function setLocale(next: LocaleId): void {
    locale.value = next;
    saveStoredLocale(next);
    i18n.global.locale.value = next;
    document.documentElement.lang = next;
  }

  // Restore the saved language for this session (no-op when it equals the default).
  i18n.global.locale.value = locale.value;
  document.documentElement.lang = locale.value;

  return { locale, setLocale };
});
