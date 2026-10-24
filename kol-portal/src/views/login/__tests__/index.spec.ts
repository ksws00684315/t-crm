import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { routes } from "@/router/routes";
import { nextTick } from "vue";
import { describe, beforeEach, expect, it } from "vitest";
import { flushPromises } from "@vue/test-utils";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import LoginPage from "../index.vue";

async function mountPage(path = "/login") {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push(path);
  useLocaleStore().setLocale("en-US");
  return mount(LoginPage, { global: { plugins: [ElementPlus, i18n, pinia, router] } });
}

describe("login page", () => {
  beforeEach(() => localStorage.clear());

  it("renders English by default", async () => {
    const wrapper = await mountPage();
    expect(wrapper.find("h1").text()).toBe("SparkTrack Creator");
    expect(wrapper.text()).toContain("Email");
    expect(wrapper.text()).toContain("Password");
    expect(wrapper.text()).toContain("Sign in");
    expect(wrapper.text()).toContain("Forgot password?");
    expect(wrapper.find("input[type=email]").attributes("disabled")).toBeUndefined();
    expect(wrapper.find('a[href="/register"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain("Create your account");
  });

  it("shows Chinese text after switching the language", async () => {
    const wrapper = await mountPage();
    useLocaleStore().setLocale("zh-CN");
    await nextTick();
    expect(wrapper.find("h1").text()).toBe("SparkTrack 达人端");
    expect(wrapper.text()).toContain("邮箱");
    expect(wrapper.text()).toContain("密码");
    expect(wrapper.text()).toContain("登录");
    expect(wrapper.text()).toContain("忘记密码？");
    expect(wrapper.find("button[type=submit]").attributes("disabled")).toBeUndefined();
    expect(wrapper.text()).toContain("注册账号");
  });

  it("prefills the email from the query string", async () => {
    const wrapper = await mountPage("/login?email=ada@example.com");
    await flushPromises();
    expect((wrapper.get("#login-email").element as HTMLInputElement).value).toBe("ada@example.com");
  });

  it("offers the language switcher", async () => {
    const wrapper = await mountPage();
    expect(wrapper.findComponent(LocaleSwitch).exists()).toBe(true);
  });
});
