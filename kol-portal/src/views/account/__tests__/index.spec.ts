import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getMe, updateMe } from "@/api/auth";
import { listPayoutAccounts } from "@/api/payout-account";
import { i18n } from "@/locales";
import AccountPage from "../index.vue";

vi.mock("@/api/auth", () => ({ getMe: vi.fn(), updateMe: vi.fn(), changePassword: vi.fn() }));
vi.mock("@/api/payout-account", () => ({
  listPayoutAccounts: vi.fn(),
  createPayoutAccount: vi.fn(),
  updatePayoutAccount: vi.fn(),
  uploadQrFile: vi.fn()
}));
let wrapper: VueWrapper;
beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  vi.mocked(getMe).mockResolvedValue({
    kolCode: "t60_creator",
    name: "Creator",
    email: "t60_creator@example.test",
    tenantName: "t60_company",
    country: null,
    primaryPlatform: "TikTok",
    primaryHandle: "creator"
  });
  vi.mocked(listPayoutAccounts).mockResolvedValue([]);
});
afterEach(() => wrapper?.unmount());
async function page() {
  wrapper = mount(AccountPage, {
    global: { plugins: [ElementPlus, i18n, createPinia()], stubs: { RouterLink: true } }
  });
  await flushPromises();
}

describe("creator profile validation", () => {
  it("preserves the name above the creator code alongside editable profile fields", async () => {
    await page();
    const identity = wrapper.get('[data-test="name-code"]');
    expect(identity.get('[data-test="identity-name"]').text()).toBe("Creator");
    expect(identity.get('[data-test="identity-code"]').text()).toBe("t60_creator");
    expect(wrapper.get('[data-test="profile-name"]').element).toBeInstanceOf(HTMLInputElement);
  });
  it("enforces the backend handle length instead of allowing 255 characters", async () => {
    await page();
    expect(wrapper.get('[data-test="profile-handle"]').attributes("maxlength")).toBe("128");
    await wrapper.get('[data-test="profile-handle"]').setValue("x".repeat(129));
    await wrapper.get('[data-test="profile-save"]').trigger("click");
    expect(updateMe).not.toHaveBeenCalled();
  });
  it("checks the platform and trimmed handle as a pair", async () => {
    await page();
    await wrapper.get('[data-test="profile-handle"]').setValue("   ");
    await wrapper.get('[data-test="profile-save"]').trigger("click");
    expect(updateMe).not.toHaveBeenCalled();
  });
});
