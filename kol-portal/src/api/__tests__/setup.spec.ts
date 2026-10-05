import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { installGuard } from "@/router/guard";
import { routes } from "@/router/routes";
import { useSessionStore } from "@/stores/session";
import { loadSession, saveSession, SESSION_KEY } from "@/utils/session";
import { setupHttp } from "../setup";
import { http } from "../http";

function appWithSession() {
  saveSession({ token: "tok", expiresAt: Date.now() + 60_000 });
  setActivePinia(createPinia()); // a fresh store reads the saved session, like a page reload
  const store = useSessionStore();
  const router = createRouter({ history: createMemoryHistory(), routes });
  installGuard(router, () => ({ isLoggedIn: store.isLoggedIn }));
  setupHttp(router);
  return { store, router };
}

describe("401 handling", () => {
  beforeEach(() => localStorage.clear());

  it("clears the sign-in state and returns to /login", async () => {
    const { store, router } = appWithSession();
    await router.push("/");
    expect(store.isLoggedIn).toBe(true);

    http.instance.defaults.adapter = async config => ({
      data: { code: 401, msg: "x", data: null, errorCode: "AUTH_UNAUTHORIZED", traceId: "t" },
      status: 401,
      statusText: "",
      headers: {},
      config
    });
    await http.request({ url: "/me" }).catch(() => undefined);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));

    expect(store.isLoggedIn).toBe(false);
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
  });
});

describe("stored session", () => {
  beforeEach(() => localStorage.clear());

  it("ignores and removes an expired session", () => {
    saveSession({ token: "tok", expiresAt: Date.now() - 1 });
    expect(loadSession()).toBeNull();
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it("ignores and removes unreadable data", () => {
    localStorage.setItem(SESSION_KEY, "{not json");
    expect(loadSession()).toBeNull();
  });

  it("restores a valid session into the store", () => {
    const { store } = appWithSession();
    expect(store.isLoggedIn).toBe(true);
  });
});
