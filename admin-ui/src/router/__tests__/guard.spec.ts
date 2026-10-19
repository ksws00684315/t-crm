import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import { accessOf, type AbilitySubject } from "@/utils/permission";
import { installGuard, resolveAccess, type AuthState } from "../guard";
import { routes } from "../routes";

// The first real navigation compiles the login view, which now pulls in vue-i18n and both
// locale packs. Import it here so that cost stays outside the 5s test timeout.
import "@/views/login/index.vue";

function session(user: AbilitySubject | null): AuthState {
  return { isLoggedIn: user !== null, ...accessOf(user) };
}

const signedOut = session(null);
const viewer: AbilitySubject = {
  role: "STAFF",
  dataScope: "ALL",
  permissions: ["kol:view"],
  postTemplate: "OPERATOR"
};
const productAll: AbilitySubject = {
  role: "STAFF",
  dataScope: "ALL",
  permissions: ["product:view"],
  postTemplate: "OPERATOR"
};
const productOwn: AbilitySubject = { ...productAll, dataScope: "OWN", postTemplate: "BD" };
const superAdmin: AbilitySubject = {
  role: "SUPER_ADMIN",
  dataScope: "ALL",
  permissions: [],
  postTemplate: null
};
const platformAdmin: AbilitySubject = {
  role: "PLATFORM_ADMIN",
  dataScope: null,
  permissions: [],
  postTemplate: null
};

function target(path: string, meta: Record<string, unknown> = {}) {
  return { path, fullPath: path, meta };
}

describe("resolveAccess", () => {
  it("redirects a signed-out user to /login and remembers the target", () => {
    expect(resolveAccess(target("/system/users", { superAdmin: true }), signedOut)).toEqual({
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
    expect(resolveAccess(target("/login", { public: true }), session(viewer))).toBe("/");
  });

  it("sends a platform administrator from login and home to company management", () => {
    expect(resolveAccess(target("/login", { public: true }), session(platformAdmin))).toBe(
      "/platform/tenants"
    );
    expect(resolveAccess(target("/home"), session(platformAdmin))).toBe("/platform/tenants");
  });

  it("rejects a route the account cannot open with the 403 page", () => {
    expect(resolveAccess(target("/system/users", { superAdmin: true }), session(viewer))).toBe(
      "/403"
    );
    expect(
      resolveAccess(target("/kol/kols", { permission: "kol:view" }), session(productAll))
    ).toBe("/403");
  });

  it("allows a super administrator onto a super-admin route", () => {
    expect(resolveAccess(target("/system/users", { superAdmin: true }), session(superAdmin))).toBe(
      true
    );
  });

  it("allows any signed-in company account when the route declares no permission", () => {
    expect(resolveAccess(target("/home", { title: "首页" }), session(viewer))).toBe(true);
  });

  it("rejects OWN on hideForOwn routes (same as the menu)", () => {
    expect(
      resolveAccess(
        target("/kol/kol-items", { permission: "product:view", hideForOwn: true }),
        session(productOwn)
      )
    ).toBe("/403");
  });

  it("allows a non-OWN account that has product:view on hideForOwn routes", () => {
    const meta = { permission: "product:view" as const, hideForOwn: true };
    expect(resolveAccess(target("/kol/kol-items", meta), session(productAll))).toBe(true);
    expect(resolveAccess(target("/kol/kol-items", meta), session(superAdmin))).toBe(true);
  });
});

describe("installGuard with the real route table", () => {
  async function navigate(user: AbilitySubject | null, path: string) {
    const router = createRouter({ history: createMemoryHistory(), routes });
    installGuard(router, () => session(user));
    await router.push(path);
    return router.currentRoute.value;
  }

  it("keeps a signed-out user on the login page with a redirect query", async () => {
    const route = await navigate(null, "/system/users");
    expect(route.path).toBe("/login");
    expect(route.query.redirect).toBe("/system/users");
  });

  it("denies the user list, link templates and the audit log to a sub-account", async () => {
    expect((await navigate(viewer, "/system/users")).path).toBe("/403");
    expect((await navigate(viewer, "/system/link-templates")).path).toBe("/403");
    expect((await navigate(viewer, "/system/oper-logs")).path).toBe("/403");
  });

  it("denies a route whose permission code is missing", async () => {
    expect((await navigate(productAll, "/kol/kols")).path).toBe("/403");
  });

  it("opens the user list, link templates and the audit log for the super administrator", async () => {
    expect((await navigate(superAdmin, "/system/users")).name).toBe("SystemUsers");
    expect((await navigate(superAdmin, "/system/link-templates")).name).toBe("SystemLinkTemplates");
    expect((await navigate(superAdmin, "/system/oper-logs")).name).toBe("SystemOperLogs");
  });

  it("redirects OWN away from /kol/kol-items to 403", async () => {
    expect((await navigate(productOwn, "/kol/kol-items")).path).toBe("/403");
  });

  it("opens /kol/kol-items for a non-OWN account with product:view", async () => {
    expect((await navigate(productAll, "/kol/kol-items")).name).toBe("KolItemList");
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

  it("declares access with a permission code, superAdmin or platformAdmin", () => {
    const layout = routes.find(route => route.path === "/");
    const flags = (layout?.children ?? []).map(route => [
      route.path,
      route.meta?.permission ?? null,
      route.meta?.superAdmin === true,
      route.meta?.platformAdmin === true,
      route.meta?.hideForOwn === true
    ]);
    expect(flags).toEqual([
      ["home", null, false, false, false],
      ["platform/tenants", null, false, true, false],
      ["analytics", null, false, false, false],
      ["product", null, false, false, false],
      ["order", null, false, false, false],
      ["kol", null, false, false, false],
      ["fund", null, false, false, false],
      ["system", null, false, false, false],
      ["notifications", null, false, false, false],
      ["account/password", null, false, false, false],
      ["403", null, false, false, false],
      // Merged and removed pages survive as plain redirects (task 32 §2.1).
      ["product/products", null, false, false, false],
      ["product/sheet-sources", null, false, false, false],
      ["order/unmatched", null, false, false, false],
      ["fund/payout-accounts", null, false, false, false],
      ["kol/bd-transfers", null, false, false, false]
    ]);
    const product = layout?.children?.find(route => route.path === "product");
    expect(
      product?.children?.map(route => [
        route.path,
        route.meta?.permission ?? null,
        route.meta?.hideForOwn === true
      ])
    ).toEqual([
      ["shops", "product:view", false],
      ["copy-tokens", "kol:copy-token", false],
      ["tasks", "product:view", false]
    ]);
    const order = layout?.children?.find(route => route.path === "order");
    expect(
      order?.children?.map(route => [
        route.path,
        route.meta?.permission ?? null,
        route.meta?.hideForOwn === true
      ])
    ).toEqual([
      ["orders", "order:view", false],
      ["refunds", "order:view", false]
    ]);
    // The payout-account review accepts either of its two codes (task 15b §4.2.1).
    const fund = layout?.children?.find(route => route.path === "fund");
    expect(fund?.children?.map(route => route.meta?.permission ?? null)).toEqual([
      ["payout-account:review", "commission:view"],
      "commission:view",
      "commission:view"
    ]);
    for (const route of layout?.children ?? []) {
      if (route.meta) expect(route.meta).not.toHaveProperty("roles");
    }
  });
});
