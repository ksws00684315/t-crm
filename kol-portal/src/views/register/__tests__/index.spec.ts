import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElSelect } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import { useSessionStore } from "@/stores/session";
import { loadSession } from "@/utils/session";
import { ApiError } from "@/utils/http/error";
import * as registerApi from "@/api/register";
import type { AuthSession } from "@/api/auth";
import RegisterPage from "../index.vue";

vi.mock("@/api/register", () => ({
  previewRegister: vi.fn(),
  sendRegisterCode: vi.fn(),
  registerKol: vi.fn()
}));

const CODE = "ABCDEFGH";
const session: AuthSession = {
  tokenName: "X-Kol-Token",
  token: "reg-token",
  expiresIn: 3600,
  kol: {
    kolCode: "KOL0001",
    name: "ada",
    email: "ada@example.com",
    country: null,
    tenantName: "Acme"
  }
};
const wrappers: VueWrapper[] = [];

function failure(errorCode: string, status = 400) {
  return new ApiError({ errorCode, status, traceId: "test-trace" });
}

async function page(path: string) {
  const pinia = createPinia();
  setActivePinia(pinia);
  useLocaleStore().setLocale("en-US");
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push(path);
  const wrapper = mount(RegisterPage, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, pinia, router] }
  });
  wrappers.push(wrapper);
  await flushPromises();
  return { wrapper, router };
}

function choosePlatform(wrapper: VueWrapper, value: string) {
  const select = wrapper
    .findAllComponents(ElSelect)
    .find(item => item.classes().includes("register-platform"));
  if (!select) throw new Error("platform select was not rendered");
  select.vm.$emit("update:model-value", value);
}

async function fill(wrapper: VueWrapper, values: Record<string, string>) {
  for (const [id, value] of Object.entries(values)) await wrapper.get(`#${id}`).setValue(value);
}

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
});

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount());
  document.body.innerHTML = "";
});

