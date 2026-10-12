import { defineStore } from "pinia";
import { computed, ref } from "vue";
import {
  applyThemeClass,
  loadStoredTheme,
  nextTheme,
  resolveTheme,
  saveStoredTheme,
  subscribeSystemTheme,
  type ThemeMode
} from "@/utils/theme";

/**
 * Light / dark / system. The class on <html> is applied when the store is created, and main.ts
 * creates the store before mount so the first paint already matches the saved choice.
 */
export const useThemeStore = defineStore("theme", () => {
  const mode = ref<ThemeMode>(loadStoredTheme());
  const prefersDark = ref(false);

  const resolved = computed(() => resolveTheme(mode.value, prefersDark.value));

  function apply(): void {
    applyThemeClass(resolved.value);
  }

  function setMode(next: ThemeMode): void {
    mode.value = next;
    saveStoredTheme(next);
    apply();
  }

  function cycle(): void {
    setMode(nextTheme(mode.value));
  }

  const unsubscribe = subscribeSystemTheme(matches => {
    prefersDark.value = matches;
    apply();
  });
  apply();

  return { mode, resolved, setMode, cycle, unsubscribe };
});
