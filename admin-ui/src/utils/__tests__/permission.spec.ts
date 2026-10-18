import { describe, expect, it } from "vitest";
import { routes } from "@/router/routes";
import {
  accessOf,
  accountLabel,
  buildMenu,
  canAccessRoute,
  canExport,
  groupPermissions,
  isStaff,
  isSuperAdminAccount,
  kolAbilities,
  linkAbilities,
  orderAbilities,
  permissionGroupLabel,
  permissionText,
  POST_TEMPLATES,
  productAbilities,
  SELECTABLE_ROLES,
  togglePermission,
  type AbilitySubject
} from "../permission";

function staff(dataScope: "ALL" | "OWN", permissions: readonly string[]): AbilitySubject {
  // Real sessions always carry the account status; enabled unless a test says otherwise.
  return { role: "STAFF", dataScope, permissions, postTemplate: "OPERATOR", status: 1 };
}

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

function titles(user: AbilitySubject | null): string[] {
  return buildMenu(routes, accessOf(user)).flatMap(item =>
    item.children?.length ? [item.title, ...item.children.map(child => child.title)] : [item.title]
  );
}

describe("accessOf", () => {
  it("grants every code to a super administrator and ignores the permissions array", () => {
    const access = accessOf({ ...superAdmin, permissions: [] });
    expect(access.isSuperAdmin).toBe(true);
    expect(access.isOwnScope).toBe(false);
    expect(access.can("kol:view")).toBe(true);
    expect(access.can("commission:freeze")).toBe(true);
  });

  it("grants a sub-account only the codes it holds", () => {
    const access = accessOf(staff("ALL", ["kol:view"]));
    expect(access.isSuperAdmin).toBe(false);
    expect(access.can("kol:view")).toBe(true);
    expect(access.can("kol:edit")).toBe(false);
  });

  it("treats OWN scope as staff plus OWN, never a super administrator", () => {
    expect(accessOf(staff("OWN", ["kol:view"])).isOwnScope).toBe(true);
    expect(accessOf(staff("ALL", ["kol:view"])).isOwnScope).toBe(false);
    expect(accessOf({ ...superAdmin, dataScope: "OWN" }).isOwnScope).toBe(false);
  });

  it("gives the platform administrator no company permission", () => {
    const access = accessOf(platformAdmin);
    expect(access.isPlatformAdmin).toBe(true);
    expect(access.can("kol:view")).toBe(false);
    expect(accessOf(null).can("data:export")).toBe(false);
  });
});

describe("labels", () => {
  it("shows the post name for a sub-account and the account type otherwise", () => {
    expect(accountLabel(staff("OWN", []))).toBe("运营");
    expect(accountLabel({ ...staff("ALL", []), postTemplate: "BD" })).toBe("商务");
    expect(accountLabel({ ...staff("ALL", []), postTemplate: null })).toBe("子账号");
    expect(accountLabel(superAdmin)).toBe("超级管理员");
    expect(accountLabel(platformAdmin)).toBe("平台管理员");
  });

  it("keeps platform administrator out of the company account types", () => {
    expect(SELECTABLE_ROLES).toEqual(["SUPER_ADMIN", "STAFF"]);
    expect(POST_TEMPLATES).toEqual(["OPERATOR", "BD"]);
    expect(isStaff(staff("ALL", []))).toBe(true);
    expect(isStaff(superAdmin)).toBe(false);
    expect(isSuperAdminAccount(superAdmin)).toBe(true);
    expect(isSuperAdminAccount(staff("ALL", []))).toBe(false);
  });
});

describe("permission text", () => {
  it("reads the language pack and shows an unknown code as itself", () => {
    expect(permissionText("kol:view", "name")).toBe("查看达人");
    expect(permissionText("kol:view", "desc").length).toBeGreaterThan(0);
    expect(permissionText("future:extra", "name")).toBe("future:extra");
    expect(permissionText("future:extra", "desc")).toBe("");
    expect(permissionGroupLabel("KOL")).toBe("达人");
    expect(permissionGroupLabel("NEWGROUP")).toBe("NEWGROUP");
  });

  it("keeps known groups first and appends a group the API adds", () => {
    expect(
      groupPermissions([
        { code: "data:export", group: "OTHER" },
        { code: "kol:view", group: "KOL" },
        { code: "future:extra", group: "NEWGROUP" }
      ]).map(group => group.group)
    ).toEqual(["KOL", "OTHER", "NEWGROUP"]);
  });
});

