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
  let seq = 0;
  vi.mocked(authApi.createCaptcha).mockImplementation(async () => ({
    enabled: true,
    captchaId: `c${++seq}`,
    image: "data:image/png;base64,AA==",
    expiresIn: 180
  }));
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
  await flushPromises();
  return { wrapper, router };
}

async function fill(
  wrapper: Awaited<ReturnType<typeof mountLogin>>["wrapper"],
  u: string,
  p: string,
  c = "ab7k"
) {
  const inputs = wrapper.findAll("input");
  await inputs[0].setValue(u);
  await inputs[1].setValue(p);
  await wrapper.get("input[data-test=captcha-input]").setValue(c);
  await wrapper.find("button.login__submit").trigger("click");
  await flushPromises();
}

describe("login page", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(authApi.login).mockReset();
    vi.mocked(authApi.createCaptcha).mockReset();
  });

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
    expect(authApi.login).toHaveBeenCalledWith({
      username: "root",
      password: "any-password",
      captchaId: "c1",
      captchaCode: "ab7k"
    });
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

  it("shows the captcha image and loads a new one when it is clicked", async () => {
    const { wrapper } = await mountLogin();
    expect(wrapper.get("[data-test=captcha-image]").attributes("src")).toContain("data:image/png");
    await wrapper.get("input[data-test=captcha-input]").setValue("zz");
    await wrapper.get("[data-test=captcha-refresh]").trigger("click");
    await flushPromises();
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(2);
    expect((wrapper.get("input[data-test=captcha-input]").element as HTMLInputElement).value).toBe(
      ""
    );
  });

  it("requires the captcha before calling the API", async () => {
    const { wrapper } = await mountLogin();
    await fill(wrapper, "root", "any-password", "");
    expect(authApi.login).not.toHaveBeenCalled();
    await vi.waitFor(() => expect(wrapper.text()).toContain("请输入验证码"));
  });

  it("refreshes the captcha after a failed login and sends the new id next time", async () => {
    vi.mocked(authApi.login).mockRejectedValueOnce(
      new ApiError({ status: 400, errorCode: "AUTH_CAPTCHA_INVALID", traceId: "t" })
    );
    vi.mocked(authApi.login).mockRejectedValueOnce(
      new ApiError({ status: 401, errorCode: "AUTH_INVALID_CREDENTIALS", traceId: "t" })
    );
    const { wrapper } = await mountLogin();
    await fill(wrapper, "root", "any-password");
    await flushPromises();
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(2);
    // Only the captcha was wrong, so the password stays.
    expect((wrapper.findAll("input")[1].element as HTMLInputElement).value).toBe("any-password");
    await fill(wrapper, "root", "any-password", "q2w3");
    await flushPromises();
    expect(vi.mocked(authApi.login).mock.calls[1][0]).toMatchObject({
      captchaId: "c2",
      captchaCode: "q2w3"
    });
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(3);
  });

  it("submits on Enter in the captcha field", async () => {
    vi.mocked(authApi.login).mockRejectedValue(
      new ApiError({ status: 401, errorCode: "AUTH_INVALID_CREDENTIALS", traceId: "t" })
    );
    const { wrapper } = await mountLogin();
    const inputs = wrapper.findAll("input");
    await inputs[0].setValue("root");
    await inputs[1].setValue("any-password");
    const captcha = wrapper.get("input[data-test=captcha-input]");
    await captcha.setValue("ab7k");
    await captcha.trigger("keyup", { key: "Enter" });
    await flushPromises();
    expect(authApi.login).toHaveBeenCalledTimes(1);
  });

  it("hides the captcha and omits it when the backend has captchas disabled", async () => {
    const { wrapper } = await mountLogin();
    vi.mocked(authApi.createCaptcha).mockResolvedValue({
      enabled: false,
      captchaId: null,
      image: null,
      expiresIn: 0
    });
    await wrapper.get("[data-test=captcha-refresh]").trigger("click");
    await flushPromises();
    expect(wrapper.find("[data-test=captcha]").exists()).toBe(false);
    vi.mocked(authApi.login).mockRejectedValue(
      new ApiError({ status: 401, errorCode: "AUTH_INVALID_CREDENTIALS", traceId: "t" })
    );
    const inputs = wrapper.findAll("input");
    await inputs[0].setValue("root");
    await inputs[1].setValue("any-password");
    await wrapper.find("button.login__submit").trigger("click");
    await flushPromises();
    expect(authApi.login).toHaveBeenCalledWith({ username: "root", password: "any-password" });
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
    expect(text).toContain("Captcha");
    expect(document.documentElement.lang).toBe("en-US");
  });
});
