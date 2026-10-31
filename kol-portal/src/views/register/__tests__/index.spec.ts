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
  registerKol: vi.fn()
}));

/**
 * Registration form after 02.01 (task 32 §2.5): the fields are exactly the invitation code,
 * account name, email, password and confirm password (required) plus phone, WhatsApp, main
 * platform and social account (optional). No e-mail verification step, no country field.
 */
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
  select.vm.$emit("update:modelValue", value);
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

describe("register page (02.01)", () => {
  it("renders exactly the 02.01 fields: no verification code, no country", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({
      bdUserName: "Alex",
      tenantName: "Acme"
    });
    const { wrapper } = await page(`/register?code=${CODE}`);
    const labels = wrapper.findAll(".el-form-item__label").map(item => item.text());
    expect(labels).toEqual([
      "Invitation code",
      "Name",
      "Email",
      "New password",
      "Confirm password",
      "Phone number (optional)",
      "WhatsApp (optional)",
      "Main platform (optional)",
      "Social account"
    ]);
    expect(wrapper.find("#register-email-code").exists()).toBe(false);
    expect(wrapper.find("#register-country").exists()).toBe(false);
    expect(wrapper.find("#register-url").exists()).toBe(false);
    expect(wrapper.find("form").text()).not.toContain("verification");
  });

  it("fills the code read-only from the link, required and editable without a link", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({
      bdUserName: "Alex",
      tenantName: "Acme"
    });
    const linked = await page(`/register?code=${CODE}`);
    const linkedCode = linked.wrapper.get("#register-code");
    expect((linkedCode.element as HTMLInputElement).value).toBe(CODE);
    expect(linkedCode.attributes("readonly")).toBeDefined();
    expect(linked.wrapper.text()).toContain("Filled from your invitation link");

    const manual = await page("/register");
    const manualCode = manual.wrapper.get("#register-code");
    expect((manualCode.element as HTMLInputElement).value).toBe("");
    expect(manualCode.attributes("readonly")).toBeUndefined();
    // Submitting without a code never reaches the API.
    await manual.wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(registerApi.registerKol).not.toHaveBeenCalled();
  });

  it("shows the inviting BD, or the tenant when the code belongs to the super admin", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({
      bdUserName: "Alex",
      tenantName: "Acme"
    });
    const withBd = await page(`/register?code=${CODE}`);
    expect(withBd.wrapper.text()).toContain("Invited by Alex");

    vi.mocked(registerApi.previewRegister).mockResolvedValue({
      bdUserName: null,
      tenantName: "Acme"
    });
    const withoutBd = await page(`/register?code=${CODE}`);
    expect(withoutBd.wrapper.text()).toContain("Invited by Acme");
  });

  it("shows invalid links with a code and keeps the form closed", async () => {
    vi.mocked(registerApi.previewRegister).mockRejectedValue(
      failure("KOL_REGISTER_CODE_INVALID", 404)
    );
    const rejected = await page(`/register?code=${CODE}`);
    expect(rejected.wrapper.text()).toContain("registration link is invalid");
    expect(rejected.wrapper.find("#register-email").exists()).toBe(false);
  });

  it("shows a retry-later message when invitation preview is rate limited", async () => {
    vi.mocked(registerApi.previewRegister).mockRejectedValue(failure("RATE_LIMITED", 429));
    const { wrapper } = await page(`/register?code=${CODE}`);
    expect(wrapper.text()).toContain("Too many attempts. Please try again later.");
    expect(wrapper.find("#register-email").exists()).toBe(true);
  });

  it("validates the form before calling the API", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({
      bdUserName: "Alex",
      tenantName: "Acme"
    });
    const { wrapper } = await page(`/register?code=${CODE}`);
    await wrapper.get("#register-name").setValue("Ada");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("Please enter a valid email address.");
    expect(registerApi.registerKol).not.toHaveBeenCalled();
  });

  it("signs in and opens home after a successful registration", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({
      bdUserName: "Alex",
      tenantName: "Acme"
    });
    vi.mocked(registerApi.registerKol).mockResolvedValue(session);
    const { wrapper, router } = await page(`/register?code=${CODE}`);
    await fill(wrapper, {
      "register-name": "Ada",
      "register-email": "ada@example.com",
      "register-password": "register-pass-1",
      "register-confirm": "register-pass-1",
      "register-whatsapp": "+15551234567",
      "register-handle": "ada"
    });
    choosePlatform(wrapper, "TikTok");
    await wrapper.get("form").trigger("submit");
    await vi.waitFor(
      () => {
        expect(router.currentRoute.value.path).toBe("/");
      },
      { timeout: 10000 }
    );
    expect(registerApi.registerKol).toHaveBeenCalledWith({
      code: CODE,
      email: "ada@example.com",
      password: "register-pass-1",
      name: "Ada",
      phone: null,
      whatsapp: "+15551234567",
      primaryPlatform: "TikTok",
      primaryHandle: "ada"
    });
    expect(useSessionStore().getToken()).toBe("reg-token");
    expect(loadSession()?.token).toBe("reg-token");
  });

  it("offers sign-in with the email when the address is already registered", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({
      bdUserName: "Alex",
      tenantName: "Acme"
    });
    vi.mocked(registerApi.registerKol).mockRejectedValue(failure("KOL_EMAIL_EXISTS", 409));
    const { wrapper } = await page(`/register?code=${CODE}`);
    await fill(wrapper, {
      "register-name": "Ada",
      "register-email": "ada@example.com",
      "register-password": "register-pass-1",
      "register-confirm": "register-pass-1"
    });
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("This email is already registered. Please sign in.");
    expect(wrapper.get("a.register-login").attributes("href")).toBe("/login?email=ada@example.com");
  });

  it("allows optional contact and social fields to stay blank", async () => {
    vi.mocked(registerApi.previewRegister).mockResolvedValue({
      bdUserName: "Alex",
      tenantName: "Acme"
    });
    vi.mocked(registerApi.registerKol).mockResolvedValue(session);
    const { wrapper } = await page(`/register?code=${CODE}`);
    await fill(wrapper, {
      "register-name": "Ada",
      "register-email": "ada@example.com",
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
    vi.mocked(registerApi.previewRegister).mockResolvedValue({
      bdUserName: "Alex",
      tenantName: "Acme"
    });
    vi.mocked(registerApi.registerKol).mockResolvedValue(session);
    const { wrapper } = await page("/register");
    await fill(wrapper, {
      "register-code": CODE,
      "register-name": "Ada",
      "register-email": "ada@example.com",
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
    vi.mocked(registerApi.previewRegister).mockResolvedValue({
      bdUserName: "Alex",
      tenantName: "Acme"
    });
    const { wrapper } = await page(`/register?code=${CODE}`);
    await fill(wrapper, {
      "register-name": "Ada",
      "register-email": "ada@example.com",
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
