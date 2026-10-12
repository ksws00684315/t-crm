import { defineStore } from "pinia";
import { computed, ref } from "vue";
import * as authApi from "@/api/auth";
import type { Role, UserVO } from "@/api/types";
import { useReauthStore } from "@/stores/reauth";
import { clearStoredSession, loadSession, saveSession } from "@/utils/session";

export const useUserStore = defineStore("user", () => {
  const stored = loadSession();
  const user = ref<UserVO | null>(stored?.user ?? null);
  const token = ref<string | null>(stored?.token ?? null);
  const expiresAt = ref<number>(stored?.expiresAt ?? 0);

  /** Signed in means a token that has not expired locally; the backend has the final word (401). */
  const isLoggedIn = computed(() => !!token.value && !!user.value && expiresAt.value > Date.now());
  const roles = computed<Role[]>(() => (user.value && isLoggedIn.value ? [user.value.role] : []));

  function setSession(next: { token: string; expiresAt: number; user: UserVO }) {
    token.value = next.token;
    expiresAt.value = next.expiresAt;
    user.value = next.user;
    saveSession(next);
  }

  /** Clears local state only (used on 401 and after logout). Also closes reauth so a later login does not reopen the dialog. */
  function clearSession() {
    token.value = null;
    expiresAt.value = 0;
    user.value = null;
    clearStoredSession();
    useReauthStore().close();
  }

  async function login(username: string, password: string) {
    const result = await authApi.login({ username, password });
    setSession({
      token: result.token,
      expiresAt: Date.now() + result.expiresIn * 1000,
      user: result.user
    });
  }

  /** Best effort server-side logout; local state is cleared either way. */
  async function logout() {
    try {
      if (isLoggedIn.value) await authApi.logout();
    } catch {
      // the token may already be invalid; nothing more to do
    } finally {
      clearSession();
    }
  }

  /** Re-reads the account (role changes, disabled account). A 401 clears the session by itself. */
  async function refreshProfile() {
    if (!token.value) return;
    const fresh = await authApi.fetchCurrentUser();
    setSession({ token: token.value, expiresAt: expiresAt.value, user: fresh });
  }

  return { user, token, isLoggedIn, roles, login, logout, clearSession, refreshProfile };
});
