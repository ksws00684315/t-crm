import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { nextTick, type Component } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/auth";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import { useSessionStore } from "@/stores/session";
import { loadSession } from "@/utils/session";
import { ApiError } from "@/utils/http/error";
import Login from "@/views/login/index.vue";
import PasswordLink from "@/views/password-link/index.vue";
import Forgot from "@/views/forgot/index.vue";
import Account from "@/views/account/index.vue";
import Layout from "@/layout/index.vue";

vi.mock("@/api/auth", () => ({
  login: vi.fn(),
  selectCompany: vi.fn(),
  activate: vi.fn(),
  forgotPassword: vi.fn(),
  resetPassword: vi.fn(),
  getMe: vi.fn(),
  changePassword: vi.fn(),
  logout: vi.fn()
}));
const kol: api.KolProfile = {
  kolCode: "TEST001",
  name: "Test Creator",
  email: "creator@example.test",
  country: "US",
  tenantName: "Test Company",
  sheetUrl: "https://example.test/sheet"
};
const session: api.AuthSession = {
  tokenName: "X-Kol-Token",
  token: "test-token",
  expiresIn: 3600,
  kol
};
const selection: api.CompanySelection = {
  selectionToken: "selection-test",
  companies: [
    { tenantId: "11", tenantName: "Test Company" },
    { tenantId: "12", tenantName: "Other Company" }
  ]
};
const wrappers: VueWrapper[] = [];
function failure(errorCode: string, status = 400) {
  return new ApiError({ errorCode, status, traceId: "test-trace" });
}
async function page(component: Component, path: string, props = {}) {
  const pinia = createPinia();
  setActivePinia(pinia);
  useLocaleStore().setLocale("en-US");
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push(path);
  const wrapper = mount(component, {
    attachTo: document.body,
    props,
    global: { plugins: [ElementPlus, i18n, pinia, router] }
  });
  wrappers.push(wrapper);
  return { wrapper, router, store: useSessionStore() };
}
async function fill(wrapper: VueWrapper, values: Record<string, string>) {
  for (const [id, value] of Object.entries(values)) await wrapper.get(`#${id}`).setValue(value);
}
async function send(wrapper: VueWrapper) {
  await wrapper.get("form").trigger("submit");
  await flushPromises();
}
beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  vi.mocked(api.getMe).mockResolvedValue(kol);
});
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount());
  document.body.innerHTML = "";
});