describe("register page", () => {
  it("allows manual code entry without a link and shows invalid links with a code", async () => {
    const missing = await page("/register");
    expect(missing.wrapper.find("#register-code").exists()).toBe(true);
    expect(registerApi.previewRegister).not.toHaveBeenCalled();

    vi.mocked(registerApi.previewRegister).mockRejectedValue(
      failure("KOL_REGISTER_CODE_INVALID", 404)
    );
    const rejected = await page(`/register?code=${CODE}`);
    expect(rejected.wrapper.text()).toContain("registration link is invalid");
    useLocaleStore().setLocale("zh-CN");
    await flushPromises();
    expect(rejected.wrapper.text()).toContain("注册链接无效或已失效，请联系您的商务获取新链接");
  });

  it("shows the inviting BD when the link is valid", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ bdUserName: "Alex" });
    const { wrapper } = await page(`/register?code=${CODE}`);
    expect(wrapper.find("h1").text()).toBe("Create your account");
    expect(wrapper.text()).toContain("Invited by Alex");
    useLocaleStore().setLocale("zh-CN");
    await flushPromises();
    expect(wrapper.find("h1").text()).toBe("创建账号");
    expect(wrapper.text()).toContain("邀请人：Alex");
  });

  it("shows a retry-later message when invitation preview is rate limited", async () => {
    vi.mocked(registerApi.previewRegister).mockRejectedValue(failure("RATE_LIMITED", 429));
    const { wrapper } = await page(`/register?code=${CODE}`);
    expect(wrapper.text()).toContain("Too many attempts. Please try again later.");
    expect(wrapper.find("#register-email").exists()).toBe(true);
  });

  it("keeps the send button usable and shows the API message on a repeat click", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ bdUserName: "Alex" });
    vi.mocked(registerApi.sendRegisterCode).mockResolvedValue(null);
    const { wrapper } = await page(`/register?code=${CODE}`);
    await wrapper.get("#register-email").setValue("ada@example.com");
    await wrapper.get("#register-send").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("Resend in 60s");
    expect(wrapper.get("#register-send").attributes("disabled")).toBeUndefined();
    vi.mocked(registerApi.sendRegisterCode).mockRejectedValue(
      failure("KOL_EMAIL_CODE_TOO_FREQUENT", 429)
    );
    await wrapper.get("#register-send").trigger("click");
    await flushPromises();
    expect(registerApi.sendRegisterCode).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("Too many attempts. Please try again later.");
  });

  it("validates the form before calling the API", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ bdUserName: "Alex" });
    const { wrapper } = await page(`/register?code=${CODE}`);
    await wrapper.get("#register-name").setValue("Ada");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("Please enter a valid email address.");
    expect(registerApi.registerKol).not.toHaveBeenCalled();
  });

  it("signs in and opens home after a successful registration", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ bdUserName: "Alex" });
    vi.mocked(registerApi.registerKol).mockResolvedValue(session);
    const { wrapper, router } = await page(`/register?code=${CODE}`);
    await fill(wrapper, {
      "register-name": "Ada",
      "register-email": "ada@example.com",
      "register-email-code": "123456",
      "register-password": "register-pass-1",
      "register-confirm": "register-pass-1",
      "register-whatsapp": "+15551234567",
      "register-handle": "ada"
    });
    choosePlatform(wrapper, "TikTok");
    await wrapper.get("form").trigger("submit");
    await vi.waitFor(() => {
      expect(router.currentRoute.value.path).toBe("/");
    });
    expect(registerApi.registerKol).toHaveBeenCalledWith({
      code: CODE,
      email: "ada@example.com",
      emailCode: "123456",
      password: "register-pass-1",
      phone: null,
      whatsapp: "+15551234567",
      primaryPlatform: "TikTok",
      primaryHandle: "ada",
      primaryUrl: null,
      name: "Ada",
      country: null
    });
    expect(useSessionStore().getToken()).toBe("reg-token");
    expect(loadSession()?.token).toBe("reg-token");
  });

  it("offers sign-in with the email when the address is already registered", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ bdUserName: "Alex" });
    vi.mocked(registerApi.registerKol).mockRejectedValue(failure("KOL_EMAIL_EXISTS", 409));
    const { wrapper } = await page(`/register?code=${CODE}`);
    await fill(wrapper, {
      "register-name": "Ada",
      "register-email": "ada@example.com",
      "register-email-code": "123456",
      "register-password": "register-pass-1",
      "register-confirm": "register-pass-1",
      "register-whatsapp": "+15551234567",
      "register-handle": "ada"
    });
    choosePlatform(wrapper, "TikTok");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("This email is already registered. Please sign in.");
    expect(wrapper.get("a.register-login").attributes("href")).toBe("/login?email=ada@example.com");
    useLocaleStore().setLocale("zh-CN");
    await flushPromises();
    expect(wrapper.text()).toContain("该邮箱已注册，请直接登录");
  });

  it("allows optional contact and social fields to stay blank", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ bdUserName: "Alex" });
    vi.mocked(registerApi.registerKol).mockResolvedValue(session);
    const { wrapper } = await page(`/register?code=${CODE}`);
    await fill(wrapper, {
      "register-name": "Ada",
      "register-email": "ada@example.com",
      "register-email-code": "123456",
      "register-password": "long-enough-pw",
      "register-confirm": "long-enough-pw"
    });
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(registerApi.registerKol).toHaveBeenCalledWith(
      expect.objectContaining({
        phone: null,
        whatsapp: null,
        primaryPlatform: null,
        primaryHandle: null,
        name: "Ada"
      })
    );
  });

  it("requires an invite code when registering without a link", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ bdUserName: "Alex" });
    const { wrapper } = await page("/register");
    await fill(wrapper, {
      "register-code": CODE,
      "register-name": "Ada",
      "register-email": "ada@example.com",
      "register-email-code": "123456",
      "register-password": "long-enough-pw",
      "register-confirm": "long-enough-pw"
    });
    await flushPromises();
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(registerApi.previewRegister).toHaveBeenCalledWith(CODE);
    expect(registerApi.registerKol).toHaveBeenCalled();
  });

  it("asks for both social fields when only one is filled", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ bdUserName: "Alex" });
    const { wrapper } = await page(`/register?code=${CODE}`);
    await fill(wrapper, {
      "register-name": "Ada",
      "register-email": "ada@example.com",
      "register-email-code": "123456",
      "register-password": "long-enough-pw",
      "register-confirm": "long-enough-pw",
      "register-handle": "@ada"
    });
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain(
      "Enter both a primary platform and account name, or leave both blank."
    );
    expect(registerApi.registerKol).not.toHaveBeenCalled();
  });
});
