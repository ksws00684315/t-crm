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
    expect(wrapper.find(".shell__brand").text()).toBe("T-CRM Creator Portal");
    expect(wrapper.find("button").text()).toContain("Sign out");
    expect(wrapper.findComponent(LocaleSwitch).exists()).toBe(true);
  });

  it("shows Chinese brand and sign out after switching the language", async () => {
    const wrapper = mountLayout();
    useLocaleStore().setLocale("zh-CN");
    await nextTick();
    expect(wrapper.find(".shell__brand").text()).toBe("T-CRM Creator Portal");
    expect(wrapper.find("button").text()).toContain("退出登录");
  });
});
