import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/commission/finance";
import { createFinanceDemo } from "../../../../../preview/commission/finance-engine";
import type { Page, Withdrawal, WithdrawalOrder } from "@/api/commission/finance-types";
import { i18n } from "@/locales";
import { notifyError } from "@/utils/notify";
import PaymentDialog from "../PaymentDialog.vue";
import WithdrawalDrawer from "../WithdrawalDrawer.vue";
import IncomeDrawer from "@/views/kol/income/IncomeDrawer.vue";
vi.mock("@/api/commission/finance", () => ({
  getIncome: vi.fn(),
  getWithdrawal: vi.fn(),
  getWithdrawalOrders: vi.fn(),
  payWithdrawal: vi.fn(),
  uploadWithdrawalVoucher: vi.fn(),
  downloadWithdrawalVoucher: vi.fn()
}));
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));
beforeEach(() => {
  vi.clearAllMocks();
  i18n.global.locale.value = "zh-CN";
  const demo = createFinanceDemo();
  vi.mocked(api.getWithdrawalOrders).mockImplementation(async (id, query) =>
    demo.withdrawalOrders("SUPER_ADMIN", "data", id, query)
  );
  vi.mocked(api.getWithdrawal).mockImplementation(async id =>
    demo.withdrawal("SUPER_ADMIN", "data", id)
  );
  vi.mocked(api.getIncome).mockResolvedValue(
    demo.incomeDetail("SUPER_ADMIN", "data", "102301", {})
  );
  vi.mocked(api.uploadWithdrawalVoucher).mockResolvedValue({ fileId: "t1023_voucher_test" });
});
const global = { plugins: [ElementPlus, i18n] };
describe("financial drawers and payment registration", () => {
  it("loads an income drawer with separate balances and only read-only prepayment records", async () => {
    const wrapper = mount(IncomeDrawer, {
      props: { modelValue: false, id: "102301", query: {} },
      global,
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    expect(document.querySelector('[data-test="income-detail"]')?.textContent).toContain(
      "申请占用"
    );
    expect(document.querySelector('[data-test="income-detail"]')?.textContent).toContain("600.00");
    expect(api.getIncome).toHaveBeenCalledWith("102301", {});
    wrapper.unmount();
  });
  it("reports an income error without keeping stale details", async () => {
    vi.mocked(api.getIncome).mockRejectedValue(new Error("network"));
    const wrapper = mount(IncomeDrawer, {
      props: { modelValue: false, id: "102301", query: {} },
      global,
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    expect(notifyError).toHaveBeenCalled();
    expect(document.querySelector('[data-test="income-retry"]')).not.toBeNull();
    wrapper.unmount();
  });
  it("shows the request summary and paginates ten distinct associated orders", async () => {
    const wrapper = mount(WithdrawalDrawer, {
      props: { modelValue: false, id: "102371" },
      global,
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    const text = document.querySelector('[data-test="withdrawal-detail"]')?.textContent;
    expect(text).toContain("t1023_K001");
    expect(text).toContain("200.00 CNY");
    expect(text).toContain("关联订单（11笔）");
    expect(text).toContain("t1023_WD001");
    expect(text).toContain("t1023_WD010");
    expect(text).toContain("剩余快照为本次申请占用后，该笔订单当时剩余的可提现金额。");
    expect(text).toContain("运营备注");
    expect(text).toContain("财务备注");
    expect(text).not.toContain("占用合计");
    expect(api.getWithdrawalOrders).toHaveBeenCalledWith("102371", { page: 1, size: 10 });
    expect(text).not.toContain("t1023_WD011");
    document
      .querySelector<HTMLButtonElement>('[data-test="withdrawal-orders-pager"] .btn-next')!
      .click();
    await flushPromises();
    expect(api.getWithdrawalOrders).toHaveBeenLastCalledWith("102371", { page: 2, size: 10 });
    expect(document.querySelector('[data-test="withdrawal-detail"]')?.textContent).toContain(
      "t1023_WD011"
    );
    wrapper.unmount();
  });
  it("reports a withdrawal-detail failure", async () => {
    vi.mocked(api.getWithdrawal).mockRejectedValue(new Error("network"));
    const wrapper = mount(WithdrawalDrawer, {
      props: { modelValue: false, id: "102374" },
      global,
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    expect(notifyError).toHaveBeenCalled();
    wrapper.unmount();
  });
  it("retries a failed order page without losing the application information", async () => {
    vi.mocked(api.getWithdrawalOrders).mockRejectedValueOnce(new Error("orders failed"));
    const wrapper = mount(WithdrawalDrawer, {
      props: { modelValue: true, id: "102371" },
      global,
      attachTo: document.body
    });
    await flushPromises();
    expect(document.querySelector(".withdrawal-info")?.textContent).toContain("t1023_W001");
    document.querySelector<HTMLButtonElement>('[data-test="withdrawal-orders-retry"]')!.click();
    await flushPromises();
    expect(api.getWithdrawalOrders).toHaveBeenCalledTimes(2);
    expect(document.querySelector('[data-test="withdrawal-orders-retry"]')).toBeNull();
    expect(document.querySelector('[data-test="withdrawal-detail"]')?.textContent).toContain(
      "t1023_WD010"
    );
    wrapper.unmount();
  });
  it("ignores late detail and order responses after switching applications", async () => {
    const demo = createFinanceDemo();
    let resolveDetail!: (value: Withdrawal) => void;
    let resolveOrders!: (value: Page<WithdrawalOrder>) => void;
    vi.mocked(api.getWithdrawal).mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveDetail = resolve;
        })
    );
    vi.mocked(api.getWithdrawalOrders).mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveOrders = resolve;
        })
    );
    const wrapper = mount(WithdrawalDrawer, {
      props: { modelValue: true, id: "102371" },
      global,
      attachTo: document.body
    });
    await wrapper.setProps({ id: "102375" });
    await flushPromises();
    resolveDetail(demo.withdrawal("SUPER_ADMIN", "data", "102371"));
    resolveOrders(demo.withdrawalOrders("SUPER_ADMIN", "data", "102371", { page: 1, size: 10 }));
    await flushPromises();
    const text = document.querySelector('[data-test="withdrawal-detail"]')?.textContent;
    expect(text).toContain("t1023_W005");
    expect(text).toContain("700.00 CNY");
    expect(text).toContain("关联订单（1笔）");
    expect(text).not.toContain("t1023_W001");
    wrapper.unmount();
  });
  it("blocks incomplete payment registration and inconsistent CNY amounts", async () => {
    const row = createFinanceDemo().withdrawal("SUPER_ADMIN", "data", "102373");
    const wrapper = mount(PaymentDialog, {
      props: { modelValue: false, withdrawal: row },
      global,
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    document.querySelector<HTMLButtonElement>('[data-test="pay-submit"]')!.click();
    await flushPromises();
    expect(api.payWithdrawal).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it("submits an external payment and its uploaded voucher, with an idempotency key", async () => {
    const row = createFinanceDemo().withdrawal("SUPER_ADMIN", "data", "102373");
    const wrapper = mount(PaymentDialog, {
      props: { modelValue: false, withdrawal: row },
      global,
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    const set = (test: string, value: string) => {
      const input = document.querySelector<HTMLInputElement>(`[data-test="${test}"]`)!;
      input.value = value;
      input.dispatchEvent(new Event("input", { bubbles: true }));
    };
    set("pay-actual-amount", "28.57");
    set("pay-conversion", "t1023_manual_rate");
    const input = document.querySelector<HTMLInputElement>('[data-test="pay-voucher"]')!;
    Object.defineProperty(input, "files", {
      configurable: true,
      value: [new File(["demo"], "t1023.png", { type: "image/png" })]
    });
    input.dispatchEvent(new Event("change", { bubbles: true }));
    await flushPromises();
    document.querySelector<HTMLButtonElement>('[data-test="pay-submit"]')!.click();
    await flushPromises();
    expect(api.payWithdrawal).toHaveBeenCalledWith(
      "102373",
      expect.objectContaining({
        actualAmount: "28.57",
        actualCurrency: "USD",
        amountCny: "200.00",
        voucher: "t1023_voucher_test"
      }),
      expect.any(String)
    );
    expect(wrapper.emitted("saved")).toHaveLength(1);
    wrapper.unmount();
  });
});
