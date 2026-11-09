import { describe, expect, it } from "vitest";
import type { UserVO } from "@/api/types";
import { routes } from "@/router/routes";
import {
  accessOf,
  buildMenu,
  canAccessRoute,
  EDITION_PERMISSION_CODES,
  editionOf,
  libraryAbilities,
  routeEditions,
  type AbilitySubject
} from "../permission";

/**
 * Company editions on the screen (ADR-038): the menu, the guard and the button helpers all read
 * one decision — the route belongs to the company's edition and the account holds its code.
 */

const distributionSuperAdmin: AbilitySubject = {
  role: "SUPER_ADMIN",
  dataScope: "ALL",
  permissions: [],
  postTemplate: null,
  edition: "DISTRIBUTION"
};

const librarySuperAdmin: AbilitySubject = {
  role: "SUPER_ADMIN",
  dataScope: "ALL",
  permissions: ["kol-library:view", "kol-library:edit"],
  postTemplate: null,
  edition: "KOL_LIBRARY"
};

const libraryStaff: AbilitySubject = {
  role: "STAFF",
  dataScope: "ALL",
  permissions: ["kol-library:view"],
  postTemplate: null,
  edition: "KOL_LIBRARY"
};

const distributionStaff: AbilitySubject = {
  role: "STAFF",
  dataScope: "ALL",
  permissions: ["kol:view", "product:view"],
  postTemplate: "OPERATOR",
  edition: "DISTRIBUTION"
};

function metaOf(path: string): Record<string, unknown> | undefined {
  const layout = routes.find(route => route.path === "/");
  return layout?.children?.find(route => route.path === path)?.meta;
}

function menuPaths(user: AbilitySubject): string[] {
  const walk = (items: ReturnType<typeof buildMenu>): string[] =>
    items.flatMap(item => [item.path, ...walk(item.children ?? [])]);
  return walk(buildMenu(routes, accessOf(user)));
}

describe("edition-aware access", () => {
  it("reads a missing or unknown edition as the distribution product", () => {
    expect(editionOf(null)).toBe("DISTRIBUTION");
    expect(editionOf({ edition: null })).toBe("DISTRIBUTION");
    expect(editionOf({ edition: "KOL_LIBRARY" })).toBe("KOL_LIBRARY");
    expect(accessOf({ ...libraryStaff, edition: undefined }).edition).toBe("DISTRIBUTION");
  });

  it("gives a super administrator only the codes of its edition", () => {
    const distribution = accessOf(distributionSuperAdmin);
    expect(distribution.can("kol:view")).toBe(true);
    expect(distribution.can("data:export")).toBe(true);
    expect(distribution.can("kol-library:view")).toBe(false);

    const library = accessOf(librarySuperAdmin);
    expect(library.can("kol-library:view")).toBe(true);
    expect(library.can("kol-library:edit")).toBe(true);
    expect(library.can("kol:view")).toBe(false);
    expect(library.can("product:view")).toBe(false);
    expect(library.can("data:export")).toBe(false);
    expect(EDITION_PERMISSION_CODES.KOL_LIBRARY).toEqual(["kol-library:view", "kol-library:edit"]);
  });

  it("refuses a route of the other edition even with its code stored", () => {
    // A staff account with a distribution code granted while it was in a distribution company.
    const stale: AbilitySubject = {
      ...libraryStaff,
      permissions: ["kol-library:view", "kol:view"]
    };
    expect(canAccessRoute(metaOf("kol"), accessOf(stale))).toBe(false);
    expect(canAccessRoute(metaOf("kol-library"), accessOf(stale))).toBe(true);
    expect(canAccessRoute(metaOf("kol-library"), accessOf(distributionStaff))).toBe(false);
  });

  it("declares the edition of every route, defaulting to the distribution product", () => {
    expect(routeEditions(metaOf("kol"))).toEqual(["DISTRIBUTION"]);
    expect(routeEditions(metaOf("kol-library"))).toEqual(["KOL_LIBRARY"]);
    expect(routeEditions(undefined)).toEqual(["DISTRIBUTION"]);
  });

  it("shows the creator library menu only to a library company", () => {
    const libraryMenu = menuPaths(librarySuperAdmin);
    expect(libraryMenu).toContain("/kol-library");
    expect(libraryMenu).toContain("/kol-library/kols");
    // Account management is the one other menu of the library (requirement 1.1.1).
    expect(libraryMenu).toContain("/system");
    expect(libraryMenu).toContain("/system/users");
    // No analytics, products, orders, creators, funds, bell or other system entries.
    expect(libraryMenu).not.toContain("/analytics");
    expect(libraryMenu).not.toContain("/product");
    expect(libraryMenu).not.toContain("/order");
    expect(libraryMenu).not.toContain("/kol");
    expect(libraryMenu).not.toContain("/fund");
    expect(libraryMenu).not.toContain("/notifications");
    expect(libraryMenu).not.toContain("/system/post-templates");
    expect(libraryMenu).not.toContain("/system/oper-logs");
    expect(libraryMenu).not.toContain("/system/tasks");

    const distributionMenu = menuPaths(distributionSuperAdmin);
    expect(distributionMenu).not.toContain("/kol-library");
    expect(distributionMenu).toContain("/kol");
  });

  it("hides the account screens from a library sub-account", () => {
    // Every company account may open the home route (a library company's screen sends it on).
    const menu = menuPaths(libraryStaff);
    expect(menu).toEqual(["/home", "/kol-library", "/kol-library/kols"]);
    expect(canAccessRoute(metaOf("system"), accessOf(libraryStaff))).toBe(false);
  });

  it("keeps the distribution menu unchanged for distribution accounts", () => {
    const menu = menuPaths(distributionStaff);
    expect(menu).toContain("/kol/kols");
    expect(menu).toContain("/product/shops");
    expect(menu).not.toContain("/kol-library");
  });

  it("lends library buttons from the edition and the role", () => {
    expect(libraryAbilities(librarySuperAdmin)).toEqual({ canEdit: true, canDelete: true });
    expect(libraryAbilities(libraryStaff)).toEqual({ canEdit: false, canDelete: false });
    // A library staff account with both codes may edit but never delete (requirement P1-15).
    expect(
      libraryAbilities({ ...libraryStaff, permissions: ["kol-library:view", "kol-library:edit"] })
    ).toEqual({ canEdit: true, canDelete: false });
    expect(libraryAbilities(distributionSuperAdmin)).toEqual({ canEdit: false, canDelete: false });
  });

  it("keeps the platform administrator out of the library", () => {
    const platform: Pick<UserVO, "role" | "dataScope" | "permissions"> & {
      edition: "DISTRIBUTION";
    } = { role: "PLATFORM_ADMIN", dataScope: null, permissions: [], edition: "DISTRIBUTION" };
    expect(canAccessRoute(metaOf("kol-library"), accessOf(platform))).toBe(false);
    expect(menuPaths(platform)).not.toContain("/kol-library");
  });
});
