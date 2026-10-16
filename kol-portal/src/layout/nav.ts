export interface NavItem {
  name: string;
  path: string;
  title: string;
}

export const NAV_ITEMS: NavItem[] = [
  { name: "Home", path: "/", title: "nav.home" },
  { name: "Orders", path: "/orders", title: "nav.orders" }
];
