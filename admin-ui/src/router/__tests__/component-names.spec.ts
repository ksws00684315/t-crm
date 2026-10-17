import type { RouteRecordRaw } from "vue-router";
import { describe, expect, it } from "vitest";
import { routes } from "../routes";

// The order pages pull in the shared drawers and API modules; import them here so that cost
// stays outside the 5s test timeout, like the login view in guard.spec.
import "@/views/order/index.vue";
import "@/views/order/unmatched.vue";

function walk(list: readonly RouteRecordRaw[]): RouteRecordRaw[] {
  return list.flatMap(route => [route, ...(route.children ? walk(route.children) : [])]);
}

describe("cacheable route component names", () => {
  // Loading every page module (the first one pays for the full Element Plus bundle) no longer
  // fits the 5s default with an eleventh page; the assertions are unchanged.
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
        "KolBdTransferPage",
        "KolItemListPage",
        "KolListPage",
        "NotificationPage",
        "OrderListPage",
        "OrderUnmatchedPage",
        "PayoutAccountReviewPage",
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
  }, 20000);
});
