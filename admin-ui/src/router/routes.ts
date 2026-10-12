import type { RouteRecordRaw } from "vue-router";

/**
 * Static routes. Access is decided here, in `meta.roles`; the menu is derived from the same data
 * and nothing is fetched from the backend. The backend enforces the same roles independently.
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
          roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"],
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
          roles: ["PLATFORM_ADMIN"],
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
          roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"],
          componentName: "KolListPage"
        }
      },
      {
        path: "product",
        name: "ProductGroup",
        redirect: "/product/shops",
        meta: {
          title: "route.products",
          icon: "Goods",
          menuGroup: true,
          roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"]
        },
        children: [
          {
            path: "shops",
            name: "ShopList",
            component: () => import("@/views/product/shop/index.vue"),
            meta: {
              title: "route.shops",
              roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"],
              componentName: "ShopListPage"
            }
          },
          {
            path: "products",
            name: "ProductList",
            component: () => import("@/views/product/product/index.vue"),
            meta: {
              title: "route.product",
              roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"],
              componentName: "ProductListPage"
            }
          },
          {
            path: "kol-items",
            name: "KolItemList",
            component: () => import("@/views/product/kol-item/index.vue"),
            meta: {
              title: "route.mappings",
              roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"],
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
          roles: ["SUPER_ADMIN"],
          componentName: "SystemUserPage"
        }
      },
      {
        path: "system/oper-logs",
        name: "SystemOperLogs",
        component: () => import("@/views/system/oper-log/index.vue"),
        meta: {
          title: "route.auditLogs",
          icon: "Document",
          roles: ["SUPER_ADMIN"],
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
