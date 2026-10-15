import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { defineComponent, nextTick, ref } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { createCaptcha, login, logout } from "@/api/auth";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { useLockStore } from "@/stores/lock";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import { loadLocked } from "@/utils/lock";
import { loadSession, saveSession } from "@/utils/session";
import LockScreen from "../LockScreen.vue";

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
  tenantName: "Default",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T00:00:00Z"
};

const Host = defineComponent({
  components: { LockScreen },
  setup() {
    return { draft: ref("unsaved") };
  },
  template: `<input data-test="draft" v-model="draft" /><LockScreen />`
});

function body(selector: string): DOMWrapper<Element> {
  const element = document.body.querySelector(selector);
  if (!element) throw new Error(`missing ${selector}`);
  return new DOMWrapper(element);
}

describe("lock screen", () => {
  beforeEach(() => {
    localStorage.clear();
    document.body.innerHTML = "";
    vi.mocked(login).mockReset();
    vi.mocked(logout).mockReset();
    vi.mocked(logout).mockResolvedValue(null);
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

  async function mountLocked() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/login", component: { template: "<div />" } },
        { path: "/kol", component: { template: "<div />" } }
      ]
    });
    await router.push("/kol");
    await router.isReady();
    useLockStore().lock();
    const wrapper = mount(Host, {
      global: { plugins: [ElementPlus, i18n, router] },
      attachTo: document.body
    });
    await flushPromises();
    return { wrapper, router };
  }

  async function fill(password: string): Promise<void> {
    await body("[data-test=lock-password] input, input[data-test=lock-password]").setValue(
      password
    );
    await body("input[data-test=captcha-input]").setValue("ab7k");
  }

  it("persists the lock so a reload stays locked", async () => {
    const { wrapper } = await mountLocked();
    expect(loadLocked()).toBe(true);
    expect(document.body.querySelector("[data-test=lock-screen]")).not.toBeNull();
    wrapper.unmount();
  });

  it("unlocks with password and captcha, keeping the page state", async () => {
    vi.mocked(login).mockResolvedValue({ tokenName: "t", token: "next", expiresIn: 60, user: me });
    const { wrapper, router } = await mountLocked();
    await wrapper.get("[data-test=draft]").setValue("still here");
    await fill("StrongPass!42");
    await body("[data-test=lock-submit]").trigger("click");
    await flushPromises();
    expect(login).toHaveBeenCalledWith({
      username: "root",
      password: "StrongPass!42",
      captchaId: "c1",
      captchaCode: "ab7k"
    });
    // Old session is logged out while the store still held the previous token, then the new one applied.
    expect(logout).toHaveBeenCalledTimes(1);
    expect(useUserStore().token).toBe("next");
    expect(useLockStore().locked).toBe(false);
    expect(loadLocked()).toBe(false);
    await nextTick();
    expect(document.body.querySelector("[data-test=lock-screen]")).toBeNull();
    expect(router.currentRoute.value.path).toBe("/kol");
    expect(wrapper.get("[data-test=draft]").element).toHaveProperty("value", "still here");
    wrapper.unmount();
  });

  it("stays locked with an error after a wrong password and asks for a new captcha", async () => {
    vi.mocked(login).mockRejectedValue(
      new ApiError({ status: 401, errorCode: "AUTH_BAD_CREDENTIALS", traceId: null })
    );
    const { wrapper } = await mountLocked();
    await fill("WrongPass!42");
    await body("[data-test=lock-submit]").trigger("click");
    await flushPromises();
    expect(useLockStore().locked).toBe(true);
    expect(useUserStore().isLoggedIn).toBe(true);
    expect(createCaptcha).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it("signs out to the login page when the account gets locked", async () => {
    vi.mocked(login).mockRejectedValue(
      new ApiError({ status: 429, errorCode: "AUTH_ACCOUNT_LOCKED", traceId: null })
    );
    const { wrapper, router } = await mountLocked();
    await fill("WrongPass!42");
    await body("[data-test=lock-submit]").trigger("click");
    await flushPromises();
    expect(useUserStore().isLoggedIn).toBe(false);
    expect(loadSession()).toBeNull();
    expect(useLockStore().locked).toBe(false);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));
    wrapper.unmount();
  });
});
