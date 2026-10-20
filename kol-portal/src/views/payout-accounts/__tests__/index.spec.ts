import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import { ApiError } from "@/utils/http/error";
import * as api from "@/api/payout-account";
import type { PayoutAccountVO } from "@/api/payout-account";
import PayoutAccountsPage from "../index.vue";

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
  // jsdom has no blob URL support; plain functions so resetAllMocks cannot wipe them.
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: () => "blob:qr-view"
  });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: () => undefined });
});

function failure(errorCode: string, status: number) {
  return new ApiError({ errorCode, status, traceId: "test-trace" });
}

function account(overrides: Partial<PayoutAccountVO> = {}): PayoutAccountVO {
  return {
    id: "11",
    accountType: "PAYPAL",
    account: "ada@example.com",
    holderName: "Ada Lovelace",
    currency: "USD",
    network: null,
    qrFileId: null,
    status: "PENDING",
    defaultAccount: false,
    rejectReason: null,
    lastSubmittedAt: "2026-10-16T08:00:00Z",
    reviewedTime: null,
    ...overrides
  };
}

function usdt(overrides: Partial<PayoutAccountVO> = {}): PayoutAccountVO {
  return account({
    id: "12",
    accountType: "USDT",
    account: "TQ5xK9aZPn7vQm",
    holderName: null,
    network: "TRC20",
    qrFileId: "66",
    ...overrides
  });
}

async function page(items: PayoutAccountVO[]) {
  vi.mocked(api.listPayoutAccounts).mockResolvedValue(items);
  return mountPage();
}

async function mountPage() {
  const pinia = createPinia();
  setActivePinia(pinia);
  useLocaleStore().setLocale("en-US");
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/payout-accounts");
  const wrapper = mount(PayoutAccountsPage, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, pinia, router] }
  });
  wrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  vi.mocked(api.listPayoutAccounts).mockResolvedValue([]);
});

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount());
  document.body.innerHTML = "";
});

