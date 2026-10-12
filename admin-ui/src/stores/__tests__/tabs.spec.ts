import { createPinia, setActivePinia } from "pinia";
import { nextTick } from "vue";
import { beforeEach, describe, expect, it } from "vitest";
import { TABS_STORAGE_KEY, homeAffix } from "@/utils/tabs";
import { useTabsStore } from "../tabs";

describe("tabs store", () => {
  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
  });

  it("drops a closed tab from the cache list and remounts only the refreshed page", async () => {
    const store = useTabsStore();
    store.restore(homeAffix(), () => true);
    store.open({
      path: "/kol/kols",
      titleKey: "route.kols",
      affix: false,
      componentName: "KolListPage"
    });
    expect(store.include).toEqual(["HomePage", "KolListPage"]);

    store.close("/kol/kols");
    expect(store.include).toEqual(["HomePage"]);

    store.open({
      path: "/kol/kols",
      titleKey: "route.kols",
      affix: false,
      componentName: "KolListPage"
    });
    store.refresh("/kol/kols");
    expect(store.include).not.toContain("KolListPage");
    expect(store.include).toContain("HomePage");
    expect(store.keyFor("/kol/kols")).toBe("/kol/kols:1");
    expect(store.keyFor("/home")).toBe("/home:0");
    await nextTick();
    expect(store.include).toContain("KolListPage");
  });

  it("clears the saved tabs", () => {
    const store = useTabsStore();
    store.restore(homeAffix(), () => true);
    store.open({
      path: "/kol/kols",
      titleKey: "route.kols",
      affix: false,
      componentName: "KolListPage"
    });
    expect(sessionStorage.getItem(TABS_STORAGE_KEY)).toContain("/kol/kols");
    store.reset();
    expect(sessionStorage.getItem(TABS_STORAGE_KEY)).toBeNull();
    expect(store.tabs).toEqual([]);
  });
});
