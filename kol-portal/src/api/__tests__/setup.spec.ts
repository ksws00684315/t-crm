import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { installGuard } from "@/router/guard";
import { routes } from "@/router/routes";
import { useSessionStore } from "@/stores/session";
import { loadSession, saveSession, SESSION_KEY } from "@/utils/session";
import { ApiError } from "@/utils/http/error";
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

  it("keeps the full account target when a 401 expires the session", async () => {
    const { store, router } = appWithSession();
    await router.push("/account?tab=password#form");
    http.instance.defaults.adapter = async config => ({
      data: {
        code: 401,
        msg: "never display",
        data: null,
        errorCode: "AUTH_UNAUTHORIZED",
        traceId: "test"
      },
      status: 401,
      statusText: "",
      headers: {},
      config
    });
    const error = await http.request({ url: "/me" }).catch(error => error);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));
    expect(router.currentRoute.value.query.redirect).toBe("/account?tab=password#form");
    expect(store.kol).toBeNull();
    if (!(error instanceof ApiError)) throw new Error("Expected ApiError");
    expect(error.handled).toBe(true);
  });

  it("leaves a login 401 available for inline feedback", async () => {
    const { store, router } = appWithSession();
    store.clearSession();
    await router.push("/login?redirect=/account");
    http.instance.defaults.adapter = async config => ({
      data: {
        code: 401,
        msg: "never display",
        data: null,
        errorCode: "AUTH_INVALID_CREDENTIALS",
        traceId: "test"
      },
      status: 401,
      statusText: "",
      headers: {},
      config
    });
    const error = await http.request({ url: "/auth/login" }).catch(error => error);
    if (!(error instanceof ApiError)) throw new Error("Expected ApiError");
    expect(error.handled).toBe(false);
    expect(error.displayMessage).toContain("Incorrect");
    expect(router.currentRoute.value.fullPath).toBe("/login?redirect=/account");
  });

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
