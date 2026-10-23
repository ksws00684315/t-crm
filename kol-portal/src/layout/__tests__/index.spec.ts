import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { nextTick } from "vue";
import { beforeEach, describe, expect, it } from "vitest";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import AppLayout from "../index.vue";
import { NAV_ITEMS } from "../nav";

function mountLayout() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({ history: createMemoryHistory(), routes });
  return mount(AppLayout, { global: { plugins: [ElementPlus, i18n, pinia, router] } });
}

describe("layout header", () => {
  beforeEach(() => localStorage.clear());

  it("renders English brand and sign out by default", () => {
    const wrapper = mountLayout();
    expect(wrapper.find(".shell__brand").text()).toBe("SparkTrack Creator Portal");
    expect(wrapper.find("button").text()).toContain("Sign out");
    expect(wrapper.findComponent(LocaleSwitch).exists()).toBe(true);
    expect(NAV_ITEMS.map(item => item.title)).toContain("nav.orders");
    expect(wrapper.find('a[href="/orders"]').text()).toBe("My orders");
  });

  it("shows Chinese brand and sign out after switching the language", async () => {
    const wrapper = mountLayout();
    useLocaleStore().setLocale("zh-CN");
    await nextTick();
    expect(wrapper.find(".shell__brand").text()).toBe("SparkTrack 达人端");
    expect(wrapper.find("button").text()).toContain("退出登录");
  });
});

describe("layout navigation", () => {
  beforeEach(() => localStorage.clear());

  /**
   * Mounting the layout directly gives its inner RouterView depth 0, which would render the
   * layout again inside itself; the stub keeps the assertions on the nav itself.
   */
  async function mountAt(path: string) {
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push(path);
    return mount(AppLayout, {
      global: { plugins: [ElementPlus, i18n, pinia, router], stubs: { RouterView: true } }
    });
  }

  it("renders the shared nav items with the home entry marked current", async () => {
    const wrapper = await mountAt("/");
    const links = wrapper.findAll(".shell__nav-link");
    expect(links.map(link => link.text())).toEqual([
      "Commission overview",
      "Home",
      "Payout accounts",
      "My orders",
      "My items",
      "Creator prepays"
    ]);
    expect(wrapper.get('[data-test="nav-Home"]').attributes("href")).toBe("/");
    expect(wrapper.get('[data-test="nav-PayoutAccounts"]').attributes("href")).toBe(
      "/payout-accounts"
    );
    expect(wrapper.get('[data-test="nav-Orders"]').attributes("href")).toBe("/orders");
    expect(wrapper.get('[data-test="nav-Home"]').attributes("aria-current")).toBe("page");
    expect(
      wrapper.get('[data-test="nav-PayoutAccounts"]').attributes("aria-current")
    ).toBeUndefined();
    expect(wrapper.get('[data-test="nav-Orders"]').attributes("aria-current")).toBeUndefined();
  });

  it("marks the payout entry current on its page and translates it", async () => {
    const wrapper = await mountAt("/payout-accounts");
    expect(wrapper.get('[data-test="nav-PayoutAccounts"]').attributes("aria-current")).toBe("page");
    expect(wrapper.get('[data-test="nav-Home"]').attributes("aria-current")).toBeUndefined();

    useLocaleStore().setLocale("zh-CN");
    await nextTick();
    expect(wrapper.findAll(".shell__nav-link").map(link => link.text())).toEqual([
      "佣金概览",
      "首页",
      "收款账户",
      "我的订单",
      "我的商品",
      "达人预付"
    ]);
  });
  it("marks the prepay entry current and translates it", async () => {
    const wrapper = await mountAt("/income/prepays");
    expect(wrapper.get('[data-test="nav-PrepayResults"]').attributes("aria-current")).toBe("page");
    useLocaleStore().setLocale("zh-CN");
    await nextTick();
    expect(wrapper.get('[data-test="nav-PrepayResults"]').text()).toBe("达人预付");
  });
});
