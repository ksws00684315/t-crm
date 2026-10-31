import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElDialog, ElSelect } from "element-plus";
import { createPinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/creator";
import { uploadQrFile } from "@/api/payout-account";
import { enUS, i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import FundsPage from "../index.vue";

vi.mock("@/api/creator", () => ({
  getFundSummary: vi.fn(),
  getFundRecords: vi.fn(),
  getWithdrawals: vi.fn(),
  getWithdrawalConditions: vi.fn(),
  getWithdrawalAccounts: vi.fn(),
  submitWithdrawal: vi.fn(),
  withdrawWithdrawal: vi.fn()
}));
vi.mock("@/api/payout-account", () => ({ uploadQrFile: vi.fn() }));

let wrapper: VueWrapper;
const conditions: api.WithdrawalConditions = {
  currency: "CNY",
  minimum: "200.00",
  version: 3,
  available: "1000.00",
  occupied: "0.00",
  frozen: "0.00",
  estimated: "1000.00",
  withdrawalFrozen: false,
  canApply: true,
  unverifiedCount: 0
};

beforeEach(() => {
  vi.resetAllMocks();
  i18n.global.locale.value = "en-US";
  vi.mocked(api.getFundSummary).mockResolvedValue({
    fundSummary: {
      totalIncomeCny: "0.00",
      commissionIncomeCny: "0.00",
      estimatedCommissionCny: "1000.00",
      totalCommissionCny: "0.00",
      withdrawnCny: "0.00",
      withdrawingCny: "0.00",
      frozenCny: "0.00",
      receivedPrepayCny: "0.00",
      availableCny: "1000.00",
      prepayDeductionCny: "0.00",
      prepayOutstandingCny: "0.00",
      asOf: "2026-10-26T00:00:00Z"
    }
  });
  vi.mocked(api.getFundRecords).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 });
  vi.mocked(api.getWithdrawals).mockResolvedValue({ items: [], total: 0, page: 1, size: 50 });
  vi.mocked(api.getWithdrawalConditions).mockResolvedValue({ ...conditions });
  vi.mocked(api.getWithdrawalAccounts).mockResolvedValue([
    {
      id: "10",
      accountType: "PAYPAL",
      account: "p***@example.test",
      holderName: "Creator",
      currency: "USD",
      network: null
    }
  ]);
});
afterEach(() => {
  wrapper?.unmount();
  document.body.innerHTML = "";
});

const dataTableStub = {
  name: "DataTable",
  props: ["rows"],
  template: '<div><slot v-for="row in rows" name="cell-amount" :row="row" /></div>'
};

async function page(options: { realTable?: boolean } = {}) {
  wrapper = mount(FundsPage, {
    attachTo: document.body,
    global: {
      plugins: [ElementPlus, i18n, createPinia()],
      stubs: { RouterLink: true, ...(options.realTable ? {} : { DataTable: dataTableStub }) }
    }
  });
  await flushPromises();
}
async function apply() {
  await wrapper.get('[data-test="apply-withdrawal"]').trigger("click");
  await flushPromises();
}
async function submit(amount = "250.00") {
  await wrapper.get('[data-test="withdrawal-amount"]').setValue(amount);
  await wrapper.get('[data-test="submit-withdrawal"]').trigger("click");
  await flushPromises();
}

describe("creator fund records list states", () => {
  it("hides the empty copy and the pager when loading fails", async () => {
    vi.mocked(api.getFundRecords).mockRejectedValue(new Error("network"));
    await page({ realTable: true });
    expect(wrapper.text()).toContain(enUS.funds.loadFailed);
    expect(wrapper.text()).not.toContain(enUS.funds.empty);
    expect(wrapper.find(".data-table__empty").exists()).toBe(false);
    expect(wrapper.find(".data-table__pager").exists()).toBe(false);
    expect(wrapper.find(".fund-records-pager").exists()).toBe(false);
  });

  it("shows the empty copy without a pager on an empty list", async () => {
    await page({ realTable: true });
    expect(wrapper.text()).toContain(enUS.funds.empty);
    expect(wrapper.find(".data-table__pager").exists()).toBe(false);
    expect(wrapper.find(".fund-records-pager").exists()).toBe(false);
  });
});

