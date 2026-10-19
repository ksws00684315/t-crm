import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it } from "vitest";
import { useLocaleStore } from "@/stores/locale";
import { installGuard, resolveAccess } from "../guard";
import { applyDocumentTitle } from "../title";
import { routes } from "../routes";

function target(path: string, meta: Record<string, unknown> = {}) {
  return { path, fullPath: path, meta };
}

describe("resolveAccess", () => {
  it("redirects a signed-out visitor to /login and remembers the target", () => {
    expect(resolveAccess(target("/orders"), { isLoggedIn: false })).toEqual({
      path: "/login",
      query: { redirect: "/orders" }
    });
  });

  it("does not remember the home page as a redirect target", () => {
    expect(resolveAccess(target("/"), { isLoggedIn: false })).toEqual({
      path: "/login",
      query: undefined
    });
  });

  it("lets a signed-out visitor open public routes", () => {
    expect(resolveAccess(target("/login", { public: true }), { isLoggedIn: false })).toBe(true);
  });

  it("sends a signed-in user away from /login", () => {
    expect(resolveAccess(target("/login", { public: true }), { isLoggedIn: true })).toBe("/");
  });

  it("lets a signed-in user open protected routes", () => {
    expect(resolveAccess(target("/"), { isLoggedIn: true })).toBe(true);
  });
});

describe("installGuard with the real route table", () => {
  async function navigate(isLoggedIn: boolean, path: string) {
    const router = createRouter({ history: createMemoryHistory(), routes });
    installGuard(router, () => ({ isLoggedIn }));
    await router.push(path);
    return router.currentRoute.value;
  }

  it("redirects / to /login when signed out", async () => {
    expect((await navigate(false, "/")).path).toBe("/login");
  });

  it("opens / when signed in", async () => {
    expect((await navigate(true, "/")).name).toBe("Home");
  });
});

describe("auth route access", () => {
  it.each(["/activate?token=test", "/reset?token=test", "/forgot", "/register?code=test"])(
    "opens public %s without a session",
    async path => {
      const router = createRouter({ history: createMemoryHistory(), routes });
      installGuard(router, () => ({ isLoggedIn: false }));
      await router.push(path);
      expect(router.currentRoute.value.fullPath).toBe(path);
    }
  );
  it("lets a signed-in user open a register link", async () => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    installGuard(router, () => ({ isLoggedIn: true }));
    await router.push("/register?code=test");
    expect(router.currentRoute.value.fullPath).toBe("/register?code=test");
  });

  it("requires a session for the account page", async () => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    installGuard(router, () => ({ isLoggedIn: false }));
    await router.push("/account");
    expect(router.currentRoute.value.fullPath).toBe("/login?redirect=/account");
  });
});

describe("document title", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    useLocaleStore().setLocale("en-US");
  });

  async function navigate(isLoggedIn: boolean, path: string) {
    const router = createRouter({ history: createMemoryHistory(), routes });
    installGuard(router, () => ({ isLoggedIn }));
    await router.push(path);
  }

  it("translates the route title key into English", async () => {
    await navigate(false, "/login");
    expect(document.title).toBe("Sign in | T-CRM Creator Portal");
  });

  it("translates the route title key into Chinese", async () => {
    useLocaleStore().setLocale("zh-CN");
    await navigate(true, "/");
    expect(document.title).toBe("首页 | T-CRM Creator Portal");
  });

  it("uses the app name alone when a route has no title key", () => {
    applyDocumentTitle({});
    expect(document.title).toBe("T-CRM Creator Portal");
  });
});
