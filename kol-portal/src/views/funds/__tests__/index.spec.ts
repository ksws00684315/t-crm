import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElDialog, ElMessageBox, ElSelect } from "element-plus";
import { createPinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/creator";
import { uploadQrFile } from "@/api/payout-account";
import { enUS, i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import FundsPage from "../index.vue";

const nav = vi.hoisted(() => ({
  route: { query: {} as Record<string, string> },
  push: vi.fn(),
  replace: vi.fn()
}));
vi.mock("vue-router", () => ({
  useRoute: () => nav.route,
  useRouter: () => ({ push: nav.push, replace: nav.replace })
}));
vi.mock("@/api/creator", () => ({
  getFundSummary: vi.fn(),
  getFundRecords: vi.fn(),
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
  nav.route.query = {};
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

function record(overrides: Partial<api.FundRecord> = {}): api.FundRecord {
  return {
    id: "42",
    type: "COMMISSION",
    appliedAt: "2026-10-25T00:00:00Z",
    applicationNo: "WD42",
    amount: "100.00",
    currency: "CNY",
    maskedRecipient: "PayPal · p***@example.test",
    receivedAt: null,
    publicStatus: "PENDING_REVIEW",
    sourceId: "42",
    ...overrides
  };
}
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
    vi.mocked(api.getFundRecords).mockResolvedValue({
      items: [record({ id: "42", publicStatus: "REJECTED", amount: "325.50" })],
      total: 1,
      page: 1,
      size: 20
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

describe("commission progress (task 92 section 4.1)", () => {
  function summary(overrides: Partial<api.FundSummary>): api.FundSummary {
    return {
      totalIncomeCny: "0.00",
      commissionIncomeCny: "0.00",
      estimatedCommissionCny: "1000.00",
      totalCommissionCny: "0.00",
      withdrawnCny: "200.00",
      withdrawingCny: "100.00",
      frozenCny: "300.00",
      receivedPrepayCny: "0.00",
      availableCny: "400.00",
      prepayDeductionCny: "0.00",
      prepayOutstandingCny: "0.00",
      asOf: "2026-10-26T00:00:00Z",
      ...overrides
    };
  }
  const step = (key: string) => wrapper.get(`[data-test="step-${key}-amount"]`).text();

  it("shows frozen, withdrawable, withdrawing and withdrawn in order with the workspace values", async () => {
    vi.mocked(api.getFundSummary).mockResolvedValue({ fundSummary: summary({}) });
    await page();
    expect(wrapper.findAll('[data-test^="step-"]:not([data-test$="-amount"])')).toHaveLength(4);
    expect(step("frozen")).toBe("¥300.00");
    expect(step("withdrawable")).toBe("¥400.00");
    expect(step("withdrawing")).toBe("¥100.00");
    expect(step("withdrawn")).toBe("¥200.00");
    expect(wrapper.text()).toContain(enUS.funds.stepFrozenCaption);
    expect(wrapper.text()).toContain(enUS.funds.stepWithdrawableCaption);
    // The totals that only the workspace shows are not repeated here.
    expect(wrapper.text()).not.toContain("Estimated");
  });

  it("shows a negative withdrawable amount in red", async () => {
    vi.mocked(api.getFundSummary).mockResolvedValue({
      fundSummary: summary({ availableCny: "-25.50" })
    });
    await page();
    expect(step("withdrawable")).toBe("-¥25.50");
    expect(wrapper.get('[data-test="step-withdrawable-amount"]').classes()).toContain("negative");
  });

  it("explains the repaid and outstanding advance, and hides the line when both are zero", async () => {
    await page();
    expect(wrapper.find('[data-test="prepay-note"]').exists()).toBe(false);
    wrapper.unmount();
    vi.mocked(api.getFundSummary).mockResolvedValue({
      fundSummary: summary({ prepayDeductionCny: "200.00", prepayOutstandingCny: "300.00" })
    });
    await page();
    expect(wrapper.get('[data-test="prepay-note"]').text()).toBe(
      "Advances repaid from commission: ¥200.00. Outstanding: ¥300.00."
    );
  });

  it("shows the note when only the outstanding part is not zero", async () => {
    vi.mocked(api.getFundSummary).mockResolvedValue({
      fundSummary: summary({ prepayOutstandingCny: "300.00" })
    });
    await page();
    expect(wrapper.get('[data-test="prepay-note"]').text()).toContain("¥0.00");
  });
});

describe("withdrawal actions and the ?apply=1 link (task 92 sections 4.2, 4.3)", () => {
  it("opens the application dialog once for ?apply=1 and removes the parameter", async () => {
    nav.route.query = { apply: "1", other: "keep" };
    await page();
    expect(api.getWithdrawalConditions).toHaveBeenCalledTimes(1);
    expect(wrapper.findComponent(ElDialog).props("modelValue")).toBe(true);
    expect(nav.replace).toHaveBeenCalledWith({ query: { other: "keep" } });
  });

  it("does not open the dialog without the parameter", async () => {
    await page();
    expect(api.getWithdrawalConditions).not.toHaveBeenCalled();
    expect(nav.replace).not.toHaveBeenCalled();
  });

  it("puts the apply and manage-accounts actions in one card, the second leading to the accounts", async () => {
    await page();
    const card = wrapper.get('[data-test="action-card"]');
    expect(card.findAll("button")).toHaveLength(2);
    expect(card.get('[data-test="apply-withdrawal"]').text()).toBe(enUS.funds.apply);
    await card.get('[data-test="manage-accounts"]').trigger("click");
    expect(nav.push).toHaveBeenCalledWith("/payout-accounts");
    // No second apply button or accounts link remains elsewhere on the page.
    expect(wrapper.findAll('[data-test="apply-withdrawal"]')).toHaveLength(1);
  });

  it("requests the records from the server page by page and not the old withdrawal list", async () => {
    await page();
    expect(api.getFundRecords).toHaveBeenCalledTimes(1);
    expect(api.getFundRecords).toHaveBeenCalledWith({ page: 1, size: 20 });
    expect("getWithdrawals" in api).toBe(false);
  });

  const allStatuses = [
    ["PENDING_REVIEW", enUS.funds.status.PENDING_REVIEW],
    ["PENDING_PAYMENT", enUS.funds.status.PENDING_PAYMENT],
    ["PAID", enUS.funds.status.PAID],
    ["REJECTED", enUS.funds.status.REJECTED],
    ["WITHDRAWN", enUS.funds.status.WITHDRAWN]
  ] as const;

  async function pageWithEveryStatus() {
    vi.mocked(api.getFundRecords).mockResolvedValue({
      items: [
        ...allStatuses.map(([status], index) =>
          record({ id: String(10 + index), applicationNo: `WD${index}`, publicStatus: status })
        ),
        record({
          id: "p-1",
          type: "PREPAY",
          applicationNo: "PP1",
          publicStatus: "PAID",
          receivedAt: "2026-10-26T00:00:00Z"
        })
      ],
      total: 6,
      page: 1,
      size: 20
    });
    await page({ realTable: true });
    return wrapper.get(".records-table");
  }

  it("lists every status and a paid advance, with the owner's status colours", async () => {
    const table = await pageWithEveryStatus();
    const rows = table.findAll("tbody tr");
    expect(rows).toHaveLength(6);
    // Element Plus also renders each column once, hidden, to measure it: only count the body.
    const statusTags = table.findAll('tbody [data-test="fund-record-status"]');
    const tags = statusTags.map(tag => tag.text());
    expect(tags).toEqual([...allStatuses.map(([, label]) => label), enUS.funds.status.PAID]);
    const kinds = statusTags.map(tag =>
      tag
        .get(".el-tag")
        .classes()
        .find(name => name.startsWith("el-tag--") && name !== "el-tag--light")
    );
    // waiting, waiting, success, danger, info, success
    expect(kinds).toEqual([
      "el-tag--warning",
      "el-tag--warning",
      "el-tag--success",
      "el-tag--danger",
      "el-tag--info",
      "el-tag--success"
    ]);
    expect(table.text()).toContain(enUS.funds.prepay);
    expect(table.text()).toContain(enUS.funds.withdrawal);
  });

  it("offers cancel only while under review and reapply only after a rejection", async () => {
    const table = await pageWithEveryStatus();
    expect(table.findAll('[data-test="cancel-withdrawal"]')).toHaveLength(1);
    expect(table.findAll('[data-test="resubmit-withdrawal"]')).toHaveLength(1);
    const rows = table.findAll("tbody tr");
    // The review row (first) has the cancel action; the rejected row (fourth) the reapply one.
    expect(rows[0].find('[data-test="cancel-withdrawal"]').exists()).toBe(true);
    expect(rows[3].find('[data-test="resubmit-withdrawal"]').exists()).toBe(true);
    for (const index of [1, 2, 4, 5]) {
      expect(rows[index].find('[data-test="row-actions"]').exists()).toBe(false);
    }
  });

  it("withdraws a request under review after the confirmation and reloads", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(api.withdrawWithdrawal).mockResolvedValue(undefined);
    const table = await pageWithEveryStatus();
    vi.mocked(api.getFundRecords).mockClear();
    await table.get('[data-test="cancel-withdrawal"]').trigger("click");
    await flushPromises();
    expect(api.withdrawWithdrawal).toHaveBeenCalledWith("10");
    expect(api.getFundRecords).toHaveBeenCalledTimes(1);
  });

  it("leaves the request untouched when the confirmation is declined", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    const table = await pageWithEveryStatus();
    await table.get('[data-test="cancel-withdrawal"]').trigger("click");
    await flushPromises();
    expect(api.withdrawWithdrawal).not.toHaveBeenCalled();
  });

  it("shows the status and the actions on the phone cards too", async () => {
    await pageWithEveryStatus();
    const cards = wrapper.findAll('[data-test="fund-record-card"]');
    expect(cards).toHaveLength(6);
    expect(cards[0].text()).toContain(enUS.funds.status.PENDING_REVIEW);
    expect(cards[0].find('[data-test="cancel-withdrawal"]').exists()).toBe(true);
    expect(cards[3].find('[data-test="resubmit-withdrawal"]').exists()).toBe(true);
    expect(cards[2].find('[data-test="row-actions"]').exists()).toBe(false);
  });
});
