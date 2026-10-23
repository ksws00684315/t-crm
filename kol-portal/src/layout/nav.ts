import { Goods, HomeFilled, List, Wallet } from "@element-plus/icons-vue";
import type { Component } from "vue";

/**
 * Side navigation entries (task 15b §4.3, shared with task 19b). Task 19b appends its orders
 * entry to this list; a merge keeps both sides' items. Task 58 adds one Element Plus icon per entry,
 * matching the admin console's side menu.
 */
export interface NavItem {
  name: string; // route name
  path: string;
  title: string; // locale key
  icon: Component;
}

export const NAV_ITEMS: NavItem[] = [
  { name: "Home", path: "/", title: "nav.home", icon: HomeFilled },
  { name: "PayoutAccounts", path: "/payout-accounts", title: "nav.payoutAccounts", icon: Wallet }, // 15b
  { name: "Orders", path: "/orders", title: "nav.orders", icon: List }, // 19b
  { name: "Items", path: "/items", title: "nav.items", icon: Goods } // 26
];
