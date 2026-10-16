import type { RouteRecordRaw } from "vue-router";

/**
 * Static routes. Access is `meta.permission`, `meta.superAdmin` or `meta.platformAdmin`.
 * The menu is derived from the same data; nothing is fetched from the backend.
 * Company home has no permission code: every company account may open it.
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
        path: "kol/kols",
        name: "KolList",
        component: () => import("@/views/kol/index.vue"),
        meta: {
          title: "route.kols",
          icon: "Avatar",
          permission: "kol:view",
          componentName: "KolListPage"
        }
      },
      {
        path: "kol/bd-transfers",
        name: "KolBdTransfers",
        component: () => import("@/views/kol/bd-transfers/index.vue"),
        meta: {
          title: "route.bdTransfers",
          icon: "Switch",
          superAdmin: true,
          componentName: "KolBdTransferPage"
        }
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
            name: "ShopList",
            component: () => import("@/views/product/shop/index.vue"),
            meta: {
              title: "route.shops",
              permission: "product:view",
              componentName: "ShopListPage"
            }
          },
          {
            path: "products",
            name: "ProductList",
            component: () => import("@/views/product/product/index.vue"),
            meta: {
              title: "route.product",
              permission: "product:view",
              componentName: "ProductListPage"
            }
          },
          {
            path: "kol-items",
            name: "KolItemList",
            component: () => import("@/views/product/kol-item/index.vue"),
            meta: {
              title: "route.mappings",
              permission: "product:view",
              hideForOwn: true,
              componentName: "KolItemListPage"
            }
          }
        ]
      },
      {
        path: "system/users",
        name: "SystemUsers",
        component: () => import("@/views/system/user/index.vue"),
        meta: {
          title: "route.users",
          icon: "UserFilled",
          superAdmin: true,
          componentName: "SystemUserPage"
        }
      },
      {
        path: "system/link-templates",
        name: "SystemLinkTemplates",
        component: () => import("@/views/system/link-template/index.vue"),
        meta: {
          title: "route.linkTemplates",
          icon: "Link",
          superAdmin: true,
          componentName: "SystemLinkTemplatePage"
        }
      },
      {
        path: "system/oper-logs",
        name: "SystemOperLogs",
        component: () => import("@/views/system/oper-log/index.vue"),
        meta: {
          title: "route.auditLogs",
          icon: "Document",
          superAdmin: true,
          componentName: "SystemOperLogPage"
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