describe("payout accounts page", () => {
  it("shows the empty state with a create button in English and Chinese", async () => {
    const wrapper = await page([]);
    expect(wrapper.text()).toContain(
      "Add a PayPal or USDT account; once approved it is used to pay you."
    );
    expect(wrapper.get('[data-test="empty-create"]').text()).toBe("Add payout account");

    useLocaleStore().setLocale("zh-CN");
    await flushPromises();
    expect(wrapper.text()).toContain("添加 PayPal 或 USDT 账户，审核通过后用于收款。");
    expect(wrapper.text()).toContain("收款账户");
  });

  it("renders each account card with type, full account, status and default tag", async () => {
    const wrapper = await page([
      usdt({ status: "APPROVED", defaultAccount: true }),
      account({ id: "13", status: "REJECTED", rejectReason: "Wrong holder name" })
    ]);
    expect(wrapper.get('[data-test="account-12"] [data-test="type"]').text()).toBe("USDT · TRC20");
    expect(wrapper.get('[data-test="account-12"] [data-test="account"]').text()).toBe(
      "TQ5xK9aZPn7vQm"
    );
    expect(wrapper.get('[data-test="account-12"] [data-test="status"]').text()).toBe("Approved");
    expect(wrapper.find('[data-test="account-12"] [data-test="default-tag"]').exists()).toBe(true);

    const rejected = wrapper.get('[data-test="account-13"]');
    expect(rejected.get('[data-test="status"]').text()).toBe("Rejected");
    expect(rejected.get('[data-test="reject-reason"]').text()).toContain("Wrong holder name");
    expect(wrapper.text()).toContain("Ada Lovelace");
  });

  it("shows edit only for PENDING or REJECTED and set-default only for APPROVED non-default", async () => {
    const wrapper = await page([
      account({ id: "21", status: "PENDING" }),
      account({ id: "22", status: "REJECTED", rejectReason: "No" }),
      account({ id: "23", status: "APPROVED" }),
      account({ id: "24", status: "APPROVED", defaultAccount: true })
    ]);
    expect(wrapper.find('[data-test="account-21"] [data-test="edit"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="account-22"] [data-test="edit"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="account-23"] [data-test="edit"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="account-24"] [data-test="edit"]').exists()).toBe(false);

    expect(wrapper.find('[data-test="account-21"] [data-test="set-default"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="account-23"] [data-test="set-default"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="account-24"] [data-test="set-default"]').exists()).toBe(false);

    // Every account can be disabled; disabled ones never come back from the API.
    for (const id of ["21", "22", "23", "24"]) {
      expect(wrapper.find(`[data-test="account-${id}"] [data-test="disable"]`).exists()).toBe(true);
    }
  });

  it("lets the backend enforce the currently configured account limit", async () => {
    const five = [
      account({ id: "31" }),
      account({ id: "32" }),
      account({ id: "33" }),
      account({ id: "34" }),
      usdt({ id: "35" })
    ];
    const wrapper = await page(five);
    const button = wrapper.get('[data-test="create"]');
    expect(button.attributes("disabled")).toBeUndefined();
    expect(wrapper.find('[data-test="limit-hint"]').exists()).toBe(false);
  });

  it("asks for confirmation before disabling and reloads afterwards", async () => {
    const confirmSpy = vi
      .spyOn(ElMessageBox, "confirm")
      .mockResolvedValue({ action: "confirm" } as never);
    vi.mocked(api.disablePayoutAccount).mockResolvedValue(null);
    const wrapper = await page([account({ id: "41" })]);

    await wrapper.get('[data-test="account-41"] [data-test="disable"]').trigger("click");
    expect(confirmSpy).toHaveBeenCalledTimes(1);
    expect(api.disablePayoutAccount).toHaveBeenCalledWith("41");
    expect(api.listPayoutAccounts).toHaveBeenCalledTimes(2);

    confirmSpy.mockRestore();
    const cancelled = vi.spyOn(ElMessageBox, "confirm").mockRejectedValue(new Error("cancelled"));
    vi.mocked(api.disablePayoutAccount).mockClear();
    await wrapper.get('[data-test="account-41"] [data-test="disable"]').trigger("click");
    expect(api.disablePayoutAccount).not.toHaveBeenCalled();
    cancelled.mockRestore();
  });

  it("sets an approved account as the default and reloads", async () => {
    vi.mocked(api.setDefaultPayoutAccount).mockResolvedValue(null);
    const wrapper = await page([account({ id: "51", status: "APPROVED" })]);
    await wrapper.get('[data-test="account-51"] [data-test="set-default"]').trigger("click");
    expect(api.setDefaultPayoutAccount).toHaveBeenCalledWith("51");
    expect(api.listPayoutAccounts).toHaveBeenCalledTimes(2);
  });

  it("loads the QR image through the file endpoint when viewing it", async () => {
    vi.mocked(api.downloadQrFile).mockResolvedValue(new Blob([new Uint8Array([1])]));
    const wrapper = await page([usdt()]);
    await wrapper.get('[data-test="account-12"] [data-test="view-qr"]').trigger("click");
    await flushPromises();
    expect(api.downloadQrFile).toHaveBeenCalledWith("66");
  });

  it("closes the dialog, explains and reloads when the account was approved meanwhile", async () => {
    vi.mocked(api.updatePayoutAccount).mockRejectedValue(failure("PAYOUT_ACCOUNT_LOCKED", 409));
    const wrapper = await page([account({ id: "61", status: "PENDING" })]);

    await wrapper.get('[data-test="account-61"] [data-test="edit"]').trigger("click");
    await flushPromises();
    const dialog = document.querySelector(".el-dialog");
    expect(dialog).not.toBeNull();

    const form = document.querySelector(".el-dialog form") as HTMLFormElement;
    form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await flushPromises();

    expect(api.updatePayoutAccount).toHaveBeenCalled();
    expect(api.listPayoutAccounts).toHaveBeenCalledTimes(2);
  });

  it("shows the API message with a retry button when the list cannot load", async () => {
    vi.mocked(api.listPayoutAccounts)
      .mockRejectedValueOnce(failure("COMMON_INTERNAL_ERROR", 500))
      .mockResolvedValueOnce([account({ id: "71" })]);
    const wrapper = await mountPage();
    expect(wrapper.get('[data-test="list-error"]').text()).toContain(
      "Something went wrong on our side"
    );
    expect(wrapper.text()).toContain("Retry");

    await wrapper
      .findAll("button")
      .find(b => b.text() === "Retry")!
      .trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="list-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("ada@example.com");
  });

  it("shows the PayPal currency on the card (task 25b §3.6)", async () => {
    const wrapper = await page([account({ currency: "EUR" }), usdt({})]);
    const types = wrapper.findAll('[data-test="type"]');
    expect(types[0]!.text()).toContain("PayPal · EUR");
    expect(types[1]!.text()).toContain("USDT · TRC20");
    wrapper.unmount();
  });
});
