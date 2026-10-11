import { describe, expect, it } from "vitest";
import { routes } from "@/router/routes";
import {
  buildMenu,
  canAccessRoute,
  hasAnyRole,
  kolAbilities,
  linkAbilities,
  productAbilities
} from "../permission";

describe("hasAnyRole", () => {
  it("allows everyone when no roles are required", () => {
    expect(hasAnyRole(undefined, ["FINANCE"])).toBe(true);
    expect(hasAnyRole([], [])).toBe(true);
  });

  it("requires at least one matching role", () => {
    expect(hasAnyRole(["SUPER_ADMIN"], ["OPERATOR"])).toBe(false);
    expect(hasAnyRole(["SUPER_ADMIN", "FINANCE"], ["FINANCE"])).toBe(true);
    expect(hasAnyRole(["SUPER_ADMIN"], [])).toBe(false);
  });
});

describe("buildMenu", () => {
  const titles = (roles: Parameters<typeof buildMenu>[1], scope: "ALL" | "OWN" = "ALL") =>
    buildMenu(routes, roles, scope).flatMap(item =>
      item.children?.length ? [item.title, ...item.children.map(c => c.title)] : [item.title]
    );

  it("shows user management and the audit log only to the super administrator", () => {
    expect(titles(["SUPER_ADMIN"])).toEqual([
      "首页",
      "达人管理",
      "商品",
      "店铺管理",
      "原商品",
      "对应关系",
      "用户管理",
      "操作日志"
    ]);
    expect(titles(["OPERATOR"])).toEqual([
      "首页",
      "达人管理",
      "商品",
      "店铺管理",
      "原商品",
      "对应关系"
    ]);
    expect(titles(["FINANCE"])).toEqual([
      "首页",
      "达人管理",
      "商品",
      "店铺管理",
      "原商品",
      "对应关系"
    ]);
  });

  it("hides mapping menu for an OWN operator", () => {
    expect(titles(["OPERATOR"], "OWN")).toEqual(["首页", "达人管理", "商品", "店铺管理", "原商品"]);
  });

  it("shows only company management to the platform administrator", () => {
    expect(titles(["PLATFORM_ADMIN"])).toEqual(["公司管理"]);
  });

  it("builds absolute paths and hides the login, password and error pages", () => {
    const items = buildMenu(routes, ["SUPER_ADMIN"]);
    const paths = items.flatMap(item =>
      item.children?.length ? item.children.map(c => c.path) : [item.path]
    );
    expect(paths).toEqual([
      "/home",
      "/kol/kols",
      "/product/shops",
      "/product/products",
      "/product/kol-items",
      "/system/users",
      "/system/oper-logs"
    ]);
  });
});

describe("kolAbilities", () => {
  it("lets FINANCE only read and keeps BD management from a BD", () => {
    expect(kolAbilities({ role: "SUPER_ADMIN", dataScope: "ALL" })).toEqual({
      canWrite: true,
      canManageBd: true
    });
    expect(kolAbilities({ role: "OPERATOR", dataScope: "ALL" })).toEqual({
      canWrite: true,
      canManageBd: true
    });
    expect(kolAbilities({ role: "OPERATOR", dataScope: "OWN" })).toEqual({
      canWrite: true,
      canManageBd: false
    });
    expect(kolAbilities({ role: "FINANCE", dataScope: "ALL" })).toEqual({
      canWrite: false,
      canManageBd: false
    });
    expect(kolAbilities(null)).toEqual({ canWrite: false, canManageBd: false });
  });

  it("shows the KOL menu to every role", () => {
    for (const role of ["SUPER_ADMIN", "OPERATOR", "FINANCE"] as const) {
      expect(buildMenu(routes, [role]).map(item => item.title)).toContain("达人管理");
    }
  });
});

describe("productAbilities", () => {
  it("matches the task 05 permission matrix", () => {
    expect(productAbilities({ role: "SUPER_ADMIN", dataScope: "ALL" })).toEqual({
      canManageShop: true,
      canWriteProduct: true,
      canViewMapping: true,
      canImportMapping: true
    });
    expect(productAbilities({ role: "OPERATOR", dataScope: "ALL" })).toEqual({
      canManageShop: false,
      canWriteProduct: true,
      canViewMapping: true,
      canImportMapping: true
    });
    expect(productAbilities({ role: "OPERATOR", dataScope: "OWN" })).toEqual({
      canManageShop: false,
      canWriteProduct: false,
      canViewMapping: false,
      canImportMapping: false
    });
    expect(productAbilities({ role: "FINANCE", dataScope: "ALL" })).toEqual({
      canManageShop: false,
      canWriteProduct: false,
      canViewMapping: true,
      canImportMapping: false
    });
  });
});

describe("canAccessRoute", () => {
  const mappingMeta = {
    roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"] as const,
    hideForOwn: true
  };

  it("matches buildMenu: OWN operator cannot open mapping", () => {
    expect(canAccessRoute(mappingMeta, ["OPERATOR"], "OWN")).toBe(false);
    expect(canAccessRoute(mappingMeta, ["OPERATOR"], "ALL")).toBe(true);
    expect(canAccessRoute(mappingMeta, ["FINANCE"], "ALL")).toBe(true);
  });

  it("still rejects roles that are not allowed", () => {
    expect(canAccessRoute({ roles: ["SUPER_ADMIN"] }, ["OPERATOR"], "ALL")).toBe(false);
  });

  it("limits platform administrators to platform pages and roleless account/error pages", () => {
    expect(canAccessRoute({ roles: ["PLATFORM_ADMIN"] }, ["PLATFORM_ADMIN"], null)).toBe(true);
    expect(
      canAccessRoute({ roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"] }, ["PLATFORM_ADMIN"], null)
    ).toBe(false);
    expect(canAccessRoute({}, ["PLATFORM_ADMIN"], null)).toBe(true);
  });
});

describe("linkAbilities", () => {
  it("lets every role view links and only SUPER_ADMIN rebuild the index", () => {
    expect(linkAbilities(null)).toEqual({ canViewLinks: false, canRebuildIndex: false });
    expect(linkAbilities({ role: "SUPER_ADMIN", dataScope: "ALL" })).toEqual({
      canViewLinks: true,
      canRebuildIndex: true
    });
    for (const user of [
      { role: "OPERATOR", dataScope: "ALL" },
      { role: "OPERATOR", dataScope: "OWN" },
      { role: "FINANCE", dataScope: "ALL" }
    ] as const) {
      expect(linkAbilities(user)).toEqual({ canViewLinks: true, canRebuildIndex: false });
    }
  });
});
