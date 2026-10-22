import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/prepay";
import { i18n } from "@/locales";
import Page from "../index.vue";
vi.mock("@/api/prepay", () => ({
  listPrepays: vi.fn(),
  getPrepay: vi.fn(),
  prepayLedger: vi.fn(),
  downloadPrepayVoucher: vi.fn()
}));
const result: api.PrepayResult = {
  id: "9007199254740993",
  prepayNo: "PP-test",
  status: "PAID",
  payAmountCny: "100.01",
  payCurrency: "CNY",
  payTime: "2026-10-22T01:00:00Z",
  payMethod: "PayPal",
  voucherFileId: "7",
  deductFromCommission: true,
  walletBalanceCny: "-100.01",
  deduction: { postedAmountCny: "100.01", recoveredAmountCny: "20.00", remainingAmountCny: "80.01" }
};
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.listPrepays).mockResolvedValue({ items: [result], total: "1", page: 1, size: 20 });
});
const open = async () => {
  const wrapper = mount(Page, { global: { plugins: [ElementPlus, i18n] } });
  await flushPromises();
  return wrapper;
};
describe("creator paid prepay projection", () => {
  it("shows the paid amount and negative wallet with no administrative controls", async () => {
    const wrapper = await open();
    expect(wrapper.get('[data-test="prepay-wallet"]').text()).toBe("-100.01 CNY");
    expect(wrapper.text()).toContain("100.01 CNY");
    expect(wrapper.text()).toContain("80.01 CNY");
    expect(wrapper.find('[data-test="prepay-approve"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="prepay-pay-submit"]').exists()).toBe(false);
    wrapper.unmount();
  });
  it("loads ledger separately and preserves signed entries and balances", async () => {
    vi.mocked(api.prepayLedger).mockResolvedValue({
      items: [
        {
          entryId: "9",
          prepayId: result.id,
          prepayNo: "PP-test",
          entryType: "PREPAY_DEDUCT",
          amountCny: "20.00",
          occurredAt: result.payTime,
          balanceAfterCny: "-80.01"
        }
      ],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = await open();
    wrapper.getComponent({ name: "ElRadioGroup" }).vm.$emit("update:modelValue", "ledger");
    await flushPromises();
    expect(api.prepayLedger).toHaveBeenCalledWith(1, 20);
    expect(wrapper.get('[data-test="prepay-ledger-row"]').text()).toContain("-80.01 CNY");
    expect(wrapper.find('[data-test="prepay-result-row"]').exists()).toBe(false);
    wrapper.unmount();
  });
  it("clears stale results and presents failed refresh feedback", async () => {
    const wrapper = await open();
    vi.mocked(api.listPrepays).mockRejectedValueOnce(new Error("network"));
    await wrapper.get('[data-test="prepay-refresh"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="prepay-result-row"]').exists()).toBe(false);
    expect(wrapper.get('[data-test="prepay-error"]').text()).not.toBe("");
    wrapper.unmount();
  });
  it("does not describe missing deduction balance as an unchanged wallet", async () => {
    vi.mocked(api.prepayLedger).mockResolvedValue({
      items: [
        {
          entryId: "9",
          prepayId: result.id,
          prepayNo: "PP-test",
          entryType: "PREPAY_DEDUCT",
          amountCny: "20.00",
          occurredAt: result.payTime
        }
      ],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = await open();
    wrapper.getComponent({ name: "ElRadioGroup" }).vm.$emit("update:modelValue", "ledger");
    await flushPromises();
    const entry = wrapper.get('[data-test="prepay-ledger-row"]');
    expect(entry.text()).toContain("20.00 CNY");
    expect(entry.text()).not.toContain(i18n.global.t("prepay.walletUnchanged"));
    wrapper.unmount();
  });
});
