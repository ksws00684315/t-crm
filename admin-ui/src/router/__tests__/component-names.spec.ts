import type { RouteRecordRaw } from "vue-router";
import { describe, expect, it } from "vitest";
import { routes } from "../routes";

function walk(list: readonly RouteRecordRaw[]): RouteRecordRaw[] {
  return list.flatMap(route => [route, ...(route.children ? walk(route.children) : [])]);
}

describe("cacheable route component names", () => {
  it("matches the page component's name option", async () => {
    const cacheable = walk(routes).filter(
      route =>
        typeof route.meta?.componentName === "string" &&
        !route.meta.noTab &&
        typeof route.component === "function"
    );
    expect(cacheable.map(route => route.meta?.componentName).sort()).toEqual(
      [
        "AccountPasswordPage",
        "HomePage",
        "KolItemListPage",
        "KolListPage",
        "PlatformTenantPage",
        "ProductListPage",
        "ShopListPage",
        "SystemLinkTemplatePage",
        "SystemOperLogPage",
        "SystemUserPage"
      ].sort()
    );
    for (const route of cacheable) {
      const loaded = await (route.component as () => Promise<{ default: { name?: string } }>)();
      expect(loaded.default.name).toBe(route.meta?.componentName);
    }
  });
});
