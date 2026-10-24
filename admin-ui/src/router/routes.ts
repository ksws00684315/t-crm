import type { RouteRecordRaw } from "vue-router";

/**
 * Static routes. Access is `meta.permission`, `meta.superAdmin` or `meta.platformAdmin`.
 * The menu is derived from the same data; nothing is fetched from the backend.
 * Company home has no permission code: every company account may open it.
 *
 * The tree follows the owner's 2026-10-20 menu decision (task 32 §2.1): seven first-level
 * groups mirroring the original system. Pages still under construction render the shared
 * coming-soon component; they stay in the menu.
 */
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
    redirect: "/home",
    children: [
      {
        path: "home",
        name: "Home",
        component: () => import("@/views/home/index.vue"),
        meta: {
          title: "route.home",
          icon: "HomeFilled",
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
            component: () => import("@/views/common/ComingSoon.vue"),
            meta: {
              title: "route.shopPerformance",
              permission: "dashboard:shop",
              componentName: "ComingSoonPage"
            }
          },
          {
            path: "best-sellers",
            name: "AnalyticsBestSellers",
            component: () => import("@/views/common/ComingSoon.vue"),
            meta: {
              title: "route.bestSellers",
              permission: "dashboard:shop",
              componentName: "ComingSoonPage"
            }
          },
          {
            path: "kol-ranking",
            name: "AnalyticsKolRanking",
            component: () => import("@/views/common/ComingSoon.vue"),
            meta: {
              title: "route.kolRanking",
              permission: "dashboard:kol",
              componentName: "ComingSoonPage"
            }
          },
          {
            path: "inviter-performance",
            name: "AnalyticsInviterPerformance",
            component: () => import("@/views/common/ComingSoon.vue"),
            meta: {
              title: "route.inviterPerformance",
              permission: "dashboard:team",
              componentName: "ComingSoonPage"
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
            component: () => import("@/views/order/order-query/index.vue"),
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
            component: () => import("@/views/common/ComingSoon.vue"),
            meta: {
              title: "route.kolIncome",
              permission: "commission:view",
              componentName: "ComingSoonPage"
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
              // Either code opens the page; the review drawer itself needs payout-account:review.
              permission: ["payout-account:review", "commission:view"],
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
            component: () => import("@/views/common/ComingSoon.vue"),
            meta: {
              title: "route.settlementDetails",
              permission: "commission:view",
              componentName: "ComingSoonPage"
            }
          },
          {
            path: "settlement-config",
            name: "FundSettlementConfig",
            component: () => import("@/views/common/ComingSoon.vue"),
            meta: {
              title: "route.settlementConfig",
              permission: "commission:view",
              componentName: "ComingSoonPage"
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
            path: "link-templates",
            name: "SystemLinkTemplates",
            component: () => import("@/views/system/link-template/index.vue"),
            meta: {
              title: "route.linkTemplates",
              superAdmin: true,
              componentName: "SystemLinkTemplatePage"
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
        path: "403",
        name: "Forbidden",
        component: () => import("@/views/error/403.vue"),
        meta: {
          title: "route.forbidden",
          hideInMenu: true,
          componentName: "ForbiddenPage",
          noTab: true
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
