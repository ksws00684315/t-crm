import { describe, expect, it, vi } from "vitest";
import {
  TAB_LIMIT,
  TABS_STORAGE_KEY,
  affixFor,
  closeAll,
  closeLeft,
  closeOthers,
  closeRight,
  closeTab,
  homeAffix,
  loadStoredTabs,
  openTab,
  restoreTabs,
  saveStoredTabs,
  type TabRecord,
  type TabSnapshot
} from "../tabs";

function tab(path: string, affix = false): TabRecord {
  return { path, titleKey: `route.${path}`, affix, componentName: path };
}

function state(
  paths: string[],
  active: string,
  affixPath = "/home",
  userId: string | null = "u1"
): TabSnapshot {
  return {
    v: 1,
    active,
    tabs: paths.map(path => tab(path, path === affixPath)),
    userId
  };
}

describe("tabs", () => {
  it("opens one tab per path and ignores a query-only difference", () => {
    const first = openTab(state(["/home"], "/home"), tab("/kol/kols"));
    const again = openTab(first, { ...tab("/kol/kols"), titleKey: "route.kols" });
    expect(again.tabs.map(item => item.path)).toEqual(["/home", "/kol/kols"]);
    expect(again.active).toBe("/kol/kols");
  });

  it("activates the right neighbor when the current tab closes, else the left", () => {
    const opened = state(["/home", "/kol/kols", "/product/shops"], "/kol/kols");
    expect(closeTab(opened, "/kol/kols").active).toBe("/product/shops");
    expect(closeTab(state(["/home", "/kol/kols"], "/kol/kols"), "/kol/kols").active).toBe("/home");
  });

  it("does not close an affix tab", () => {
    const opened = state(["/home", "/kol/kols"], "/home");
    expect(closeTab(opened, "/home")).toEqual(opened);
  });

  it("keeps the affix when closing others, the left side, the right side, or all", () => {
    const opened = state(["/home", "/a", "/b", "/c"], "/b");
    expect(closeOthers(opened, "/b").tabs.map(item => item.path)).toEqual(["/home", "/b"]);
    expect(closeLeft(opened, "/b").tabs.map(item => item.path)).toEqual(["/home", "/b", "/c"]);
    expect(closeRight(opened, "/b").tabs.map(item => item.path)).toEqual(["/home", "/a", "/b"]);
    expect(closeAll(opened).tabs.map(item => item.path)).toEqual(["/home"]);
    expect(closeAll(opened).active).toBe("/home");
  });

  it("drops the oldest tab that is neither current nor affix past the limit", () => {
    let current = state(["/home"], "/home");
    for (let index = 1; index <= TAB_LIMIT; index += 1) {
      current = openTab(current, tab(`/p/${index}`));
    }
    expect(current.tabs).toHaveLength(TAB_LIMIT);
    expect(current.tabs[0].path).toBe("/home");
    expect(current.tabs.some(item => item.path === "/p/1")).toBe(false);
    expect(current.tabs.some(item => item.path === "/p/2")).toBe(true);
    expect(current.active).toBe(`/p/${TAB_LIMIT}`);
  });

  it("restores saved tabs, drops ones the user cannot open, and survives a bad payload", () => {
    const raw = JSON.stringify(
      state(["/home", "/kol/kols", "/system/users", "/secret"], "/system/users")
    );
    const restored = restoreTabs(raw, homeAffix(), path => path !== "/secret", "u1");
    expect(restored.tabs.map(item => item.path)).toEqual(["/home", "/kol/kols", "/system/users"]);
    expect(restored.active).toBe("/system/users");
    expect(restoreTabs("{", homeAffix(), () => true, "u1").tabs).toEqual([homeAffix()]);
    expect(restoreTabs("null", homeAffix(), () => true, "u1").active).toBe("/home");
  });

  it("does not throw when sessionStorage is unusable and clears on reset", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(loadStoredTabs(homeAffix(), () => true).tabs).toEqual([homeAffix()]);
    expect(() => saveStoredTabs(state(["/home"], "/home"))).not.toThrow();
    vi.restoreAllMocks();
    saveStoredTabs(state(["/home", "/kol/kols"], "/kol/kols"));
    expect(sessionStorage.getItem(TABS_STORAGE_KEY)).toContain("/kol/kols");
    sessionStorage.clear();
  });

  it("pins 公司管理 for a platform administrator and 工作台 otherwise", () => {
    expect(affixFor(true).path).toBe("/platform/tenants");
    expect(affixFor(false).path).toBe("/home");
  });

  it("discards tabs saved for another user", () => {
    const raw = JSON.stringify(state(["/home", "/kol/kols"], "/kol/kols", "/home", "alice"));
    const restored = restoreTabs(raw, homeAffix(), () => true, "bob");
    expect(restored.tabs.map(item => item.path)).toEqual(["/home"]);
    expect(restored.userId).toBe("bob");
  });
});
