import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { installGuard } from "@/router/guard";
import { routes } from "@/router/routes";
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

  it("clears the login state and returns to the login page, remembering the page", async () => {
    const { store, router } = appWithSession();
    await router.push("/system/users");
    expect(store.isLoggedIn).toBe(true);

    http.instance.defaults.adapter = async config => ({
      data: { code: 401, msg: "x", data: null, errorCode: "AUTH_UNAUTHORIZED", traceId: "t" },
      status: 401,
      statusText: "",
      headers: {},
      config
    });
    const failure = await http.request({ url: "/auth/me" }).catch((error: unknown) => error);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).handled).toBe(true);
    expect(store.isLoggedIn).toBe(false);
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
    expect(router.currentRoute.value.path).toBe("/login");
    expect(router.currentRoute.value.query.redirect).toBe("/system/users");
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
