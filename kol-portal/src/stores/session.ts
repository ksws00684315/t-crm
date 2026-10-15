import type { AuthSession, KolProfile } from "@/api/auth";
import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { clearStoredSession, loadSession, saveSession } from "@/utils/session";

/** Creator identity and token are replaced together after login or a password change. */
export const useSessionStore = defineStore("session", () => {
  const stored = loadSession();
  const token = ref<string | null>(stored?.token ?? null);
  const expiresAt = ref<number>(stored?.expiresAt ?? 0);

  const kol = ref<KolProfile | null>(stored?.kol ?? null);

  // Requests and guards must read the clock each time, even without a reactive state change.
  function getToken(): string | null {
    return token.value && expiresAt.value > Date.now() ? token.value : null;
  }
  const isLoggedIn = computed(() => !!getToken());

  function setSession(next: { token: string; expiresAt: number; kol?: KolProfile }) {
    token.value = next.token;
    expiresAt.value = next.expiresAt;
    kol.value = next.kol ?? null;
    saveSession(next);
  }

  function acceptSession(next: AuthSession) {
    setSession({ token: next.token, expiresAt: Date.now() + next.expiresIn * 1000, kol: next.kol });
  }

  function updateProfile(profile: KolProfile) {
    kol.value = profile;
    if (token.value) saveSession({ token: token.value, expiresAt: expiresAt.value, kol: profile });
  }

  function clearSession() {
    kol.value = null;
    token.value = null;
    expiresAt.value = 0;
    clearStoredSession();
  }

  return {
    token,
    kol,
    isLoggedIn,
    getToken,
    setSession,
    acceptSession,
    updateProfile,
    clearSession
  };
});
