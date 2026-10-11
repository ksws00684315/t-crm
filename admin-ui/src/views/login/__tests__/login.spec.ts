import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useLocaleStore } from "@/stores/locale";
import * as authApi from "@/api/auth";
import { i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import LoginPage from "../index.vue";

vi.mock("@/api/auth");

async function mountLogin(redirect?: string) {
  setActivePinia(createPinia());
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/login", component: LoginPage },
      { path: "/", component: { template: "<div />" } },
      { path: "/system/users", component: { template: "<div />" } }
    ]
  });
  await router.push({ path: "/login", query: redirect ? { redirect } : {} });
  await router.isReady();
  const wrapper = mount(LoginPage, { global: { plugins: [ElementPlus, i18n, router] } });
  return { wrapper, router };
}

async function fill(
  wrapper: Awaited<ReturnType<typeof mountLogin>>["wrapper"],
  u: string,
  p: string
) {
  const inputs = wrapper.findAll("input");
  await inputs[0].setValue(u);
  await inputs[1].setValue(p);
  await wrapper.find("button").trigger("click");
  await flushPromises();
}

describe("login page", () => {
  beforeEach(() => localStorage.clear());

  it("does not call the API when the form is invalid", async () => {
    const { wrapper } = await mountLogin();
    await fill(wrapper, "a", "");
    expect(authApi.login).not.toHaveBeenCalled();
    await vi.waitFor(() => expect(wrapper.text()).toContain("用户名需为 3-64 位"));
  });

  it("signs in and follows a same-site redirect", async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      tokenName: "X-Admin-Token",
      token: "tok",
      expiresIn: 28800,
      user: {
        id: "1",
        username: "root",
        nickname: "Root",
        email: null,
        role: "SUPER_ADMIN",
        dataScope: "ALL",
        tenantId: "1",
        tenantName: "默认公司",
        status: 1,
        lastLoginTime: null,
        createTime: "2026-10-05T00:00:00Z"
      }
    });
    const { wrapper, router } = await mountLogin("/system/users");
    await fill(wrapper, "root", "any-password");
    expect(authApi.login).toHaveBeenCalledWith({ username: "root", password: "any-password" });
    expect(router.currentRoute.value.path).toBe("/system/users");
  });

  it("ignores an absolute redirect target", async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      tokenName: "X-Admin-Token",
      token: "tok",
      expiresIn: 28800,
      user: {
        id: "1",
        username: "root",
        nickname: "Root",
        email: null,
        role: "OPERATOR",
        dataScope: "ALL",
        tenantId: "1",
        tenantName: "默认公司",
        status: 1,
        lastLoginTime: null,
        createTime: "2026-10-05T00:00:00Z"
      }
    });
    const { wrapper, router } = await mountLogin("//evil.example/x");
    await fill(wrapper, "root", "any-password");
    expect(router.currentRoute.value.path).toBe("/");
  });

  it("stays on the page and clears the password after a failed login", async () => {
    vi.mocked(authApi.login).mockRejectedValue(
      new ApiError({ status: 401, errorCode: "AUTH_INVALID_CREDENTIALS", traceId: "t" })
    );
    const { wrapper, router } = await mountLogin();
    await fill(wrapper, "root", "wrong-password");
    expect(router.currentRoute.value.path).toBe("/login");
    expect((wrapper.findAll("input")[1].element as HTMLInputElement).value).toBe("");
  });

  it("renders the sign-in form in English", async () => {
    const { wrapper } = await mountLogin();
    useLocaleStore().setLocale("en-US");
    await nextTick();
    const text = wrapper.text();
    expect(text).toContain("T-CRM Admin");
    expect(text).toContain("Username");
    expect(text).toContain("Password");
    expect(text).toContain("Sign in");
    expect(document.documentElement.lang).toBe("en-US");
  });
});
