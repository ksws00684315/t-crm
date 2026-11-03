import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { defineComponent, nextTick } from "vue";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import { useTabsStore } from "@/stores/tabs";
import TabBar from "../TabBar.vue";

const Blank = defineComponent({ render: () => null });

function routes() {
  return [
    { path: "/", redirect: "/home" },
    { path: "/home", component: Blank, meta: { title: "route.home" } },
    { path: "/order/orders", component: Blank, meta: { title: "route.orders" } },
    { path: "/kol/kols", component: Blank, meta: { title: "route.kols" } }
  ];
}

describe("TabBar keyboard and semantics (task 102 L-088)", () => {
  let wrapper: VueWrapper | null = null;

  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
  });

  afterEach(() => {
    wrapper?.unmount();
    wrapper = null;
  });

  async function mountBar(): Promise<{ bar: VueWrapper; router: Router }> {
    const router = createRouter({ history: createMemoryHistory(), routes: routes() });
    const tabs = useTabsStore();
    tabs.open({ path: "/home", titleKey: "route.home", affix: true, componentName: "HomePage" });
    tabs.open({
      path: "/order/orders",
      titleKey: "route.orders",
      affix: false,
      componentName: "OrderPage"
    });
    tabs.open({
      path: "/kol/kols",
      titleKey: "route.kols",
      affix: false,
      componentName: "KolPage"
    });
    await router.push("/kol/kols");
    await router.isReady();
    wrapper = mount(TabBar, {
      attachTo: document.body,
      global: { plugins: [ElementPlus, i18n, router] }
    });
    await nextTick();
    return { bar: wrapper, router };
  }

  it("exposes a tablist with one selected tab and a single tab stop", async () => {
    const { bar } = await mountBar();
    expect(bar.get('[data-test="tab-scroll"]').attributes("role")).toBe("tablist");
    const tabs = bar.findAll('[data-test="tab"]');
    expect(tabs).toHaveLength(3);
    expect(tabs.map(tab => tab.attributes("role"))).toEqual(["tab", "tab", "tab"]);
    expect(tabs.map(tab => tab.attributes("aria-selected"))).toEqual(["false", "false", "true"]);
    expect(tabs.map(tab => tab.attributes("tabindex"))).toEqual(["-1", "-1", "0"]);
    // The close button stays a real button inside every closable tab.
    const closes = bar.findAll('[data-test="tab-close"]');
    expect(closes).toHaveLength(2);
    for (const close of closes) expect(close.element.tagName).toBe("BUTTON");
  });

  it("moves focus with the arrow keys without navigating, then opens on Enter", async () => {
    const { bar, router } = await mountBar();
    const tabs = bar.findAll('[data-test="tab"]');
    await tabs[2].trigger("keydown", { key: "ArrowLeft" });
    await nextTick();
    expect(document.activeElement).toBe(tabs[1].element);
    expect(tabs[1].attributes("tabindex")).toBe("0");
    expect(tabs[2].attributes("tabindex")).toBe("-1");
    expect(router.currentRoute.value.path).toBe("/kol/kols");

    await tabs[1].trigger("keydown", { key: "Enter" });
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/order/orders");
  });

  it("supports Home and End, and Space opens the focused tab", async () => {
    const { bar, router } = await mountBar();
    const tabs = bar.findAll('[data-test="tab"]');
    await tabs[2].trigger("keydown", { key: "Home" });
    await nextTick();
    expect(document.activeElement).toBe(tabs[0].element);
    await tabs[0].trigger("keydown", { key: "End" });
    await nextTick();
    expect(document.activeElement).toBe(tabs[2].element);

    await tabs[2].trigger("keydown", { key: " " });
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/kol/kols");
  });

  it("lets the close button work without opening the tab it belongs to", async () => {
    const { bar, router } = await mountBar();
    const closeOrder = bar.findAll('[data-test="tab-close"]')[0];
    await closeOrder.trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/kol/kols");
    expect(bar.findAll('[data-test="tab"]').map(tab => tab.attributes("data-path"))).toEqual([
      "/home",
      "/kol/kols"
    ]);
  });

  it("follows the neighbour when the active tab is closed", async () => {
    const { bar, router } = await mountBar();
    const closeActive = bar.findAll('[data-test="tab-close"]')[1];
    await closeActive.trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/order/orders");
  });

  describe("stylesheet", () => {
    const source = readFileSync(resolve(__dirname, "../TabBar.vue"), "utf8");
    const scoped = source.slice(
      source.indexOf("<style scoped>") + "<style scoped>".length,
      source.indexOf("</style>")
    );

    function declarationsFor(selector: string): Record<string, string> {
      const index = scoped.indexOf(selector);
      expect(index, `${selector} must stay in the tab bar stylesheet`).toBeGreaterThanOrEqual(0);
      const open = scoped.indexOf("{", index);
      const close = scoped.indexOf("}", open);
      return Object.fromEntries(
        scoped
          .slice(open + 1, close)
          .split(";")
          .map(line => line.split(":"))
          .filter(parts => parts.length >= 2)
          .map(parts => [parts[0].trim(), parts.slice(1).join(":").trim()])
      );
    }

    it("pins the tab strip to 40px so flex pages cannot compress it", () => {
      const bar = declarationsFor(".tabs {");
      expect(bar.height).toBe("40px");
      expect(bar["min-height"]).toBe("40px");
      expect(bar.flex).toBe("0 0 auto");
    });

    it("marks the selected tab with the primary colour, 600 weight and a 2px indicator", () => {
      // L-099: visual chrome lives on .tabs__tab (role=tab); close is a sibling.
      const active = declarationsFor(".tabs__item.is-active .tabs__tab {");
      expect(active.color).toBe("var(--tcrm-color-primary-text)");
      expect(active["font-weight"]).toBe("600");
      const indicator = declarationsFor(".tabs__item.is-active .tabs__tab::after");
      expect(indicator.height).toBe("2px");
      expect(indicator.background).toBe("var(--tcrm-color-primary)");
      expect(indicator.position).toBe("absolute");
    });
  });
});
