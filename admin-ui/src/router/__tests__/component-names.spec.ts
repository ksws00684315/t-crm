import type { RouteRecordRaw } from "vue-router";
import { describe, expect, it } from "vitest";
import { routes } from "../routes";

function walk(list: readonly RouteRecordRaw[]): RouteRecordRaw[] {
  return list.flatMap(route => [route, ...(route.children ? walk(route.children) : [])]);
}

const cacheable = walk(routes).filter(
  route =>
    typeof route.meta?.componentName === "string" &&
    !route.meta.noTab &&
    typeof route.component === "function"
);

// Page modules load at module scope, outside every test timeout: the first page to pull the shared
// element-plus / axios graph pays ~2s of inline transform per worker, and under the full parallel
// suite on CI that cost made in-test loads overrun even a 20s budget. Loading in parallel here
// keeps each page imported exactly once; the tests below only compare loaded names.
const loaded = await Promise.all(
  cacheable.map(route => (route.component as () => Promise<{ default: { name?: string } }>)())
);

describe("cacheable route component names", () => {
  it("covers exactly the cacheable pages", () => {
    expect(cacheable.map(route => route.meta?.componentName).sort()).toEqual(
      [
        "AccountPasswordPage",
        // One shared stateless placeholder backs every coming-soon route (task 32 §2.1).
        "ComingSoonPage",
        "ComingSoonPage",
        "ComingSoonPage",
        "ComingSoonPage",
        "ComingSoonPage",
        "ComingSoonPage",
        "ComingSoonPage",
        "ComingSoonPage",
        "CopyTokenPage",
        "FundPayoutPage",
        "HomePage",
        "KolItemListPage",
        "KolListPage",
        "NotificationPage",
        "OrderChainPage",
        "OrderQueryPage",
        "OrderRefundPage",
        "PlatformTenantPage",
        "PlatformSystemConfigPage",
        "ShopManagementPage",
        "SystemImportTaskPage",
        "SystemLinkTemplatePage",
        "SystemOperLogPage",
        "SystemPostTemplatePage",
        "SystemUserPage"
      ].sort()
    );
  });

  for (const [index, route] of cacheable.entries()) {
    it(`component of ${String(route.path)} is named ${String(route.meta?.componentName)}`, () => {
      expect(loaded[index].default.name).toBe(route.meta?.componentName);
    });
  }
});
