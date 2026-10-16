import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { installGuard } from "@/router/guard";
import { routes } from "@/router/routes";
import { useReauthStore } from "@/stores/reauth";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { loadSession, saveSession, SESSION_KEY } from "@/utils/session";
import { ApiError } from "@/utils/http/error";
import { setupHttp } from "../setup";
import { http } from "../http";
import type { UserVO } from "../types";

const admin: UserVO = {
  id: "1",
  username: "root",
  nickname: "Root",
  email: null,
  role: "SUPER_ADMIN",
  postTemplate: null,
  permissions: [],
  dataScope: "ALL",
  tenantId: "1",
  tenantName: "默认公司",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T00:00:00Z"
};

function appWithSession() {
  setActivePinia(createPinia());
  saveSession({ token: "tok", expiresAt: Date.now() + 60_000, user: admin });
  setActivePinia(createPinia()); // a fresh store reads the saved session, like a page reload
  const store = useUserStore();
  const router = createRouter({ history: createMemoryHistory(), routes });
  installGuard(router, () => ({ isLoggedIn: store.isLoggedIn, ...store.access }));
  setupHttp(router);
  return { store, router };
}

describe("401 handling", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  function failWith(errorCode: string, status = 401) {
    http.instance.defaults.adapter = async config => ({
      data: { code: status, msg: "x", data: null, errorCode, traceId: "t" },
      status,
      statusText: "",
      headers: {},
      config
    });
  }

  it("keeps the session and opens one reauth dialog for AUTH_UNAUTHORIZED", async () => {
    const { store, router } = appWithSession();
    await router.push("/system/users");
    expect(store.isLoggedIn).toBe(true);
    failWith("AUTH_UNAUTHORIZED");

    const first = await http.request({ url: "/auth/me" }).catch((error: unknown) => error);
    const second = await http.request({ url: "/auth/me" }).catch((error: unknown) => error);

    expect(first).toBeInstanceOf(ApiError);
    expect((first as ApiError).handled).toBe(true);
    expect((second as ApiError).handled).toBe(true);
    expect(store.isLoggedIn).toBe(true);
    expect(localStorage.getItem(SESSION_KEY)).not.toBeNull();
    expect(router.currentRoute.value.path).toBe("/system/users");
    expect(useReauthStore().visible).toBe(true);
  });

  it("returns to the login page when the account is disabled and closes the reauth dialog", async () => {
    const { store, router } = appWithSession();
    await router.push("/system/users");
    useReauthStore().open();
    useTabsStore().open({
      path: "/system/users",
      titleKey: "route.users",
      affix: false,
      componentName: "SystemUserPage"
    });
    failWith("AUTH_ACCOUNT_DISABLED");
    const failure = await http.request({ url: "/auth/me" }).catch((error: unknown) => error);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));

    expect((failure as ApiError).handled).toBe(true);
    expect(store.isLoggedIn).toBe(false);
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
    expect(router.currentRoute.value.query.redirect).toBe("/system/users");
    expect(useReauthStore().visible).toBe(false);
    expect(useTabsStore().tabs).toEqual([]);
  });

  it("ignores a wrong password while the dialog is open", async () => {
    const { store, router } = appWithSession();
    await router.push("/system/users");
    useReauthStore().open();
    failWith("AUTH_INVALID_CREDENTIALS");
    const wrong = await http.request({ url: "/auth/login" }).catch((error: unknown) => error);
    expect((wrong as ApiError).handled).toBe(true);
    expect(store.isLoggedIn).toBe(true);
    expect(router.currentRoute.value.path).toBe("/system/users");
    expect(useReauthStore().visible).toBe(true);
  });

  it("closes reauth after an expired-session logout so the next login does not show the dialog", async () => {
    const { store, router } = appWithSession();
    await router.push("/system/users");
    failWith("AUTH_UNAUTHORIZED");
    // logout hits the expired token → handler opens reauth, then clearSession closes it
    await store.logout();
    expect(store.isLoggedIn).toBe(false);
    expect(useReauthStore().visible).toBe(false);

    // Same pinia survives into the next signed-in layout; the dialog must stay closed.
    store.$patch({}); // no-op keep store
    expect(useReauthStore().visible).toBe(false);
  });
});

describe("stored session", () => {
  beforeEach(() => localStorage.clear());

  it("ignores and removes an expired session", () => {
    saveSession({ token: "tok", expiresAt: Date.now() - 1, user: admin });
    expect(loadSession()).toBeNull();
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it("ignores and removes unreadable data", () => {
    localStorage.setItem(SESSION_KEY, "{not json");
    expect(loadSession()).toBeNull();
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it("restores a valid session into the store", () => {
    const { store } = appWithSession();
    expect(store.isLoggedIn).toBe(true);
    expect(store.isSuperAdmin).toBe(true);
    expect(store.access.can("kol:view")).toBe(true);
  });
});
