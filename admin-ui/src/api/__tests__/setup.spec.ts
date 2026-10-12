import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { installGuard } from "@/router/guard";
import { routes } from "@/router/routes";
import { useReauthStore } from "@/stores/reauth";
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
  installGuard(router, () => ({ isLoggedIn: store.isLoggedIn, roles: store.roles }));
  setupHttp(router);
  return { store, router };
}

describe("401 handling", () => {
  beforeEach(() => localStorage.clear());

  function failWith(errorCode: string) {
    http.instance.defaults.adapter = async config => ({
      data: { code: 401, msg: "x", data: null, errorCode, traceId: "t" },
      status: 401,
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

  it("returns to the login page when the account is disabled", async () => {
    const { store, router } = appWithSession();
    await router.push("/system/users");
    failWith("AUTH_ACCOUNT_DISABLED");
    const failure = await http.request({ url: "/auth/me" }).catch((error: unknown) => error);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));

    expect((failure as ApiError).handled).toBe(true);
    expect(store.isLoggedIn).toBe(false);
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
    expect(router.currentRoute.value.query.redirect).toBe("/system/users");
    expect(useReauthStore().visible).toBe(false);
  });

  it("ignores a wrong password while the dialog is open and leaves for a lockout", async () => {
    const { store, router } = appWithSession();
    await router.push("/system/users");
    useReauthStore().open();
    failWith("AUTH_INVALID_CREDENTIALS");
    const wrong = await http.request({ url: "/auth/login" }).catch((error: unknown) => error);
    expect((wrong as ApiError).handled).toBe(true);
    expect(store.isLoggedIn).toBe(true);
    expect(router.currentRoute.value.path).toBe("/system/users");

    failWith("AUTH_ACCOUNT_LOCKED");
    await http.request({ url: "/auth/login" }).catch(() => undefined);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));
    expect(store.isLoggedIn).toBe(false);
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
    expect(store.roles).toEqual(["SUPER_ADMIN"]);
  });
});
