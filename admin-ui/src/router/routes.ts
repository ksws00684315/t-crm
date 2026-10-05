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
    meta: { title: "登录", public: true }
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
        meta: { title: "首页", icon: "HomeFilled" }
      },
      {
        path: "kol/kols",
        name: "KolList",
        component: () => import("@/views/kol/index.vue"),
        meta: {
          title: "达人管理",
          icon: "Avatar",
          roles: ["SUPER_ADMIN", "OPERATOR", "FINANCE"]
        }
      },
      {
        path: "system/users",
        name: "SystemUsers",
        component: () => import("@/views/system/user/index.vue"),
        meta: { title: "用户管理", icon: "UserFilled", roles: ["SUPER_ADMIN"] }
      },
      {
        path: "system/oper-logs",
        name: "SystemOperLogs",
        component: () => import("@/views/system/oper-log/index.vue"),
        meta: { title: "操作日志", icon: "Document", roles: ["SUPER_ADMIN"] }
      },
      {
        path: "account/password",
        name: "AccountPassword",
        component: () => import("@/views/account/password.vue"),
        meta: { title: "修改密码", hideInMenu: true }
      },
      {
        path: "403",
        name: "Forbidden",
        component: () => import("@/views/error/403.vue"),
        meta: { title: "无权访问", hideInMenu: true }
      }
    ]
  },
  {
    path: "/:pathMatch(.*)*",
    name: "NotFound",
    component: () => import("@/views/error/404.vue"),
    meta: { title: "页面不存在", public: true }
  }
];
