import { defineStore } from "pinia";
import { computed, ref } from "vue";
import * as authApi from "@/api/auth";
import type { PermissionCode, UserVO } from "@/api/types";
import { useLockStore } from "@/stores/lock";
import { useReauthStore } from "@/stores/reauth";
import { accessOf, type AccessProfile } from "@/utils/permission";
import { clearStoredSession, loadSession, saveSession } from "@/utils/session";

export const useUserStore = defineStore("user", () => {
  const stored = loadSession();
  const user = ref<UserVO | null>(stored?.user ?? null);
  const token = ref<string | null>(stored?.token ?? null);
  const expiresAt = ref<number>(stored?.expiresAt ?? 0);

  /** Signed in means a token that has not expired locally; the backend has the final word (401). */
  const isLoggedIn = computed(() => !!token.value && !!user.value && expiresAt.value > Date.now());
  /** Permission view of the signed-in account. Empty when the session is missing or expired. */
  const access = computed<AccessProfile>(() => accessOf(isLoggedIn.value ? user.value : null));
  const isSuperAdmin = computed(() => access.value.isSuperAdmin);
  const isOwnScope = computed(() => access.value.isOwnScope);
  const isPlatformAdmin = computed(() => access.value.isPlatformAdmin);

  function can(code: PermissionCode): boolean {
    return access.value.can(code);
  }

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
    // A lock belongs to a session; the next sign-in starts unlocked.
    useLockStore().unlock();
    useReauthStore().close();
  }

  async function login(username: string, password: string, captcha?: authApi.CaptchaAnswer) {
    const result = await authApi.login({ username, password, ...captcha });
    setSession({
      token: result.token,
      expiresAt: Date.now() + result.expiresIn * 1000,
      user: result.user
    });
  }

  /**
   * Unlock / re-auth that must not leave the previous Sa-Token session hanging: login first (public),
   * then best-effort logout of the still-current token, then apply the new session.
   */
  async function loginReplacingToken(
    username: string,
    password: string,
    captcha?: authApi.CaptchaAnswer
  ) {
    const result = await authApi.login({ username, password, ...captcha });
    try {
      if (token.value) await authApi.logout();
    } catch {
      // the old token may already be gone; the new one still proceeds
    }
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

  return {
    user,
    token,
    isLoggedIn,
    access,
    isSuperAdmin,
    isOwnScope,
    isPlatformAdmin,
    can,
    login,
    loginReplacingToken,
    logout,
    clearSession,
    refreshProfile
  };
});