describe("togglePermission", () => {
  it("checks the required view permission together with an edit permission", () => {
    expect(togglePermission(["kol:view"], "kol:edit", true)).toEqual(["kol:view", "kol:edit"]);
    expect(togglePermission([], "product:status", true)).toEqual([
      "product:status",
      "product:view"
    ]);
    expect(togglePermission([], "order:manage", true)).toEqual(["order:manage", "order:view"]);
    expect(togglePermission([], "payout-account:review", true)).toEqual([
      "payout-account:review",
      "commission:view"
    ]);
  });

  it("clears every edit permission that depends on a cleared view permission", () => {
    expect(
      togglePermission(["product:view", "product:edit", "product:status"], "product:view", false)
    ).toEqual([]);
    expect(
      togglePermission(
        ["commission:view", "payout-account:review", "kol:view"],
        "commission:view",
        false
      )
    ).toEqual(["kol:view"]);
  });

  it("leaves codes without dependencies untouched in both directions", () => {
    expect(togglePermission(["kol:view"], "mystery:code", true)).toEqual([
      "kol:view",
      "mystery:code"
    ]);
    expect(togglePermission(["mystery:code", "kol:view"], "mystery:code", false)).toEqual([
      "kol:view"
    ]);
    // Clearing a view whose dependent is not in the set clears only the view.
    expect(togglePermission(["kol:view", "mystery:other"], "kol:view", false)).toEqual([
      "mystery:other"
    ]);
  });

  it("does not duplicate codes already in the set", () => {
    expect(togglePermission(["kol:view"], "kol:view", true)).toEqual(["kol:view"]);
  });
});

describe("buildMenu", () => {
  it("shows every company page to the super administrator", () => {
    expect(titles(superAdmin)).toEqual([
      "首页",
      "达人管理",
      "转移记录",
      "商品",
      "店铺管理",
      "原商品",
      "对应关系",
      "商品源表",
      "复制令牌",
      "订单",
      "订单列表",
      "人工匹配池",
      "资金",
      "收款账户审核",
      "用户管理",
      "代购平台模板",
      "操作日志"
    ]);
    const items = buildMenu(routes, accessOf(superAdmin));
    const paths = items.flatMap(item =>
      item.children?.length ? item.children.map(child => child.path) : [item.path]
    );
    expect(paths).toEqual([
      "/home",
      "/kol/kols",
      "/kol/bd-transfers",
      "/product/shops",
      "/product/products",
      "/product/kol-items",
      "/product/sheet-sources",
      "/product/copy-tokens",
      "/order/orders",
      "/order/unmatched",
      "/fund/payout-accounts",
      "/system/users",
      "/system/link-templates",
      "/system/oper-logs"
    ]);
  });

  it("shows only home and creators to a sub-account that has kol:view", () => {
    expect(titles(staff("ALL", ["kol:view"]))).toEqual(["首页", "达人管理"]);
    expect(titles(staff("OWN", ["kol:view"]))).toEqual(["首页", "达人管理"]);
  });

  it("hides mapping for an OWN sub-account that can view products", () => {
    expect(titles(staff("ALL", ["product:view"]))).toEqual([
      "首页",
      "商品",
      "店铺管理",
      "原商品",
      "对应关系",
      "商品源表"
    ]);
    expect(titles(staff("OWN", ["product:view"]))).toEqual(["首页", "商品", "店铺管理", "原商品"]);
  });

  it("shows the payout-account review to either of its two permission codes", () => {
    const expected = ["首页", "资金", "收款账户审核"];
    expect(titles(staff("ALL", ["payout-account:review"]))).toEqual(expected);
    expect(titles(staff("OWN", ["commission:view"]))).toEqual(expected);
  });

  it("hides the payout-account review without either permission code", () => {
    expect(titles(staff("ALL", ["kol:view"]))).not.toContain("资金");
  });

  it("shows the order list but not the matching pool without order:manage", () => {
    expect(titles(staff("ALL", ["order:view"]))).toEqual(["首页", "订单", "订单列表"]);
    expect(titles(staff("ALL", ["order:view", "order:manage"]))).toEqual([
      "首页",
      "订单",
      "订单列表",
      "人工匹配池"
    ]);
  });

  it("shows only company management to the platform administrator", () => {
    expect(titles(platformAdmin)).toEqual(["公司管理"]);
  });
});