describe("sign in", () => {
  it("saves identity and expiration and follows redirect for a single company", async () => {
    vi.mocked(api.login).mockResolvedValue(session);
    const { wrapper, router, store } = await page(Login, "/login?redirect=/account");
    await fill(wrapper, {
      "login-email": "creator@example.test",
      "login-password": "Test-password1"
    });
    await send(wrapper);
    expect(api.login).toHaveBeenCalledWith({ email: kol.email, password: "Test-password1" });
    expect(router.currentRoute.value.path).toBe("/account");
    expect(store.kol).toEqual(kol);
    expect(loadSession()?.token).toBe("test-token");
    expect(loadSession()?.expiresAt).toBeGreaterThan(Date.now() + 3590000);
  });
  it("shows companies and exchanges only the selected company credential", async () => {
    vi.mocked(api.login).mockResolvedValue(selection);
    vi.mocked(api.selectCompany).mockResolvedValue(session);
    const { wrapper, router } = await page(Login, "/login");
    await fill(wrapper, { "login-email": kol.email, "login-password": "Test-password1" });
    await send(wrapper);
    expect(document.body.textContent).toContain("Choose your company");
    expect(document.body.textContent).toContain("Other Company");
    useLocaleStore().setLocale("zh-CN");
    await nextTick();
    expect(document.body.textContent).toContain("选择公司");
    await wrapper.getComponent({ name: "ElRadioGroup" }).vm.$emit("update:modelValue", "12");
    await nextTick();
    const buttons = [...document.body.querySelectorAll("button")];
    const button = buttons.find(item => item.textContent?.includes("继续"));
    button?.click();
    await flushPromises();
    expect(api.selectCompany).toHaveBeenCalledWith({
      selectionToken: "selection-test",
      tenantId: "12"
    });
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/"));
  });
  it("closes company selection when the credential expires", async () => {
    vi.mocked(api.login).mockResolvedValue(selection);
    vi.mocked(api.selectCompany).mockRejectedValue(failure("KOL_LOGIN_SELECTION_INVALID"));
    const { wrapper } = await page(Login, "/login");
    await fill(wrapper, { "login-email": kol.email, "login-password": "Test-password1" });
    await send(wrapper);
    wrapper.getComponent({ name: "ElRadioGroup" }).vm.$emit("update:modelValue", "11");
    await nextTick();
    [...document.body.querySelectorAll("button")]
      .find(item => item.textContent?.includes("Continue"))
      ?.click();
    await flushPromises();
    expect(wrapper.text()).toContain("Company selection has expired");
    expect(wrapper.getComponent({ name: "ElDialog" }).props("modelValue")).toBe(false);
  });
  it.each([
    ["AUTH_INVALID_CREDENTIALS", 401, "Incorrect email or password"],
    ["AUTH_ACCOUNT_LOCKED", 429, "Too many failed"],
    ["AUTH_ACCOUNT_DISABLED", 401, "disabled or unavailable"]
  ] as const)("displays %s inline", async (code, status, text) => {
    vi.mocked(api.login).mockRejectedValue(failure(code, status));
    const { wrapper, router } = await page(Login, "/login");
    await fill(wrapper, { "login-email": kol.email, "login-password": "Test-password1" });
    await send(wrapper);
    expect(wrapper.text()).toContain(text);
    expect(router.currentRoute.value.path).toBe("/login");
    useLocaleStore().setLocale("zh-CN");
    await nextTick();
    expect(wrapper.text()).not.toContain(text);
  });
  it("prevents invalid input and duplicate submissions", async () => {
    const { wrapper } = await page(Login, "/login");
    await send(wrapper);
    expect(api.login).not.toHaveBeenCalled();
    let done: (value: api.AuthSession) => void = () => {};
    vi.mocked(api.login).mockReturnValue(
      new Promise(resolve => {
        done = resolve;
      })
    );
    await fill(wrapper, { "login-email": kol.email, "login-password": "Test-password1" });
    await send(wrapper);
    await send(wrapper);
    expect(api.login).toHaveBeenCalledTimes(1);
    expect(wrapper.get("button[type=submit]").attributes("disabled")).toBeDefined();
    done(session);
    await flushPromises();
  });
  it("rejects an external redirect after login", async () => {
    vi.mocked(api.login).mockResolvedValue(session);
    const { wrapper, router } = await page(Login, "/login?redirect=https://evil.test");
    await fill(wrapper, { "login-email": kol.email, "login-password": "Test-password1" });
    await send(wrapper);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/"));
  });
});

describe("activation and reset links", () => {
  it("blocks mismatched passwords before requesting activation", async () => {
    const { wrapper } = await page(PasswordLink, "/activate?token=test-link", { mode: "activate" });
    await fill(wrapper, { "link-password": "Test-password1", "link-confirm": "Test-password2" });
    await send(wrapper);
    expect(api.activate).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("passwords do not match");
  });
  it.each(["activate", "reset"] as const)("shows invalid %s links without a form", async mode => {
    const { wrapper } = await page(PasswordLink, `/${mode}`, { mode });
    expect(wrapper.find("form").exists()).toBe(false);
    expect(wrapper.text()).toContain("contact your representative");
  });
  it("shows the translated token failure", async () => {
    vi.mocked(api.activate).mockRejectedValue(failure("KOL_AUTH_TOKEN_INVALID"));
    const { wrapper } = await page(PasswordLink, "/activate?token=test-link", { mode: "activate" });
    await fill(wrapper, { "link-password": "Test-password1", "link-confirm": "Test-password1" });
    await send(wrapper);
    expect(wrapper.find("form").exists()).toBe(false);
    useLocaleStore().setLocale("zh-CN");
    await nextTick();
    expect(wrapper.text()).toContain("链接已失效，请联系您的商务重新发送");
  });
  it("activates and signs in directly", async () => {
    vi.mocked(api.activate).mockResolvedValue(session);
    const { wrapper, router, store } = await page(PasswordLink, "/activate?token=test-link", {
      mode: "activate"
    });
    await fill(wrapper, { "link-password": "Test-password1", "link-confirm": "Test-password1" });
    await send(wrapper);
    expect(api.activate).toHaveBeenCalledWith({ token: "test-link", password: "Test-password1" });
    expect(store.token).toBe(session.token);
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/"));
  });
  it("resets and shows a sign-in success notice", async () => {
    vi.mocked(api.resetPassword).mockResolvedValue(null);
    const { wrapper, router, store } = await page(PasswordLink, "/reset?token=test-reset", {
      mode: "reset"
    });
    store.acceptSession(session);
    await fill(wrapper, { "link-password": "Test-password1", "link-confirm": "Test-password1" });
    await send(wrapper);
    expect(api.resetPassword).toHaveBeenCalledWith({
      token: "test-reset",
      password: "Test-password1"
    });
    expect(store.isLoggedIn).toBe(false);
    expect(router.currentRoute.value.fullPath).toBe("/login?reset=success");
    const login = mount(Login, { global: { plugins: [ElementPlus, i18n, createPinia(), router] } });
    wrappers.push(login);
    expect(login.text()).toContain("password has been reset");
  });
});

