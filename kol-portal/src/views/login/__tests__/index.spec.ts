import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import { routes } from "@/router/routes";
import { nextTick } from "vue";
import { describe, beforeEach, expect, it } from "vitest";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import LoginPage from "../index.vue";

function mountPage() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({ history: createMemoryHistory(), routes });
  useLocaleStore().setLocale("en-US");
  return mount(LoginPage, { global: { plugins: [ElementPlus, i18n, pinia, router] } });
}

describe("login page", () => {
  beforeEach(() => localStorage.clear());

  it("renders English by default", () => {
    const wrapper = mountPage();
    expect(wrapper.find("h1").text()).toBe("T-CRM Creator Portal");
    expect(wrapper.text()).toContain("Email");
    expect(wrapper.text()).toContain("Password");
    expect(wrapper.text()).toContain("Sign in");
    expect(wrapper.text()).toContain("Forgot password?");
    expect(wrapper.find("input[type=email]").attributes("disabled")).toBeUndefined();
  });

  it("shows Chinese text after switching the language", async () => {
    const wrapper = mountPage();
    useLocaleStore().setLocale("zh-CN");
    await nextTick();
    expect(wrapper.find("h1").text()).toBe("T-CRM Creator Portal");
    expect(wrapper.text()).toContain("邮箱");
    expect(wrapper.text()).toContain("密码");
    expect(wrapper.text()).toContain("登录");
    expect(wrapper.text()).toContain("忘记密码？");
    expect(wrapper.find("button[type=submit]").attributes("disabled")).toBeUndefined();
  });

  it("offers the language switcher", () => {
    const wrapper = mountPage();
    expect(wrapper.findComponent(LocaleSwitch).exists()).toBe(true);
  });
});
