import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { defineComponent, nextTick, ref } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { createCaptcha, login } from "@/api/auth";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { useReauthStore } from "@/stores/reauth";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import { saveSession } from "@/utils/session";
import ReauthDialog from "../ReauthDialog.vue";

vi.mock("@/api/auth", () => ({
  login: vi.fn(),
  createCaptcha: vi.fn(),
  logout: vi.fn(),
  fetchCurrentUser: vi.fn()
}));

const me: UserVO = {
  id: "1",
  username: "root",
  nickname: "Root",
  email: null,
  role: "SUPER_ADMIN",
  postTemplate: null,
  permissions: [],
  dataScope: "ALL",
  tenantId: "1",
  tenantName: "默认公司",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T00:00:00Z"
};

const other: UserVO = {
  ...me,
  id: "2",
  username: "other",
  role: "STAFF",
  postTemplate: "BD",
  permissions: ["kol:view"]
};

const Host = defineComponent({
  components: { ReauthDialog },
  setup() {
    const draft = ref("unsaved note");
    return { draft };
  },
  template: `<input data-test="draft" v-model="draft" /><ReauthDialog />`
});

describe("reauth dialog", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(login).mockReset();
    let seq = 0;
    vi.mocked(createCaptcha).mockReset();
    vi.mocked(createCaptcha).mockImplementation(async () => ({
      enabled: true,
      captchaId: `c${++seq}`,
      image: "data:image/png;base64,AA==",
      expiresIn: 180
    }));
    saveSession({ token: "tok", expiresAt: Date.now() + 60_000, user: me });
    setActivePinia(createPinia());
  });

  async function mountHost() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/login", component: { template: "<div />" }, meta: { public: true } },
        { path: "/", redirect: "/home" },
        {
          path: "/home",
          component: { template: "<div />" },
          meta: {}
        },
        {
          path: "/system/users",
          component: { template: "<div />" },
          meta: { superAdmin: true }
        }
      ]
    });
    await router.push("/system/users");
    await router.isReady();
    useReauthStore().open();
    const wrapper = mount(Host, {
      global: { plugins: [ElementPlus, i18n, router] },
      attachTo: document.body
    });
    useTabsStore().open({
      path: "/system/users",
      titleKey: "route.users",
      affix: false,
      componentName: "SystemUserPage"
    });
    await nextTick();
    return { wrapper, router };
  }

  function bodyButton(testId: string): DOMWrapper<Element> {
    const element = document.body.querySelector(`[data-test=${testId}]`);
    if (!element) throw new Error(`missing ${testId}`);
    return new DOMWrapper(element);
  }

  async function typePassword(value: string): Promise<void> {
    const field = document.body.querySelector("[data-test=reauth-password]");
    if (!(field instanceof HTMLInputElement)) throw new Error("password input missing");
    await new DOMWrapper(field).setValue(value);
    await typeCaptcha("ab7k");
  }

  async function typeCaptcha(value: string): Promise<void> {
    await flushPromises();
    const field = document.body.querySelector("input[data-test=captcha-input]");
    if (!(field instanceof HTMLInputElement)) throw new Error("captcha input missing");
    await new DOMWrapper(field).setValue(value);
  }

  it("keeps the page and the draft after the same account signs in again", async () => {
    vi.mocked(login).mockResolvedValue({
      tokenName: "satoken",
      token: "next",
      expiresIn: 60,
      user: me
    });
    const { wrapper, router } = await mountHost();
    await wrapper.get("[data-test=draft]").setValue("still here");
    await typePassword("StrongPass!42");
    await bodyButton("reauth-submit").trigger("click");
    await flushPromises();

    expect(login).toHaveBeenCalledWith({
      username: "root",
      password: "StrongPass!42",
      captchaId: "c1",
      captchaCode: "ab7k"
    });
    expect(useReauthStore().visible).toBe(false);
    expect(router.currentRoute.value.path).toBe("/system/users");
    expect(wrapper.get("[data-test=draft]").element).toHaveProperty("value", "still here");
    expect(useTabsStore().tabs).toHaveLength(1);
    wrapper.unmount();
  });

  it("drops the previous tabs and goes home when the password signs in a different account", async () => {
    vi.mocked(login).mockResolvedValue({
      tokenName: "satoken",
      token: "next",
      expiresIn: 60,
      user: other
    });
    const { wrapper, router } = await mountHost();
    await typePassword("StrongPass!42");
    await bodyButton("reauth-submit").trigger("click");
    await flushPromises();

    expect(useUserStore().user?.id).toBe("2");
    expect(useTabsStore().tabs).toEqual([]);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/home"));
    wrapper.unmount();
  });

  it("clears the session and opens the login page when switching accounts", async () => {
    const { wrapper, router } = await mountHost();
    await bodyButton("reauth-switch").trigger("click");
    await flushPromises();

    expect(useUserStore().isLoggedIn).toBe(false);
    expect(useTabsStore().tabs).toEqual([]);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));
    expect(login).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("leaves for the login page when reauth login returns AUTH_ACCOUNT_LOCKED as HTTP 429", async () => {
    vi.mocked(login).mockRejectedValue(
      new ApiError({ status: 429, errorCode: "AUTH_ACCOUNT_LOCKED", traceId: "t" })
    );
    const warning = vi.spyOn(ElMessage, "warning");
    const { wrapper, router } = await mountHost();
    await typePassword("StrongPass!42");
    await bodyButton("reauth-submit").trigger("click");
    await flushPromises();

    expect(warning).toHaveBeenCalledTimes(1);
    expect(warning).toHaveBeenCalledWith("登录失败次数过多，请稍后再试");
    warning.mockRestore();
    expect(useUserStore().isLoggedIn).toBe(false);
    expect(useTabsStore().tabs).toEqual([]);
    expect(useReauthStore().visible).toBe(false);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));
    wrapper.unmount();
  });

  it("requires the captcha before calling login", async () => {
    const { wrapper } = await mountHost();
    const field = document.body.querySelector("[data-test=reauth-password]") as HTMLInputElement;
    await new DOMWrapper(field).setValue("StrongPass!42");
    await bodyButton("reauth-submit").trigger("click");
    await flushPromises();
    expect(login).not.toHaveBeenCalled();
    await vi.waitFor(() => expect(document.body.textContent).toContain("请输入验证码"));
    wrapper.unmount();
  });

  it("shows the captcha message and loads a new captcha after AUTH_CAPTCHA_INVALID", async () => {
    vi.mocked(login).mockRejectedValue(
      new ApiError({ status: 400, errorCode: "AUTH_CAPTCHA_INVALID", traceId: "t" })
    );
    const { wrapper } = await mountHost();
    await typePassword("StrongPass!42");
    await bodyButton("reauth-submit").trigger("click");
    await flushPromises();

    expect(createCaptcha).toHaveBeenCalledTimes(2);
    expect(useReauthStore().visible).toBe(true);
    expect(useUserStore().isLoggedIn).toBe(true);
    await vi.waitFor(() => expect(document.body.textContent).toContain("验证码错误或已过期"));
    const password = document.body.querySelector("[data-test=reauth-password]") as HTMLInputElement;
    expect(password.value).toBe("StrongPass!42");
    const captcha = document.body.querySelector(
      "input[data-test=captcha-input]"
    ) as HTMLInputElement;
    expect(captcha.value).toBe("");
    // The second attempt carries the new captcha id.
    vi.mocked(login).mockResolvedValue({
      tokenName: "satoken",
      token: "n",
      expiresIn: 60,
      user: me
    });
    await typePassword("StrongPass!42");
    await bodyButton("reauth-submit").trigger("click");
    await flushPromises();
    expect(vi.mocked(login).mock.calls[1][0]).toMatchObject({ captchaId: "c2" });
    wrapper.unmount();
  });

  it("closes the store flag when the dialog unmounts", async () => {
    const { wrapper } = await mountHost();
    expect(useReauthStore().visible).toBe(true);
    wrapper.unmount();
    expect(useReauthStore().visible).toBe(false);
  });
});
