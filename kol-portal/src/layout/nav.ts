/**
 * Header navigation entries (task 15b §4.3, shared with task 19b). Task 19b appends its orders
 * entry to this list; a merge keeps both sides' items.
 */
export interface NavItem {
  name: string; // route name
  path: string;
  title: string; // locale key
}

export const NAV_ITEMS: NavItem[] = [
  { name: "Home", path: "/", title: "nav.home" },
  { name: "PayoutAccounts", path: "/payout-accounts", title: "nav.payoutAccounts" }, // 15b
  { name: "Orders", path: "/orders", title: "nav.orders" }, // 19b
  { name: "Items", path: "/items", title: "nav.items" }, // 26
  { name: "PrepayResults", path: "/income/prepays", title: "prepay.title" }
];