describe("canAccessRoute", () => {
  it("matches the menu: OWN cannot open mapping, a missing code cannot open the page", () => {
    const mapping = { permission: "product:view" as const, hideForOwn: true };
    expect(canAccessRoute(mapping, accessOf(staff("OWN", ["product:view"])))).toBe(false);
    expect(canAccessRoute(mapping, accessOf(staff("ALL", ["product:view"])))).toBe(true);
    expect(canAccessRoute(mapping, accessOf(staff("ALL", [])))).toBe(false);
    expect(canAccessRoute({ superAdmin: true }, accessOf(staff("ALL", ["kol:view"])))).toBe(false);
    expect(canAccessRoute({ superAdmin: true }, accessOf(superAdmin))).toBe(true);
  });

  it("treats an array permission as any-of and a single code as before", () => {
    const review = {
      permission: ["payout-account:review", "commission:view"] as const
    };
    expect(canAccessRoute(review, accessOf(staff("ALL", ["payout-account:review"])))).toBe(true);
    expect(canAccessRoute(review, accessOf(staff("ALL", ["commission:view"])))).toBe(true);
    expect(
      canAccessRoute(review, accessOf(staff("ALL", ["payout-account:review", "commission:view"])))
    ).toBe(true);
    expect(canAccessRoute(review, accessOf(staff("ALL", ["kol:view"])))).toBe(false);
    expect(canAccessRoute(review, accessOf(staff("ALL", [])))).toBe(false);
    expect(canAccessRoute(review, accessOf(superAdmin))).toBe(true);
    expect(canAccessRoute(review, accessOf(platformAdmin))).toBe(false);
  });

  it("limits platform administrators to platform pages and hidden account pages", () => {
    expect(canAccessRoute({ platformAdmin: true }, accessOf(platformAdmin))).toBe(true);
    expect(canAccessRoute({ platformAdmin: true }, accessOf(superAdmin))).toBe(false);
    expect(canAccessRoute({ permission: "kol:view" }, accessOf(platformAdmin))).toBe(false);
    expect(canAccessRoute({}, accessOf(platformAdmin))).toBe(false);
    expect(canAccessRoute({ hideInMenu: true }, accessOf(platformAdmin))).toBe(true);
    expect(
      canAccessRoute({ hideInMenu: true, companyAccount: true }, accessOf(platformAdmin))
    ).toBe(false);
    expect(canAccessRoute({ hideInMenu: true, companyAccount: true }, accessOf(superAdmin))).toBe(
      true
    );
    expect(
      canAccessRoute({ hideInMenu: true, companyAccount: true }, accessOf(staff("ALL", [])))
    ).toBe(true);
    expect(canAccessRoute({}, accessOf(staff("ALL", [])))).toBe(true);
  });
});

describe("kolAbilities", () => {
  it("follows the permission matrix for creators", () => {
    expect(kolAbilities(null)).toEqual({
      canWrite: false,
      canChangeBd: false,
      canAssignBd: false,
      canFilterBd: false,
      canRegisterLink: false,
      canCopyToken: false,
      canClaimKol: false,
      canManageCleanup: false
    });
    expect(kolAbilities(superAdmin)).toEqual({
      canWrite: true,
      canChangeBd: true,
      canAssignBd: true,
      canFilterBd: true,
      canRegisterLink: true,
      canCopyToken: true,
      canClaimKol: false,
      canManageCleanup: true
    });
    expect(kolAbilities(staff("ALL", ["kol:view", "kol:edit"]))).toEqual({
      canWrite: true,
      canChangeBd: false,
      canAssignBd: true,
      canFilterBd: true,
      canRegisterLink: false,
      canCopyToken: false,
      canClaimKol: false,
      canManageCleanup: true
    });
    expect(kolAbilities(staff("OWN", ["kol:view", "kol:edit"]))).toEqual({
      canWrite: true,
      canChangeBd: false,
      canAssignBd: false,
      canFilterBd: false,
      canRegisterLink: false,
      canCopyToken: false,
      canClaimKol: true,
      canManageCleanup: false
    });
    expect(kolAbilities(staff("ALL", ["kol:view"]))).toEqual({
      canWrite: false,
      canChangeBd: false,
      canAssignBd: false,
      canFilterBd: true,
      canRegisterLink: false,
      canCopyToken: false,
      canClaimKol: false,
      canManageCleanup: false
    });
    expect(kolAbilities(staff("OWN", ["kol:register-link"]))).toEqual({
      canWrite: false,
      canChangeBd: false,
      canAssignBd: false,
      canFilterBd: false,
      canRegisterLink: true,
      canCopyToken: false,
      canClaimKol: false,
      canManageCleanup: false
    });
    expect(kolAbilities(staff("OWN", ["kol:copy-token"]))).toEqual({
      canWrite: false,
      canChangeBd: false,
      canAssignBd: false,
      canFilterBd: false,
      canRegisterLink: false,
      canCopyToken: true,
      canClaimKol: false,
      canManageCleanup: false
    });
  });

  it("gives claiming only to an enabled OWN-scope BD with kol:edit (25a §4.4)", () => {
    const bd = { role: "STAFF" as const, dataScope: "OWN" as const, permissions: ["kol:edit"] };
    expect(kolAbilities({ ...bd, status: 1 })).toEqual(
      expect.objectContaining({ canClaimKol: true })
    );
    expect(kolAbilities({ ...bd, status: 0 })).toEqual(
      expect.objectContaining({ canClaimKol: false })
    );
    expect(kolAbilities({ role: "STAFF", dataScope: "OWN", permissions: [] })).toEqual(
      expect.objectContaining({ canClaimKol: false })
    );
  });
});

