import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElOption, ElSelect } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import { ApiError } from "@/utils/http/error";
import * as api from "@/api/payout-account";
import type { PayoutAccountVO } from "@/api/payout-account";
import PayoutAccountDialog from "../PayoutAccountDialog.vue";

vi.mock("@/api/payout-account", () => ({
  listPayoutAccounts: vi.fn(),
  createPayoutAccount: vi.fn(),
  updatePayoutAccount: vi.fn(),
  setDefaultPayoutAccount: vi.fn(),
  disablePayoutAccount: vi.fn(),
  uploadQrFile: vi.fn(),
  downloadQrFile: vi.fn()
}));

const wrappers: VueWrapper[] = [];

beforeAll(() => {
  // jsdom has no blob URL support; the preview only needs a stable string. Plain functions, not
  // mocks, so `vi.resetAllMocks` below cannot wipe them.
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: () => "blob:qr-preview"
  });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: () => undefined });
});

function failure(errorCode: string, status = 409) {
  return new ApiError({ errorCode, status, traceId: "test-trace" });
}

function account(overrides: Partial<PayoutAccountVO> = {}): PayoutAccountVO {
  return {
    id: "11",
    accountType: "USDT",
    account: "TQ5xK9aZPn7vQm",
    holderName: null,
    currency: null,
    network: "TRC20",
    qrFileId: null,
    status: "PENDING",
    defaultAccount: false,
    rejectReason: null,
    lastSubmittedAt: "2026-10-16T08:00:00Z",
    reviewedTime: null,
    ...overrides
  };
}

async function openDialog(editing: PayoutAccountVO | null) {
  const pinia = createPinia();
  setActivePinia(pinia);
  useLocaleStore().setLocale("en-US");
  const wrapper = mount(PayoutAccountDialog, {
    props: { modelValue: true, account: editing },
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, pinia] }
  });
  wrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}

async function chooseType(wrapper: VueWrapper, value: "PAYPAL" | "USDT") {
  await wrapper.find(`input[value="${value}"]`).setValue(true);
}

async function chooseNetwork(wrapper: VueWrapper, value: string) {
  const select = wrapper.findAllComponents(ElSelect)[0];
  if (!select) throw new Error("network select was not rendered");
  select.vm.$emit("update:model-value", value);
  await flushPromises();
}

async function submit(wrapper: VueWrapper) {
  await wrapper.find("form").trigger("submit");
  await flushPromises();
}

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
});

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount());
  document.body.innerHTML = "";
});

