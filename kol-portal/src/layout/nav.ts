import { HomeFilled, List, User, Wallet } from "@element-plus/icons-vue";
import type { Component } from "vue";

/**
 * Creator portal entry points, using the task 58 shell and icon treatment.
 */
export interface NavItem {
  name: string; // route name
  path: string;
  title: string; // locale key
  icon: Component;
}

export const NAV_ITEMS: NavItem[] = [
  { name: "Home", path: "/", title: "nav.home", icon: HomeFilled },
  { name: "Orders", path: "/orders", title: "nav.orders", icon: List },
  { name: "Funds", path: "/funds", title: "nav.funds", icon: Wallet },
  { name: "Account", path: "/account", title: "nav.account", icon: User }
];
