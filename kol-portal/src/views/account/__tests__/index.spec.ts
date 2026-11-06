import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElSelect, ElTabs } from "element-plus";
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
  it("keeps the nickname above the creator code as read-only text (task 122)", async () => {
    await page();
    const identity = wrapper.get('[data-test="name-code"]');
    expect(identity.get('[data-test="identity-name"]').text()).toBe("Creator");
    expect(identity.get('[data-test="identity-code"]').text()).toBe("t60_creator");
    const nickname = wrapper.get('[data-test="profile-name"]');
    expect(nickname.find("input").exists()).toBe(false);
    expect(nickname.get('[data-test="value"]').text()).toBe("Creator");
    expect(nickname.find('[data-test="copy"]').exists()).toBe(true);
    expect(wrapper.text()).toContain(i18n.global.t("account.nicknameHint"));
  });

  it("submits only the primary social slot, never the nickname", async () => {
    vi.mocked(updateMe).mockResolvedValue({
      kolCode: "t60_creator",
      name: "Creator",
      email: "t60_creator@example.test",
      tenantName: "t60_company",
      country: null,
      primaryPlatform: "YouTube",
      primaryHandle: "@creator"
    });
    await page();
    const platformSelect = wrapper
      .findAllComponents(ElSelect)
      .find(select => select.props("modelValue") === "TikTok");
    if (!platformSelect) throw new Error("Primary platform selector was not rendered");
    platformSelect.vm.$emit("update:modelValue", "YouTube");
    await flushPromises();
    await wrapper.get('[data-test="profile-handle"]').setValue("@creator");
    await wrapper.get('[data-test="profile-save"]').trigger("click");
    await flushPromises();
    expect(updateMe).toHaveBeenCalledWith({
      primaryPlatform: "YouTube",
      primaryHandle: "@creator"
    });
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

  it("keeps the title and identity above the tabs", async () => {
    await page();
    const header = wrapper.get(".account-header");
    expect(wrapper.get(".account-page").element.firstElementChild).toBe(header.element);
    expect(header.element.firstElementChild?.tagName).toBe("H1");
    expect(header.get("h1").element.nextElementSibling).toBe(
      header.get('[data-test="name-code"]').element
    );
    expect(wrapper.findAll('[data-test="name-code"]')).toHaveLength(1);
  });

  it("groups the cards into profile, payout and password tabs (smoke item 15)", async () => {
    await page();
    const labels = wrapper.findAll(".el-tabs__item").map(tab => tab.text());
    expect(labels).toEqual([
      i18n.global.t("account.tabProfile"),
      i18n.global.t("account.tabPayout"),
      i18n.global.t("account.tabPassword")
    ]);
    /** Element Plus hides inactive panes with `v-show`; the inline display is the visible state. */
    const paneShown = (index: number): boolean =>
      (wrapper.findAll(".el-tab-pane")[index]!.element as HTMLElement).style.display !== "none";
    const panes = wrapper.findAll(".el-tab-pane");
    expect(panes).toHaveLength(3);
    // Profile first; the other two stay rendered but hidden, so their forms keep their state.
    expect(paneShown(0)).toBe(true);
    expect(panes[0]!.find('[data-test="profile-save"]').exists()).toBe(true);
    expect(paneShown(1)).toBe(false);
    expect(paneShown(2)).toBe(false);

    const tabs = wrapper.findComponent(ElTabs);
    tabs.vm.$emit("update:modelValue", "payout");
    await flushPromises();
    expect(paneShown(1)).toBe(true);
    expect(wrapper.findAll(".el-tab-pane")[1]!.text()).toContain(
      i18n.global.t("account.payoutInfo")
    );
    expect(paneShown(0)).toBe(false);

    tabs.vm.$emit("update:modelValue", "password");
    await flushPromises();
    expect(paneShown(2)).toBe(true);
    expect(wrapper.findAll(".el-tab-pane")[2]!.find('[data-test="password-form"]').exists()).toBe(
      true
    );
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