describe("creator withdrawal application", () => {
  it("renders foreign-currency history amounts without a CNY symbol", async () => {
    vi.mocked(api.getFundRecords).mockResolvedValue({
      items: [
        {
          id: "p-1",
          type: "PREPAY",
          appliedAt: null,
          applicationNo: "P1",
          amount: "100.00",
          currency: "USD",
          maskedRecipient: null,
          receivedAt: null,
          publicStatus: "PAID",
          sourceId: "1"
        }
      ],
      total: 1,
      page: 1,
      size: 20
    });
    await page();
    expect(wrapper.get('[data-test="fund-record-amount"]').text()).toBe("100.00 USD");
    const card = wrapper.get('[data-test="fund-record-card"]');
    expect(card.text()).toContain("P1");
    expect(card.text()).toContain("100.00 USD");
  });

  it("resets history to the first page when changing page size", async () => {
    await page();
    const table = wrapper.findComponent({ name: "DataTable" });
    table.vm.$emit("update:page", 2);
    await flushPromises();
    expect(api.getFundRecords).toHaveBeenLastCalledWith({ page: 2, size: 20 });
    vi.mocked(api.getFundRecords).mockClear();
    table.vm.$emit("update:size", 50);
    table.vm.$emit("update:page", 1);
    await flushPromises();
    expect(api.getFundRecords).toHaveBeenCalledTimes(1);
    expect(api.getFundRecords).toHaveBeenLastCalledWith({ page: 1, size: 50 });
  });
  it("does not send the button event as previousId on a new application", async () => {
    await page();
    await apply();
    await submit();
    expect(api.submitWithdrawal).toHaveBeenCalledWith({
      amount: "250.00",
      payoutAccountId: "10",
      note: "",
      idempotencyKey: expect.any(String)
    });
  });

  it("prefills the rejected amount and sends its id on resubmission", async () => {
    vi.mocked(api.getWithdrawals).mockResolvedValue({
      items: [
        {
          id: "42",
          applicationNo: "WD42",
          amount: "325.50",
          currency: "CNY",
          status: "REJECTED",
          createdAt: "2026-10-25T00:00:00Z",
          account: null
        }
      ],
      total: 1,
      page: 1,
      size: 50
    });
    await page();
    await wrapper.get('[data-test="resubmit-withdrawal"]').trigger("click");
    await flushPromises();
    expect((wrapper.get('[data-test="withdrawal-amount"]').element as HTMLInputElement).value).toBe(
      "325.50"
    );
    await wrapper.get('[data-test="submit-withdrawal"]').trigger("click");
    await flushPromises();
    expect(api.submitWithdrawal).toHaveBeenCalledWith(
      expect.objectContaining({ amount: "325.50", previousId: "42" })
    );
  });

  it("shows the server block reason and disables a frozen application", async () => {
    vi.mocked(api.getWithdrawalConditions).mockResolvedValue({
      ...conditions,
      withdrawalFrozen: true,
      canApply: false
    });
    await page();
    await apply();
    expect(wrapper.get('[data-test="withdrawal-conditions"]').text()).toContain(
      i18n.global.t("funds.withdrawalFrozen")
    );
    expect(wrapper.get('[data-test="submit-withdrawal"]').attributes("disabled")).toBeDefined();
    expect(api.submitWithdrawal).not.toHaveBeenCalled();
  });

  it("shows a visible error when withdrawal conditions cannot load", async () => {
    vi.mocked(api.getWithdrawalConditions).mockRejectedValue(
      new ApiError({ status: 503, errorCode: "COMMON_INTERNAL_ERROR", traceId: "t60-conditions" })
    );
    await page();
    await apply();
    expect(wrapper.text()).toContain("t60-conditions");
    expect(wrapper.get('[data-test="submit-withdrawal"]').attributes("disabled")).toBeDefined();
  });

  it("reuses the key after a lost response but renews it when the request changes", async () => {
    vi.mocked(api.submitWithdrawal).mockRejectedValue(
      new ApiError({ status: 0, errorCode: "NETWORK_ERROR", traceId: null })
    );
    await page();
    await apply();
    await submit();
    await submit();
    await submit("300.00");
    const calls = vi.mocked(api.submitWithdrawal).mock.calls;
    expect(calls).toHaveLength(3);
    expect(calls[0][0].idempotencyKey).toBe(calls[1][0].idempotencyKey);
    expect(calls[2][0].idempotencyKey).not.toBe(calls[1][0].idempotencyKey);
  });

  it("uploads and sends an inline USDT QR file using a string id", async () => {
    vi.mocked(api.getWithdrawalAccounts).mockResolvedValue([]);
    let finishUpload!: (value: { fileId: string }) => void;
    vi.mocked(uploadQrFile).mockImplementation(
      () =>
        new Promise(resolve => {
          finishUpload = resolve;
        })
    );
    await page();
    await apply();
    const typeSelect = wrapper
      .findAllComponents(ElSelect)
      .find(select => select.attributes("data-test") === "withdrawal-account-type");
    if (!typeSelect) throw new Error("Withdrawal account type selector was not rendered");
    typeSelect.vm.$emit("update:modelValue", "USDT");
    await flushPromises();
    await wrapper.get('[data-test="withdrawal-account"]').setValue("T1234567890");
    await wrapper.get('[data-test="withdrawal-holder"]').setValue("Creator");
    const input = wrapper.get('[data-test="withdrawal-qr"]');
    const file = new File(["png"], "t60-qr.png", { type: "image/png" });
    Object.defineProperty(input.element, "files", { value: [file] });
    await input.trigger("change");
    expect(wrapper.findComponent(ElDialog).props("showClose")).toBe(false);
    expect(wrapper.findComponent(ElDialog).props("closeOnPressEscape")).toBe(false);
    expect(wrapper.get('[data-test="submit-withdrawal"]').attributes("disabled")).toBeDefined();
    finishUpload({ fileId: "9007199254740993" });
    await flushPromises();
    await submit();
    expect(uploadQrFile).toHaveBeenCalledWith(file);
    expect(api.submitWithdrawal).toHaveBeenCalledWith(
      expect.objectContaining({
        account: {
          type: "USDT",
          account: "T1234567890",
          holder: "Creator",
          network: "TRC20",
          qrFileId: "9007199254740993"
        }
      })
    );
  });
});
