import { describe, expect, it } from "vitest";
import type { MenuItem } from "@/utils/permission";
import {
  flattenMenu,
  loadRecentMenus,
  matchMenus,
  rememberRecentMenu,
  RECENT_MENU_LIMIT
} from "../menu-search";

const menu: MenuItem[] = [
  { path: "/home", title: "首页" },
  {
    path: "/product",
    title: "商品",
    children: [
      { path: "/product/shops", title: "店铺管理" },
      { path: "/product/products", title: "原商品" }
    ]
  },
  { path: "/system/users", title: "用户管理" }
];

describe("menu search", () => {
  it("matches the menu name and its group without caring about case", () => {
    const entries = flattenMenu(menu);
    expect(matchMenus(entries, "用户").map(entry => entry.path)).toEqual(["/system/users"]);
    expect(matchMenus(entries, "商品").map(entry => entry.path)).toEqual([
      "/product/shops",
      "/product/products"
    ]);
    expect(matchMenus(entries, "SHOP")).toEqual([]);
    expect(matchMenus(entries, " 店铺 ")).toEqual([
      { path: "/product/shops", title: "店铺管理", group: "商品" }
    ]);
  });

  it("keeps six recent menus per user and ignores a broken payload", () => {
    localStorage.clear();
    for (let index = 1; index <= RECENT_MENU_LIMIT + 1; index += 1) {
      rememberRecentMenu("alice", `/page-${index}`);
    }
    rememberRecentMenu("bob", "/home");
    expect(loadRecentMenus("alice")).toEqual([
      "/page-7",
      "/page-6",
      "/page-5",
      "/page-4",
      "/page-3",
      "/page-2"
    ]);
    expect(loadRecentMenus("bob")).toEqual(["/home"]);

    localStorage.setItem("tcrm.admin.recent", "{");
    expect(loadRecentMenus("alice")).toEqual([]);
  });
});
