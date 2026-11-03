import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElSelect } from "element-plus";
import { createPinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getMe, updateMe } from "@/api/auth";
import { listPayoutAccounts, uploadQrFile } from "@/api/payout-account";
import { ApiError } from "@/utils/http/error";
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
/** Element Plus debounces the visible error state by 100 ms. */
async function settleErrors() {
  await flushPromises();
  await new Promise(resolve => setTimeout(resolve, 120));
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
  it("shows email and system ID as plain CopyText (L-101)", async () => {
    await page();
    // L-101: email / system ID are CopyText, not read-only inputs (other fields may use inputs).
    const emailItem = wrapper.get('[data-test="profile-email"]');
    const systemItem = wrapper.get('[data-test="profile-system-id"]');
    expect(emailItem.find("input").exists()).toBe(false);
    expect(systemItem.find("input").exists()).toBe(false);
    expect(emailItem.get('[data-test="value"]').text()).toBe("t60_creator@example.test");
    expect(systemItem.get('[data-test="value"]').text()).toBe("t60_creator");
    expect(emailItem.find('[data-test="copy"]').exists()).toBe(true);
    expect(systemItem.find('[data-test="copy"]').exists()).toBe(true);
  });

  it("makes the title the top of the page, with the identity right below it in the first card", async () => {
    await page();
    const firstCard = wrapper.findAll(".section-card")[0];
    expect(wrapper.get(".account-page").element.firstElementChild).toBe(firstCard.element);
    const header = firstCard.get(".el-card__header");
    expect(header.element.firstElementChild?.tagName).toBe("H1");
    expect(wrapper.findAll(".section-card h1")).toHaveLength(1);
    expect(header.get("h1").element.nextElementSibling).toBe(
      header.get('[data-test="name-code"]').element
    );
    expect(wrapper.findAll('[data-test="name-code"]')).toHaveLength(1);
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

describe("creator profile feedback (task 104)", () => {
  it("shows one nickname error under the field and no retry for a validation failure", async () => {
    await page();
    await wrapper.get('[data-test="profile-name"]').setValue("");
    await wrapper.get('[data-test="profile-save"]').trigger("click");
    await settleErrors();
    const message = i18n.global.t("register.nameRequired");
    expect(wrapper.text().split(message)).toHaveLength(2);
    const nickname = wrapper.get('[data-test="profile-name"]').element.closest(".el-form-item");
    expect(nickname?.textContent).toContain(message);
    expect(wrapper.find('[data-test="load-retry"]').exists()).toBe(false);
    expect(wrapper.find(".account-page > .el-alert").exists()).toBe(false);
    wrapper.unmount();
  });

  it("shows the platform and handle pair error under the handle field", async () => {
    await page();
    await wrapper.get('[data-test="profile-handle"]').setValue("");
    await wrapper.get('[data-test="profile-save"]').trigger("click");
    await settleErrors();
    const field = wrapper.get('[data-test="profile-handle"]').element.closest(".el-form-item");
    expect(field?.textContent).toContain(i18n.global.t("register.socialPairRequired"));
    wrapper.unmount();
  });

  it("keeps the retry only for a failed profile load", async () => {
    vi.mocked(getMe).mockRejectedValue(
      new ApiError({ status: 500, errorCode: "COMMON_INTERNAL_ERROR", traceId: "t104-load" })
    );
    await page();
    expect(wrapper.find('[data-test="load-retry"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("t104-load");
    wrapper.unmount();
  });

  it("uploads the USDT QR code through the upload button and shows the uploaded state", async () => {
    vi.mocked(uploadQrFile).mockResolvedValue({ fileId: "9007199254740993" });
    await page();
    const typeSelect = wrapper
      .findAllComponents(ElSelect)
      .find(select => select.props("modelValue") === "PAYPAL");
    if (!typeSelect) throw new Error("Payout type selector was not rendered");
    typeSelect.vm.$emit("change", "USDT");
    await flushPromises();
    const input = wrapper.get('[data-test="qr-upload"] input[type="file"]');
    const file = new File(["png"], "t104-qr.png", { type: "image/png" });
    Object.defineProperty(input.element, "files", { value: [file] });
    await input.trigger("change");
    await flushPromises();
    expect(uploadQrFile).toHaveBeenCalledWith(file);
    expect(wrapper.text()).toContain(i18n.global.t("account.qrUploaded"));
    wrapper.unmount();
  });

  it("shows a rejected upload under the QR field", async () => {
    vi.mocked(uploadQrFile).mockRejectedValue(
      new ApiError({ status: 400, errorCode: "COMMON_VALIDATION_FAILED", traceId: "t104-qr" })
    );
    await page();
    const typeSelect = wrapper
      .findAllComponents(ElSelect)
      .find(select => select.props("modelValue") === "PAYPAL");
    if (!typeSelect) throw new Error("Payout type selector was not rendered");
    typeSelect.vm.$emit("change", "USDT");
    await flushPromises();
    const input = wrapper.get('[data-test="qr-upload"] input[type="file"]');
    const file = new File(["png"], "t104-qr.png", { type: "image/png" });
    Object.defineProperty(input.element, "files", { value: [file] });
    await input.trigger("change");
    await settleErrors();
    expect(wrapper.text()).toContain(i18n.global.t("errors.COMMON_VALIDATION_FAILED"));
    wrapper.unmount();
  });
});
