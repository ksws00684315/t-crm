import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { clearStoredSession, loadSession, saveSession } from "@/utils/session";

/** Local sign-in state. The creator login endpoint is provided by a later task. */
export const useSessionStore = defineStore("session", () => {
  const stored = loadSession();
  const token = ref<string | null>(stored?.token ?? null);
  const expiresAt = ref<number>(stored?.expiresAt ?? 0);

  const isLoggedIn = computed(() => !!token.value && expiresAt.value > Date.now());

  function setSession(next: { token: string; expiresAt: number }) {
    token.value = next.token;
    expiresAt.value = next.expiresAt;
    saveSession(next);
  }

  function clearSession() {
    token.value = null;
    expiresAt.value = 0;
    clearStoredSession();
  }

  return { token, isLoggedIn, setSession, clearSession };
});
