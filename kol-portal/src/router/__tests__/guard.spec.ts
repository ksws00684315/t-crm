import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import { installGuard, resolveAccess } from "../guard";
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
