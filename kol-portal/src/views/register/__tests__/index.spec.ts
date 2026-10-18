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

const CODE = "A".repeat(32);
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
  it("shows the invalid-link message when the code is missing or rejected", async () => {
    const missing = await page("/register");
    expect(missing.wrapper.text()).toContain(
      "This registration link is invalid or has expired. Please contact your representative for a new link."
    );
    expect(registerApi.previewRegister).not.toHaveBeenCalled();

    vi.mocked(registerApi.previewRegister).mockRejectedValue(
      failure("KOL_REGISTER_CODE_INVALID", 404)
    );
    const rejected = await page(`/register?c=${CODE}`);
    expect(rejected.wrapper.text()).toContain("registration link is invalid");
    useLocaleStore().setLocale("zh-CN");
    await flushPromises();
    expect(rejected.wrapper.text()).toContain("注册链接无效或已失效，请联系您的商务获取新链接");
  });

  it("shows the company under the title when the link is valid", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ tenantName: "Acme" });
    const { wrapper } = await page(`/register?c=${CODE}`);
    expect(wrapper.find("h1").text()).toBe("Create your account");
    expect(wrapper.text()).toContain("Join Acme");
    useLocaleStore().setLocale("zh-CN");
    await flushPromises();
    expect(wrapper.find("h1").text()).toBe("创建账号");
    expect(wrapper.text()).toContain("加入 Acme");
  });

  it("keeps the send button usable and shows the API message on a repeat click", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ tenantName: "Acme" });
    vi.mocked(registerApi.sendRegisterCode).mockResolvedValue(null);
    const { wrapper } = await page(`/register?c=${CODE}`);
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
    expect(wrapper.text()).toContain("A verification code was sent too recently");
  });

  it("validates the form before calling the API", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ tenantName: "Acme" });
    const { wrapper } = await page(`/register?c=${CODE}`);
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("Please enter a valid email address.");
    expect(registerApi.registerKol).not.toHaveBeenCalled();
  });

  it("signs in and opens home after a successful registration", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ tenantName: "Acme" });
    vi.mocked(registerApi.registerKol).mockResolvedValue(session);
    const { wrapper, router } = await page(`/register?c=${CODE}`);
    await fill(wrapper, {
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
      whatsapp: "+15551234567",
      primaryPlatform: "TikTok",
      primaryHandle: "ada",
      primaryUrl: null,
      name: null,
      country: null
    });
    expect(useSessionStore().getToken()).toBe("reg-token");
    expect(loadSession()?.token).toBe("reg-token");
  });

  it("offers sign-in with the email when the address is already registered", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ tenantName: "Acme" });
    vi.mocked(registerApi.registerKol).mockRejectedValue(failure("KOL_EMAIL_EXISTS", 409));
    const { wrapper } = await page(`/register?c=${CODE}`);
    await fill(wrapper, {
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

  it("requires a WhatsApp number before registering (task 25b §3.6)", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({ tenantName: "Acme" });
    const { wrapper } = await page(`/register?c=${CODE}`);
    await fill(wrapper, {
      "register-email": "ada@example.com",
      "register-email-code": "123456",
      "register-password": "long-enough-pw",
      "register-confirm": "long-enough-pw",
      "register-handle": "@ada"
    });
    choosePlatform(wrapper, "TikTok");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("Enter a WhatsApp number starting with + and 8–15 digits.");
    expect(registerApi.registerKol).not.toHaveBeenCalled();

    await fill(wrapper, { "register-whatsapp": "not-a-number" });
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("Enter a WhatsApp number starting with + and 8–15 digits.");
    expect(registerApi.registerKol).not.toHaveBeenCalled();

    await fill(wrapper, { "register-whatsapp": "+15551234567" });
    vi.mocked(registerApi.registerKol).mockResolvedValue(session);
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(registerApi.registerKol).toHaveBeenCalledWith(
      expect.objectContaining({ whatsapp: "+15551234567" })
    );
  });
});