describe("forgot password", () => {
  it.each(["registered@example.test", "unknown@example.test"])(
    "uses the same notice for %s",
    async email => {
      vi.mocked(api.forgotPassword).mockResolvedValue(null);
      const { wrapper } = await page(Forgot, "/forgot");
      await fill(wrapper, { "forgot-email": email });
      await send(wrapper);
      expect(api.forgotPassword).toHaveBeenCalledWith({ email });
      expect(wrapper.text()).toContain(
        "If this email is registered, we have sent a password reset email."
      );
      expect(wrapper.find("form").exists()).toBe(false);
    }
  );
});

describe("account and logout", () => {
  it("loads read-only identity and a safe sheet link", async () => {
    const { wrapper } = await page(Account, "/account");
    await flushPromises();
    expect(api.getMe).toHaveBeenCalledOnce();
    expect(wrapper.text()).toContain(kol.kolCode);
    expect(wrapper.text()).toContain(kol.tenantName);
    expect(wrapper.get('a[target="_blank"]').attributes("href")).toBe(kol.sheetUrl);
  });
  it("shows an old-password error and replaces the session after a successful change", async () => {
    vi.mocked(api.changePassword)
      .mockRejectedValueOnce(failure("AUTH_OLD_PASSWORD_INCORRECT", 422))
      .mockResolvedValueOnce({ ...session, token: "rotated-test-token" });
    const { wrapper, store } = await page(Account, "/account");
    store.acceptSession(session);
    await flushPromises();
    await fill(wrapper, {
      "old-password": "Old-password1",
      "new-password": "New-password2",
      "confirm-password": "New-password2"
    });
    await send(wrapper);
    expect(wrapper.text()).toContain("current password is incorrect");
    expect(store.token).toBe(session.token);
    await send(wrapper);
    expect(api.changePassword).toHaveBeenLastCalledWith({
      oldPassword: "Old-password1",
      newPassword: "New-password2"
    });
    expect(store.token).toBe("rotated-test-token");
    expect(loadSession()?.token).toBe("rotated-test-token");
    expect(store.isLoggedIn).toBe(true);
    expect(wrapper.text()).toContain("password has been changed");
  });
  it("does not send mismatched new passwords", async () => {
    const { wrapper } = await page(Account, "/account");
    await fill(wrapper, {
      "old-password": "Old-password1",
      "new-password": "New-password2",
      "confirm-password": "New-password3"
    });
    await send(wrapper);
    expect(api.changePassword).not.toHaveBeenCalled();
  });
  it.each([false, true])("clears local state after logout (server fails: %s)", async fails => {
    if (fails) vi.mocked(api.logout).mockRejectedValue(failure("COMMON_INTERNAL_ERROR", 500));
    else vi.mocked(api.logout).mockResolvedValue(null);
    const { wrapper, router, store } = await page(Layout, "/");
    store.acceptSession(session);
    await nextTick();
    expect(wrapper.text()).toContain("Test Creator · Test Company");
    await wrapper.get("button").trigger("click");
    await flushPromises();
    expect(api.logout).toHaveBeenCalledOnce();
    expect(store.isLoggedIn).toBe(false);
    expect(loadSession()).toBeNull();
    expect(router.currentRoute.value.path).toBe("/login");
  });
});
