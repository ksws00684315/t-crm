import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import { installGuard, resolveAccess, type AuthState } from "../guard";
import { routes } from "../routes";

const signedOut: AuthState = { isLoggedIn: false, roles: [] };
const operator: AuthState = { isLoggedIn: true, roles: ["OPERATOR"] };
const superAdmin: AuthState = { isLoggedIn: true, roles: ["SUPER_ADMIN"] };

function target(path: string, meta: Record<string, unknown> = {}) {
  return { path, fullPath: path, meta };
}

describe("resolveAccess", () => {
  it("redirects a signed-out user to /login and remembers the target", () => {
    expect(resolveAccess(target("/system/users", { roles: ["SUPER_ADMIN"] }), signedOut)).toEqual({
      path: "/login",
      query: { redirect: "/system/users" }
    });
  });

  it("does not remember the home page as a redirect target", () => {
    expect(resolveAccess(target("/"), signedOut)).toEqual({ path: "/login", query: undefined });
  });

  it("lets a signed-out user open public routes", () => {
    expect(resolveAccess(target("/login", { public: true }), signedOut)).toBe(true);
  });

  it("sends a signed-in user away from the login page", () => {
    expect(resolveAccess(target("/login", { public: true }), operator)).toBe("/");
  });

  it("rejects a role that is not allowed with the 403 page", () => {
    expect(resolveAccess(target("/system/users", { roles: ["SUPER_ADMIN"] }), operator)).toBe(
      "/403"
    );
  });

  it("allows a matching role", () => {
    expect(resolveAccess(target("/system/users", { roles: ["SUPER_ADMIN"] }), superAdmin)).toBe(
      true
    );
  });

  it("allows any signed-in role when the route declares no roles", () => {
    expect(resolveAccess(target("/home", { title: "首页" }), operator)).toBe(true);
  });
});

describe("installGuard with the real route table", () => {
  async function navigate(auth: AuthState, path: string) {
    const router = createRouter({ history: createMemoryHistory(), routes });
    installGuard(router, () => auth);
    await router.push(path);
    return router.currentRoute.value;
  }

  it("keeps a signed-out user on the login page with a redirect query", async () => {
    const route = await navigate(signedOut, "/system/users");
    expect(route.path).toBe("/login");
    expect(route.query.redirect).toBe("/system/users");
  });

  it("denies the user list and the audit log to an operator", async () => {
    expect((await navigate(operator, "/system/users")).path).toBe("/403");
    expect((await navigate(operator, "/system/oper-logs")).path).toBe("/403");
  });

  it("opens the user list and the audit log for the super administrator", async () => {
    expect((await navigate(superAdmin, "/system/users")).name).toBe("SystemUsers");
    expect((await navigate(superAdmin, "/system/oper-logs")).name).toBe("SystemOperLogs");
  });

  it("restricts exactly the two system pages to SUPER_ADMIN", () => {
    const layout = routes.find(route => route.path === "/");
    const restricted = (layout?.children ?? [])
      .filter(route => route.meta?.roles?.length)
      .map(route => [route.path, route.meta?.roles]);
    expect(restricted).toEqual([
      ["system/users", ["SUPER_ADMIN"]],
      ["system/oper-logs", ["SUPER_ADMIN"]]
    ]);
  });
});