describe("payout account dialog", () => {
  it("requires the type before any other field matters", async () => {
    const wrapper = await openDialog(null);
    await submit(wrapper);
    expect(wrapper.text()).toContain("Choose a type first.");
    expect(api.createPayoutAccount).not.toHaveBeenCalled();
  });

  it("validates the PayPal fields", async () => {
    const wrapper = await openDialog(null);
    await chooseType(wrapper, "PAYPAL");
    await wrapper.get("#payout-account").setValue("ab");
    await submit(wrapper);
    expect(wrapper.text()).toContain("Enter 3–128 characters.");

    await wrapper.get("#payout-account").setValue("ada@example.com");
    await submit(wrapper);
    expect(wrapper.text()).toContain("Enter the receiver name (1–64 characters).");
    expect(api.createPayoutAccount).not.toHaveBeenCalled();

    await wrapper.get("#payout-holder").setValue("Ada Lovelace");
    await submit(wrapper);
    expect(api.createPayoutAccount).toHaveBeenCalledWith({
      accountType: "PAYPAL",
      account: "ada@example.com",
      holderName: "Ada Lovelace",
      currency: "USD"
    });
    expect(wrapper.emitted("saved")).toHaveLength(1);
  });

  it("validates the USDT fields and offers the five networks", async () => {
    const wrapper = await openDialog(null);
    await chooseType(wrapper, "USDT");

    const networks = wrapper
      .findAllComponents(ElOption)
      .map(option => String(option.props("value")));
    expect(networks).toEqual(["TRC20", "ERC20", "BEP20", "SOL", "TON"]);

    await wrapper.get("#payout-account").setValue("TQ5x K9aZ");
    await submit(wrapper);
    expect(wrapper.text()).toContain("The address may contain letters and digits only.");

    await wrapper.get("#payout-account").setValue("TQ5xK9aZPn7vQm");
    await submit(wrapper);
    expect(wrapper.text()).toContain("Choose the network.");

    await chooseNetwork(wrapper, "TRC20");
    await submit(wrapper);
    expect(api.createPayoutAccount).toHaveBeenCalledWith({
      accountType: "USDT",
      account: "TQ5xK9aZPn7vQm",
      network: "TRC20"
    });
  });

  it("pre-checks the QR image type and size before uploading", async () => {
    const wrapper = await openDialog(null);
    await chooseType(wrapper, "USDT");
    const input = wrapper.get('[data-test="qr-input"]').element as HTMLInputElement;

    Object.defineProperty(input, "files", {
      configurable: true,
      value: [new File([new Uint8Array(10)], "qr.gif", { type: "image/gif" })]
    });
    await wrapper.get('[data-test="qr-input"]').trigger("change");
    expect(wrapper.text()).toContain("The file must be a PNG or JPEG image.");
    expect(api.uploadQrFile).not.toHaveBeenCalled();

    const oversized = new File([new Uint8Array(10)], "qr.png", { type: "image/png" });
    Object.defineProperty(oversized, "size", { value: 2 * 1024 * 1024 + 1 });
    Object.defineProperty(input, "files", { configurable: true, value: [oversized] });
    await wrapper.get('[data-test="qr-input"]').trigger("change");
    expect(wrapper.text()).toContain("The file is larger than 2 MB.");
    expect(api.uploadQrFile).not.toHaveBeenCalled();
  });

  it("uploads an accepted QR image and sends its file id with the account", async () => {
    vi.mocked(api.uploadQrFile).mockResolvedValue({ fileId: "66" });
    const wrapper = await openDialog(null);
    await chooseType(wrapper, "USDT");
    await wrapper.get("#payout-account").setValue("TQ5xK9aZPn7vQm");
    await chooseNetwork(wrapper, "ERC20");

    const input = wrapper.get('[data-test="qr-input"]').element as HTMLInputElement;
    const image = new File([new Uint8Array(10)], "qr.png", { type: "image/png" });
    Object.defineProperty(input, "files", { configurable: true, value: [image] });
    await wrapper.get('[data-test="qr-input"]').trigger("change");
    await flushPromises();

    expect(api.uploadQrFile).toHaveBeenCalledWith(image);
    expect(wrapper.find('[data-test="qr-preview"]').exists()).toBe(true);

    await submit(wrapper);
    expect(api.createPayoutAccount).toHaveBeenCalledWith({
      accountType: "USDT",
      account: "TQ5xK9aZPn7vQm",
      network: "ERC20",
      qrFileId: "66"
    });
  });

  it("drops the QR reference when the image is removed again", async () => {
    vi.mocked(api.uploadQrFile).mockResolvedValue({ fileId: "66" });
    const wrapper = await openDialog(null);
    await chooseType(wrapper, "USDT");
    await wrapper.get("#payout-account").setValue("TQ5xK9aZPn7vQm");
    await chooseNetwork(wrapper, "ERC20");

    const input = wrapper.get('[data-test="qr-input"]').element as HTMLInputElement;
    const image = new File([new Uint8Array(10)], "qr.png", { type: "image/png" });
    Object.defineProperty(input, "files", { configurable: true, value: [image] });
    await wrapper.get('[data-test="qr-input"]').trigger("change");
    await flushPromises();

    await wrapper.get('[data-test="qr-remove"]').trigger("click");
    expect(wrapper.find('[data-test="qr-preview"]').exists()).toBe(false);
    await submit(wrapper);
    expect(api.createPayoutAccount).toHaveBeenCalledWith({
      accountType: "USDT",
      account: "TQ5xK9aZPn7vQm",
      network: "ERC20"
    });
  });

  it("loads the attached QR of an edited account from the file endpoint", async () => {
    vi.mocked(api.downloadQrFile).mockResolvedValue(new Blob([new Uint8Array([1])]));
    const wrapper = await openDialog(account({ qrFileId: "66" }));
    await flushPromises();
    expect(api.downloadQrFile).toHaveBeenCalledWith("66");
    expect(wrapper.find('[data-test="qr-preview"]').exists()).toBe(true);
  });

  it("prefills an edited account and resubmits it", async () => {
    const edited = account({
      accountType: "PAYPAL",
      account: "old@example.com",
      holderName: "Old",
      network: null,
      currency: "EUR",
      status: "REJECTED"
    });
    const wrapper = await openDialog(edited);
    expect((wrapper.get("#payout-account").element as HTMLInputElement).value).toBe(
      "old@example.com"
    );
    await wrapper.get("#payout-account").setValue("new@example.com");
    await wrapper.get("#payout-holder").setValue("New Name");
    await submit(wrapper);
    expect(api.updatePayoutAccount).toHaveBeenCalledWith("11", {
      accountType: "PAYPAL",
      account: "new@example.com",
      holderName: "New Name",
      currency: "EUR"
    });
    expect(wrapper.emitted("saved")).toHaveLength(1);
  });

  it("reports a locked account instead of saving and lets the page refresh", async () => {
    vi.mocked(api.updatePayoutAccount).mockRejectedValue(failure("PAYOUT_ACCOUNT_LOCKED"));
    const wrapper = await openDialog(account());
    await submit(wrapper);
    expect(wrapper.emitted("conflict")).toHaveLength(1);
    expect(wrapper.emitted("saved")).toBeUndefined();
  });

  it("offers PayPal currencies with USD default and sends the chosen one (task 25b)", async () => {
    const wrapper = await openDialog(null);
    await chooseType(wrapper, "PAYPAL");
    const currencySelect = wrapper
      .findAllComponents(ElSelect)
      .find(item => item.find('[data-test="currency-input"]').exists());
    expect(currencySelect).toBeDefined();
    expect(currencySelect!.props("modelValue")).toBe("USD");
    expect(
      currencySelect!.findAllComponents(ElOption).map(option => String(option.props("value")))
    ).toEqual(["USD", "EUR", "GBP", "CNY"]);
    // The default is USD, and the payload always carries a currency for PayPal.
    await wrapper.get("#payout-account").setValue("ada@example.com");
    await wrapper.get("#payout-holder").setValue("Ada");
    await submit(wrapper);
    expect(api.createPayoutAccount).toHaveBeenCalledWith(
      expect.objectContaining({ accountType: "PAYPAL", currency: "USD" })
    );
  });
});
