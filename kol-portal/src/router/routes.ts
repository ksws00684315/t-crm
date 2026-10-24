import type { RouteRecordRaw } from "vue-router";

export const routes: RouteRecordRaw[] = [
  ...(["activate", "reset"] as const).map(mode => ({
    path: `/${mode}`,
    name: mode,
    component: () => import("@/views/password-link/index.vue"),
    props: { mode },
    meta: { title: `route.${mode}`, public: true }
  })),
  {
    path: "/forgot",
    name: "Forgot",
    component: () => import("@/views/forgot/index.vue"),
    meta: { title: "route.forgot", public: true }
  },
  {
    path: "/login",
    name: "Login",
    component: () => import("@/views/login/index.vue"),
    meta: { title: "route.login", public: true }
  },
  {
    path: "/",
    component: () => import("@/layout/index.vue"),
    children: [
      {
        path: "income/prepays",
        name: "PrepayResults",
        component: () => import("@/views/income/prepay/index.vue"),
        meta: { title: "prepay.title", componentName: "PrepayResultsPage" }
      },
      {
        path: "account",
        name: "Account",
        component: () => import("@/views/account/index.vue"),
        meta: { title: "route.account" }
      },
      {
        path: "funds",
        name: "Funds",
        component: () => import("@/views/funds/index.vue"),
        meta: { title: "route.funds" }
      },
      {
        path: "payout-accounts",
        name: "PayoutAccounts",
        component: () => import("@/views/payout-accounts/index.vue"),
        meta: { title: "route.payoutAccounts" }
      },
      {
        path: "",
        name: "Home",
        component: () => import("@/views/home/index.vue"),
        meta: { title: "route.home" }
      },
      {
        path: "orders",
        name: "Orders",
        component: () => import("@/views/orders/index.vue"),
        meta: { title: "route.orders" }
      },
      {
        path: "items",
        name: "Items",
        redirect: "/"
      }
    ]
  },
  {
    path: "/register",
    name: "Register",
    component: () => import("@/views/register/index.vue"),
    meta: { title: "route.register", public: true }
  },
  {
    path: "/:pathMatch(.*)*",
    name: "NotFound",
    component: () => import("@/views/error/404.vue"),
    meta: { title: "route.notFound", public: true }
  }
];
