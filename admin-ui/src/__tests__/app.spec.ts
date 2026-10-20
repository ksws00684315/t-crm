import { mount } from "@vue/test-utils";
import ElementPlus, { ElConfigProvider } from "element-plus";
import en from "element-plus/es/locale/lang/en";
import zhCN from "element-plus/es/locale/lang/zh-cn";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { nextTick } from "vue";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import App from "../App.vue";

async function mountApp() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/login");
  await router.isReady();
  const wrapper = mount(App, { global: { plugins: [ElementPlus, i18n, pinia, router] } });
  await nextTick();
  return wrapper;
}

describe("app root", () => {
  beforeEach(() => {
    localStorage.clear();
    document.title = "";
  });

  it("passes the Chinese Element Plus locale by default", async () => {
    const wrapper = await mountApp();
    expect(wrapper.findComponent(ElConfigProvider).props("locale")).toBe(zhCN);
  });

  it("updates the Element Plus locale immediately after switching", async () => {
    const wrapper = await mountApp();
    const store = useLocaleStore();
    store.setLocale("en-US");
    await nextTick();
    expect(wrapper.findComponent(ElConfigProvider).props("locale")).toBe(en);
    store.setLocale("zh-CN");
    await nextTick();
    expect(wrapper.findComponent(ElConfigProvider).props("locale")).toBe(zhCN);
  });

  it("re-applies the page title when the language changes without navigation", async () => {
    await mountApp();
    useLocaleStore().setLocale("en-US");
    await nextTick();
    expect(document.title).toBe("Sign in | SparkTrack Admin");
    useLocaleStore().setLocale("zh-CN");
    await nextTick();
    expect(document.title).toBe("登录 | SparkTrack 管理端");
  });
});
