import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import { installGuard, resolveAccess, type AuthState } from "../guard";
import { routes } from "../routes";

const signedOut: AuthState = { isLoggedIn: false, roles: [] };
const operator: AuthState = { isLoggedIn: true, roles: ["OPERATOR"], dataScope: "ALL" };
const operatorOwn: AuthState = { isLoggedIn: true, roles: ["OPERATOR"], dataScope: "OWN" };
const superAdmin: AuthState = { isLoggedIn: true, roles: ["SUPER_ADMIN"], dataScope: "ALL" };
const finance: AuthState = { isLoggedIn: true, roles: ["FINANCE"], dataScope: "ALL" };
const platformAdmin: AuthState = { isLoggedIn: true, roles: ["PLATFORM_ADMIN"] };

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

  it("sends a platform administrator from login and home to company management", () => {
    expect(resolveAccess(target("/login", { public: true }), platformAdmin)).toBe(
      "/platform/tenants"
    );
    expect(
      resolveAccess(
        target("/home", { roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"] }),
        platformAdmin
      )
    ).toBe("/platform/tenants");
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

  it("rejects OPERATOR+OWN on hideForOwn routes (same as the menu)", () => {
    expect(
      resolveAccess(
        target("/product/kol-items", {
          roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"],
          hideForOwn: true
        }),
        operatorOwn
      )
    ).toBe("/403");
  });

  it("allows OPERATOR+ALL and FINANCE on hideForOwn routes", () => {
    const meta = {
      roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"] as const,
      hideForOwn: true
    };
    expect(resolveAccess(target("/product/kol-items", meta), operator)).toBe(true);
    expect(resolveAccess(target("/product/kol-items", meta), finance)).toBe(true);
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

  it("redirects OPERATOR+OWN away from /product/kol-items to 403", async () => {
    expect((await navigate(operatorOwn, "/product/kol-items")).path).toBe("/403");
  });

  it("opens /product/kol-items for OPERATOR+ALL", async () => {
    expect((await navigate(operator, "/product/kol-items")).name).toBe("KolItemList");
  });

  it("routes platform home to company management and blocks business pages", async () => {
    expect((await navigate(platformAdmin, "/")).name).toBe("PlatformTenants");
    expect((await navigate(platformAdmin, "/home")).name).toBe("PlatformTenants");
    for (const path of ["/kol/kols", "/system/users"]) {
      expect((await navigate(platformAdmin, path)).path).toBe("/403");
    }
  });

  it("denies company management to a company super administrator", async () => {
    expect((await navigate(superAdmin, "/platform/tenants")).path).toBe("/403");
  });

  it("restricts the system pages to SUPER_ADMIN and opens KOL management to every role", () => {
    const layout = routes.find(route => route.path === "/");
    const restricted = (layout?.children ?? [])
      .filter(route => route.meta?.roles?.length)
      .map(route => [route.path, route.meta?.roles]);
    expect(restricted).toEqual([
      ["home", ["SUPER_ADMIN", "OPERATOR", "FINANCE"]],
      ["platform/tenants", ["PLATFORM_ADMIN"]],
      ["kol/kols", ["SUPER_ADMIN", "OPERATOR", "FINANCE"]],
      ["product", ["SUPER_ADMIN", "OPERATOR", "FINANCE"]],
      ["system/users", ["SUPER_ADMIN"]],
      ["system/oper-logs", ["SUPER_ADMIN"]]
    ]);
  });
});