describe("productAbilities", () => {
  it("follows the permission matrix for shops, products and mappings", () => {
    expect(productAbilities(null)).toEqual({
      canManageShop: false,
      canEditProduct: false,
      canChangeStatus: false,
      canViewMapping: false,
      canImportMapping: false
    });
    expect(productAbilities(superAdmin)).toEqual({
      canManageShop: true,
      canEditProduct: true,
      canChangeStatus: true,
      canViewMapping: true,
      canImportMapping: true
    });
    expect(
      productAbilities(staff("ALL", ["product:view", "product:edit", "product:status"]))
    ).toEqual({
      canManageShop: false,
      canEditProduct: true,
      canChangeStatus: true,
      canViewMapping: true,
      canImportMapping: true
    });
    expect(productAbilities(staff("OWN", ["product:view", "product:edit"]))).toEqual({
      canManageShop: false,
      canEditProduct: true,
      canChangeStatus: false,
      canViewMapping: false,
      canImportMapping: true
    });
    expect(productAbilities(staff("ALL", ["product:view"]))).toEqual({
      canManageShop: false,
      canEditProduct: false,
      canChangeStatus: false,
      canViewMapping: true,
      canImportMapping: false
    });
    expect(productAbilities(staff("ALL", ["product:status"]))).toEqual({
      canManageShop: false,
      canEditProduct: false,
      canChangeStatus: true,
      canViewMapping: false,
      canImportMapping: false
    });
  });
});

describe("linkAbilities", () => {
  it("requires product:view to see links and super administrator to rebuild the index", () => {
    expect(linkAbilities(null)).toEqual({ canViewLinks: false, canRebuildIndex: false });
    expect(linkAbilities(superAdmin)).toEqual({ canViewLinks: true, canRebuildIndex: true });
    expect(linkAbilities(staff("ALL", ["product:view"]))).toEqual({
      canViewLinks: true,
      canRebuildIndex: false
    });
    expect(linkAbilities(staff("OWN", ["kol:view"]))).toEqual({
      canViewLinks: false,
      canRebuildIndex: false
    });
  });
});

describe("orderAbilities", () => {
  it("follows the permission matrix for orders and the creator drawer", () => {
    expect(orderAbilities(null)).toEqual({ canManage: false, canViewKol: false });
    expect(orderAbilities(superAdmin)).toEqual({ canManage: true, canViewKol: true });
    expect(orderAbilities(staff("ALL", ["order:view"]))).toEqual({
      canManage: false,
      canViewKol: false
    });
    expect(orderAbilities(staff("ALL", ["order:view", "order:manage"]))).toEqual({
      canManage: true,
      canViewKol: false
    });
    expect(orderAbilities(staff("OWN", ["order:view", "order:manage", "kol:view"]))).toEqual({
      canManage: true,
      canViewKol: true
    });
  });
});

describe("canExport", () => {
  it("shows export only when the account has data:export", () => {
    expect(canExport(superAdmin)).toBe(true);
    expect(canExport(staff("ALL", ["data:export"]))).toBe(true);
    expect(canExport(staff("OWN", ["kol:view"]))).toBe(false);
    expect(canExport(null)).toBe(false);
  });
});
