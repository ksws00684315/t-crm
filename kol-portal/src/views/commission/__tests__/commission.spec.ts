import type * as FinanceUtils from "@/utils/finance";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import ElementPlus from "element-plus";
import * as api from "@/api/commission/finance";
import { createFinanceDemo } from "@/api/commission/finance-engine";
import { i18n } from "@/locales";
import Page from "../index.vue";
import ApplyDialog from "../ApplyDialog.vue";
vi.mock("@/api/commission/finance", () => ({
  getMyIncome: vi.fn(),
  getWithdrawalConditions: vi.fn(),
  listMyWithdrawals: vi.fn(),
  getMyWithdrawal: vi.fn(),
  cancelWithdrawal: vi.fn(),
  downloadWithdrawalVoucher: vi.fn(),
  getMyAccounts: vi.fn(),
  applyWithdrawal: vi.fn(),
  uploadRecipientQr: vi.fn()
}));
vi.mock("@/utils/finance", async original => ({
  ...(await original<typeof FinanceUtils>()),
  financePreview: true
}));
beforeEach(() => {
  vi.clearAllMocks();
  i18n.global.locale.value = "en-US";
  const demo = createFinanceDemo();
  vi.mocked(api.getMyIncome).mockResolvedValue(demo.incomeDetail("KOL", "data", "102301", {}));
  vi.mocked(api.getWithdrawalConditions).mockResolvedValue(demo.getConfig("KOL", "data"));
  vi.mocked(api.listMyWithdrawals).mockResolvedValue(demo.listWithdrawals("KOL", "data", {}));
  vi.mocked(api.getMyAccounts).mockResolvedValue(demo.accounts());
});
describe("creator commissions", () => {
  it("renders separate commission balances and exposes only the current creator", async () => {
    const wrapper = mount(Page, {
      global: { plugins: [ElementPlus, i18n], stubs: { ApplyDialog: true } }
    });
    await flushPromises();
    expect(wrapper.get('[data-test="metric-estimated"]').text()).toContain("2600.00");
    expect(wrapper.get('[data-test="metric-available"]').text()).toContain("600.00");
    expect(wrapper.findAll('[data-test="creator-ledger-record"]')).toHaveLength(4);
    expect(wrapper.get('[data-test="creator-commission-page"]').text()).not.toContain("t1023_Emma");
    expect(api.getMyIncome).toHaveBeenCalledWith({});
    expect(api.listMyWithdrawals).toHaveBeenCalledWith(1, 20);
    wrapper.unmount();
  });
  it("updates translated metrics immediately and clears stale results on failure", async () => {
    const wrapper = mount(Page, {
      global: { plugins: [ElementPlus, i18n], stubs: { ApplyDialog: true } }
    });
    await flushPromises();
    i18n.global.locale.value = "zh-CN";
    await flushPromises();
    expect(wrapper.get('[data-test="finance-metrics"]').text()).toContain("冻结佣金");
    vi.mocked(api.getMyIncome).mockRejectedValueOnce(new Error("network"));
    await wrapper.get('[data-test="creator-refresh"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="finance-metrics"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="creator-retry"]').exists()).toBe(true);
    wrapper.unmount();
  });
  it("validates threshold before submitting and sends a recipient snapshot with an idempotency key", async () => {
    const wrapper = mount(ApplyDialog, {
      props: { modelValue: false, minimum: "200.00", available: "600.00", previous: null },
      attachTo: document.body,
      global: { plugins: [ElementPlus, i18n] }
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    const amount = document.querySelector<HTMLInputElement>('[data-test="apply-amount"]')!;
    amount.value = "199.99";
    amount.dispatchEvent(new Event("input", { bubbles: true }));
    document.querySelector<HTMLButtonElement>('[data-test="apply-submit"]')!.click();
    await flushPromises();
    expect(api.applyWithdrawal).not.toHaveBeenCalled();
    amount.value = "200.00";
    amount.dispatchEvent(new Event("input", { bubbles: true }));
    document.querySelector<HTMLButtonElement>('[data-test="apply-submit"]')!.click();
    await flushPromises();
    expect(api.applyWithdrawal).toHaveBeenCalledWith(
      expect.objectContaining({
        amount: "200.00",
        account: expect.objectContaining({ account: "t1023_creator@example.com" }),
        idempotencyKey: expect.any(String)
      })
    );
    wrapper.unmount();
  });
  it("presents submission failure and retains the form for correction", async () => {
    vi.mocked(api.applyWithdrawal).mockRejectedValue(new Error("network"));
    const wrapper = mount(ApplyDialog, {
      props: { modelValue: false, minimum: "200.00", available: "600.00", previous: null },
      attachTo: document.body,
      global: { plugins: [ElementPlus, i18n] }
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    const amount = document.querySelector<HTMLInputElement>('[data-test="apply-amount"]')!;
    amount.value = "200";
    amount.dispatchEvent(new Event("input", { bubbles: true }));
    document.querySelector<HTMLButtonElement>('[data-test="apply-submit"]')!.click();
    await flushPromises();
    expect(document.querySelector('[data-test="apply-error"]')?.textContent).not.toBe("");
    expect(wrapper.emitted("saved")).toBeUndefined();
    wrapper.unmount();
  });
});
