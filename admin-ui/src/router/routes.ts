import type { RouteRecordRaw } from "vue-router";
import { useUserStore } from "@/stores/user";
import { homePathFor } from "@/utils/tabs";

/**
 * Static routes. Access is `meta.editions` (default: the distribution product, ADR-038),
 * `meta.permission`, `meta.superAdmin` or `meta.platformAdmin`.
 * The menu is derived from the same data; nothing is fetched from the backend.
 * Company home has no permission code: every company account may open it.
 *
 * The tree follows the owner's 2026-10-20 menu decision (task 32 §2.1): seven first-level
 * groups mirroring the original system. Pages still under construction render the shared
 * coming-soon component; they stay in the menu.
 */
/** The home path of the signed-in company; workbench unless it uses the creator-library edition. */
function companyHome(): string {
  try {
    // Read outside a running app (route tests) has no store: the workbench is the default home.
    return homePathFor(useUserStore().user?.edition === "KOL_LIBRARY");
  } catch {
    return homePathFor(false);
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: "/login",
    name: "Login",
    component: () => import("@/views/login/index.vue"),
    meta: { title: "route.login", public: true }
  },
  {
    path: "/",
    component: () => import("@/layout/index.vue"),
    // "/" leads to the company's home: the workbench, or the creator list for a creator-library
    // company (ADR-038 §3.3). The decision is a function so a company switch is honoured; it is
    // kept off the static "/home" redirect, which would leave a library company on the workbench.
    redirect: () => companyHome(),
    children: [
      {
        path: "home",
        name: "Home",
        component: () => import("@/views/home/index.vue"),
        meta: {
          title: "route.home",
          icon: "HomeFilled",
          // Every company account may open its home; the screen sends a library company on.
          editions: ["DISTRIBUTION", "KOL_LIBRARY"],
          componentName: "HomePage"
        }
      },
      {
        path: "platform/tenants",
        name: "PlatformTenants",
        component: () => import("@/views/platform/tenant/index.vue"),
        meta: {
          title: "route.tenants",
          icon: "OfficeBuilding",
          platformAdmin: true,
          componentName: "PlatformTenantPage"
        }
      },
      {
        path: "platform/system-configs",
        name: "PlatformSystemConfigs",
        component: () => import("@/views/platform/system-config/index.vue"),
        meta: {
          title: "route.systemConfigs",
          icon: "Setting",
          platformAdmin: true,
          componentName: "PlatformSystemConfigPage"
        }
      },
      {
        path: "analytics",
        name: "AnalyticsGroup",
        redirect: "/analytics/shop-performance",
        meta: {
          title: "route.analytics",
          icon: "TrendCharts",
          menuGroup: true
        },
        children: [
          {
            path: "shop-performance",
            name: "AnalyticsShopPerformance",
            component: () => import("@/views/analytics/AnalyticsPage.vue"),
            meta: {
              title: "route.shopPerformance",
              permission: "dashboard:shop",
              componentName: "AnalyticsPage"
            }
          },
          {
            path: "best-sellers",
            name: "AnalyticsBestSellers",
            component: () => import("@/views/analytics/AnalyticsPage.vue"),
            meta: {
              title: "route.bestSellers",
              permission: "dashboard:shop",
              componentName: "AnalyticsPage"
            }
          },
          {
            path: "kol-ranking",
            name: "AnalyticsKolRanking",
            component: () => import("@/views/analytics/AnalyticsPage.vue"),
            meta: {
              title: "route.kolRanking",
              permission: "dashboard:kol",
              componentName: "AnalyticsPage"
            }
          },
          {
            path: "inviter-performance",
            name: "AnalyticsInviterPerformance",
            component: () => import("@/views/analytics/AnalyticsPage.vue"),
            meta: {
              title: "route.inviterPerformance",
              permission: "dashboard:team",
              componentName: "AnalyticsPage"
            }
          }
        ]
      },
      {
        path: "product",
        name: "ProductGroup",
        redirect: "/product/shops",
        meta: {
          title: "route.products",
          icon: "Goods",
          menuGroup: true
        },
        children: [
          {
            path: "shops",
            name: "ShopManagement",
            component: () => import("@/views/product/shop-management/index.vue"),
            meta: {
              title: "route.shopManagement",
              permission: "product:view",
              componentName: "ShopManagementPage"
            }
          },
          {
            path: "copy-tokens",
            name: "CopyTokenList",
            component: () => import("@/views/product/copy-token/index.vue"),
            meta: {
              title: "route.copyTokens",
              permission: "kol:copy-token",
              componentName: "CopyTokenPage"
            }
          },
          {
            path: "tasks",
            name: "ProductTasks",
            component: () => import("@/views/product/copy-job/index.vue"),
            meta: {
              title: "route.productTasks",
              permission: "product:view",
              componentName: "CopyJobPage"
            }
          },
          {
            // Keep the existing URL so saved tabs and bookmarks remain valid.
            path: "/system/link-templates",
            name: "SystemLinkTemplates",
            component: () => import("@/views/system/link-template/index.vue"),
            meta: {
              title: "route.linkTemplates",
              superAdmin: true,
              componentName: "SystemLinkTemplatePage"
            }
          }
        ]
      },
      {
        path: "order",
        name: "OrderGroup",
        redirect: "/order/orders",
        meta: {
          title: "route.orders",
          icon: "List",
          menuGroup: true
        },
        children: [
          {
            path: "orders",
            name: "OrderQuery",
            component: () => import("@/views/order/index.vue"),
            meta: {
              title: "route.orderQuery",
              permission: "order:view",
              componentName: "OrderQueryPage"
            }
          },
          {
            path: "refunds",
            name: "OrderRefunds",
            component: () => import("@/views/order/refund/index.vue"),
            meta: {
              title: "route.refundQuery",
              permission: "order:view",
              componentName: "OrderRefundPage"
            }
          },
          {
            // Order chain (09.04): opened in its own browser tab from the order list.
            path: "orders/:id/chain",
            name: "OrderChain",
            component: () => import("@/views/order/chain/index.vue"),
            meta: {
              title: "route.orderChain",
              permission: "order:view",
              hideInMenu: true,
              componentName: "OrderChainPage"
            }
          }
        ]
      },
      {
        path: "kol",
        name: "KolGroup",
        redirect: "/kol/kols",
        meta: {
          title: "route.kolManage",
          icon: "Avatar",
          menuGroup: true
        },
        children: [
          {
            path: "kols",
            name: "KolList",
            component: () => import("@/views/kol/index.vue"),
            meta: {
              title: "route.kols",
              permission: "kol:view",
              componentName: "KolListPage"
            }
          },
          {
            path: "kol-items",
            name: "KolItemList",
            component: () => import("@/views/product/kol-item/index.vue"),
            meta: {
              title: "route.kolItems",
              permission: "product:view",
              componentName: "KolItemListPage"
            }
          },
          {
            path: "income",
            name: "KolIncome",
            component: () => import("@/views/kol/income/index.vue"),
            meta: {
              title: "route.kolIncome",
              permission: "commission:view",
              componentName: "KolIncomePage"
            }
          }
        ]
      },
      {
        path: "fund",
        name: "FundGroup",
        redirect: "/fund/payouts",
        meta: {
          title: "route.fund",
          icon: "Wallet",
          menuGroup: true
        },
        children: [
          {
            path: "payouts",
            name: "FundPayouts",
            component: () => import("@/views/fund/payout/index.vue"),
            meta: {
              title: "route.payouts",
              permission: ["commission:view"],
              componentName: "FundPayoutPage"
            }
          },
          {
            path: "prepays",
            name: "PrepayTasks",
            component: () => import("@/views/fund/prepay/index.vue"),
            meta: {
              title: "prepay.title",
              permission: [
                "commission:view",
                "prepay:review",
                "prepay:finance-review",
                "prepay:pay"
              ],
              companyAccount: true,
              componentName: "PrepayTasksPage"
            }
          },
          {
            path: "settlement-details",
            name: "FundSettlementDetails",
            component: () => import("@/views/fund/settlement-details/index.vue"),
            meta: {
              title: "route.settlementDetails",
              permission: "commission:view",
              componentName: "SettlementDetailsPage"
            }
          },
          {
            path: "settlement-config",
            name: "FundSettlementConfig",
            component: () => import("@/views/fund/settlement-config/index.vue"),
            meta: {
              title: "route.settlementConfig",
              permission: "commission:view",
              componentName: "SettlementConfigPage"
            }
          }
        ]
      },
      {
        path: "system",
        name: "SystemGroup",
        redirect: "/system/users",
        meta: {
          title: "route.system",
          icon: "Setting",
          menuGroup: true
        },
        children: [
          {
            path: "users",
            name: "SystemUsers",
            component: () => import("@/views/system/user/index.vue"),
            meta: {
              title: "route.users",
              superAdmin: true,
              // Account management is also the library's only other menu (ADR-038 §3.2).
              editions: ["DISTRIBUTION", "KOL_LIBRARY"],
              componentName: "SystemUserPage"
            }
          },
          {
            path: "post-templates",
            name: "SystemPostTemplates",
            component: () => import("@/views/system/post-template/index.vue"),
            meta: {
              title: "route.postTemplates",
              superAdmin: true,
              componentName: "SystemPostTemplatePage"
            }
          },
          {
            path: "tasks",
            name: "SystemTasks",
            component: () => import("@/views/system/import-task/index.vue"),
            meta: {
              title: "route.importTasks",
              componentName: "SystemImportTaskPage"
            }
          },
          {
            path: "oper-logs",
            name: "SystemOperLogs",
            component: () => import("@/views/system/oper-log/index.vue"),
            meta: {
              title: "route.auditLogs",
              superAdmin: true,
              platformAdmin: true,
              // The audit log stays in the distribution product; a library company has none.
              editions: ["DISTRIBUTION"],
              componentName: "SystemOperLogPage"
            }
          }
        ]
      },
      {
        path: "notifications",
        name: "Notifications",
        component: () => import("@/views/notification/index.vue"),
        meta: {
          title: "route.notifications",
          hideInMenu: true,
          companyAccount: true,
          componentName: "NotificationPage"
        }
      },
      {
        path: "account/resignation",
        name: "AccountResignation",
        component: () => import("@/views/account/resignation.vue"),
        meta: {
          title: "route.resignation",
          hideInMenu: true,
          companyAccount: true,
          componentName: "AccountResignationPage"
        }
      },
      {
        path: "account/password",
        name: "AccountPassword",
        component: () => import("@/views/account/password.vue"),
        meta: { title: "route.password", hideInMenu: true, componentName: "AccountPasswordPage" }
      },
      {
        // TikTok creator library (ADR-038, requirement 1.2.5): list, detail, and the full-page form.
        path: "kol-library",
        name: "KolLibraryGroup",
        redirect: "/kol-library/kols",
        meta: {
          title: "route.kolLibrary",
          icon: "Avatar",
          menuGroup: true,
          editions: ["KOL_LIBRARY"]
        },
        children: [
          {
            path: "kols",
            name: "LibraryKolList",
            component: () => import("@/views/kol-library/kol-list/index.vue"),
            meta: {
              title: "route.libraryKols",
              permission: "kol-library:view",
              editions: ["KOL_LIBRARY"],
              componentName: "LibraryKolListPage"
            }
          },
          {
            path: "kols/new",
            name: "LibraryKolCreate",
            component: () => import("@/views/kol-library/kol-form/index.vue"),
            meta: {
              title: "route.libraryKolNew",
              permission: "kol-library:edit",
              editions: ["KOL_LIBRARY"],
              hideInMenu: true,
              componentName: "LibraryKolFormPage"
            }
          },
          {
            path: "kols/:id",
            name: "LibraryKolDetail",
            component: () => import("@/views/kol-library/kol-detail/index.vue"),
            meta: {
              title: "route.libraryKolDetail",
              permission: "kol-library:view",
              editions: ["KOL_LIBRARY"],
              hideInMenu: true,
              componentName: "LibraryKolDetailPage"
            }
          },
          {
            path: "kols/:id/edit",
            name: "LibraryKolEdit",
            component: () => import("@/views/kol-library/kol-form/index.vue"),
            meta: {
              title: "route.libraryKolEdit",
              permission: "kol-library:edit",
              editions: ["KOL_LIBRARY"],
              hideInMenu: true,
              componentName: "LibraryKolFormPage"
            }
          }
        ]
      },
      {
        path: "403",
        name: "Forbidden",
        component: () => import("@/views/error/403.vue"),
        meta: {
          title: "route.forbidden",
          hideInMenu: true,
          componentName: "ForbiddenPage",
          noTab: true,
          // Open to every signed-in company account: the guard must not block the page it sends
          // people to, or the redirect loops (a library company opening a distribution route).
          editions: ["DISTRIBUTION", "KOL_LIBRARY"]
        }
      },
      // Merged and removed pages keep working as redirects, so bookmarks, notification links
      // and restored tabs land on the new location (task 32 §2.1).
      {
        path: "product/products",
        redirect: "/product/shops"
      },
      {
        path: "product/sheet-sources",
        redirect: "/product/shops"
      },
      {
        path: "order/unmatched",
        redirect: "/order/orders"
      },
      {
        path: "fund/payout-accounts",
        redirect: "/fund/payouts"
      },
      {
        path: "kol/bd-transfers",
        redirect: "/system/oper-logs"
      }
    ]
  },
  {
    path: "/:pathMatch(.*)*",
    name: "NotFound",
    component: () => import("@/views/error/404.vue"),
    meta: { title: "route.notFound", public: true }
  }
];
