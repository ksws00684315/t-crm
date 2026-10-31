import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/prepay";
import DataTable from "@/components/DataTable.vue";
import CopyText from "@/components/CopyText.vue";
import RowActions from "@/components/RowActions.vue";
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
enableAutoUnmount(afterEach);
beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  i18n.global.locale.value = "en-US";
  vi.mocked(api.listPrepays).mockResolvedValue({ items: [result], total: "1", page: 1, size: 20 });
});
const open = async () => {
  const wrapper = mount(Page, { global: { plugins: [ElementPlus, i18n] } });
  await flushPromises();
  return wrapper;
};
describe("creator paid prepay projection", () => {
  it("puts the tabs in the shared list toolbar and uses one shared pager", async () => {
    const wrapper = await open();
    const table = wrapper.getComponent(DataTable);
    expect(table.props("tableId")).toBe("kol-prepay-payments");
    expect(table.props("rowKey")).toBe("id");
    expect(table.find('.data-table__toolbar [data-test="prepay-tabs"]').exists()).toBe(true);
    expect(table.findAllComponents({ name: "ElPagination" })).toHaveLength(1);
    expect(table.find(".prepay-record").exists()).toBe(false);
    expect(wrapper.find(".prepay-mobile .prepay-record").exists()).toBe(true);
    expect(
      table.findAllComponents(CopyText).some(copy => copy.props("value") === result.prepayNo)
    ).toBe(true);
    expect(
      table
        .findAllComponents(RowActions)
        .find(actions => actions.props("actions").length)!
        .props("actions")
        .map(action => action.key)
    ).toEqual(["view", "voucher"]);
    const amountColumns = table
      .findAllComponents({ name: "ElTableColumn" })
      .filter(column => column.props("className") === "tcrm-amount");
    expect(amountColumns).toHaveLength(4);
    expect(amountColumns.every(column => column.props("align") === "center")).toBe(true);
    wrapper.unmount();
  });
  it("shows the paid amount and negative wallet with no administrative controls", async () => {
    const wrapper = await open();
    expect(wrapper.getComponent(DataTable).text()).toContain("-100.01 CNY");
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
    const table = wrapper.getComponent(DataTable);
    expect(table.props("tableId")).toBe("kol-prepay-ledger");
    expect(table.props("rowKey")).toBe("entryId");
    expect(table.text()).toContain("-80.01 CNY");
    expect(table.findAllComponents(CopyText).some(copy => copy.props("value") === "PP-test")).toBe(
      true
    );
    wrapper.unmount();
  });
  it("clears stale results and presents failed refresh feedback", async () => {
    const wrapper = await open();
    vi.mocked(api.listPrepays).mockRejectedValueOnce(new Error("network"));
    wrapper.getComponent(DataTable).vm.$emit("refresh");
    await flushPromises();
    expect(wrapper.getComponent(DataTable).props("rows")).toEqual([]);
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
    const entry = wrapper.getComponent(DataTable);
    expect(entry.text()).toContain("20.00 CNY");
    expect(entry.text()).not.toContain(i18n.global.t("prepay.walletUnchanged"));
    wrapper.unmount();
  });
  it("uses shared pagination for page and size changes and resets the page on tab switch", async () => {
    vi.mocked(api.listPrepays).mockResolvedValue({
      items: [result],
      total: "81",
      page: 1,
      size: 20
    });
    vi.mocked(api.prepayLedger).mockResolvedValue({ items: [], total: "0", page: 1, size: 20 });
    const wrapper = await open();
    const table = wrapper.getComponent(DataTable);
    table.vm.$emit("update:page", 3);
    await flushPromises();
    expect(api.listPrepays).toHaveBeenLastCalledWith(3, 20);
    const calls = vi.mocked(api.listPrepays).mock.calls.length;
    table.getComponent({ name: "ElPagination" }).vm.$emit("size-change", 50);
    await flushPromises();
    expect(api.listPrepays).toHaveBeenCalledTimes(calls + 1);
    expect(api.listPrepays).toHaveBeenLastCalledWith(1, 50);
    wrapper.getComponent({ name: "ElRadioGroup" }).vm.$emit("update:modelValue", "ledger");
    await flushPromises();
    expect(api.prepayLedger).toHaveBeenLastCalledWith(1, 20);
    wrapper.getComponent({ name: "ElRadioGroup" }).vm.$emit("update:modelValue", "payments");
    await flushPromises();
    expect(api.listPrepays).toHaveBeenLastCalledWith(1, 50);
    wrapper.unmount();
  });
  it.each(["en-US", "zh-CN"] as const)(
    "formats payment and ledger instants in %s",
    async locale => {
      i18n.global.locale.value = locale;
      vi.mocked(api.prepayLedger).mockResolvedValue({
        items: [
          {
            entryId: "1",
            prepayId: result.id,
            prepayNo: result.prepayNo,
            entryType: "PREPAY_PAID",
            amountCny: "100.01",
            occurredAt: result.payTime
          }
        ],
        total: "1",
        page: 1,
        size: 20
      });
      const expected = new Intl.DateTimeFormat(locale, {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false
      }).format(new Date(result.payTime));
      const wrapper = await open();
      expect(wrapper.getComponent(DataTable).text()).toContain(expected);
      wrapper.getComponent({ name: "ElRadioGroup" }).vm.$emit("update:modelValue", "ledger");
      await flushPromises();
      expect(wrapper.getComponent(DataTable).text()).toContain(expected);
      expect(wrapper.getComponent(DataTable).text()).toContain(
        i18n.global.t("prepay.walletUnchanged")
      );
      wrapper.unmount();
    }
  );
  it("copies the full business number and opens paid details through RowActions", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    vi.mocked(api.getPrepay).mockResolvedValue(result);
    const wrapper = await open();
    await wrapper.get('.el-table__body [data-test="copy"]').trigger("click");
    await flushPromises();
    expect(writeText).toHaveBeenCalledWith(result.prepayNo);
    await wrapper.get('.el-table__body [data-test="prepay-result-detail"]').trigger("click");
    await flushPromises();
    expect(api.getPrepay).toHaveBeenCalledWith(result.id);
    expect(wrapper.get('[data-test="prepay-paid-detail"]').text()).toContain(result.prepayNo);
    wrapper.unmount();
  });
  it("downloads the voucher through RowActions without converting string IDs", async () => {
    const create = vi.fn().mockReturnValue("blob:t47_voucher");
    const revoke = vi.fn();
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    vi.mocked(api.downloadPrepayVoucher).mockResolvedValue({
      blob: new Blob(["t47_"], { type: "application/pdf" })
    });
    const wrapper = await open();
    try {
      await wrapper.get('.el-table__body [data-test="prepay-voucher-download"]').trigger("click");
      await flushPromises();
      expect(api.downloadPrepayVoucher).toHaveBeenCalledWith(result.voucherFileId);
      expect(create).toHaveBeenCalledOnce();
      expect(click).toHaveBeenCalledOnce();
      await new Promise(resolve => setTimeout(resolve, 1));
      expect(revoke).toHaveBeenCalledWith("blob:t47_voucher");
    } finally {
      click.mockRestore();
      vi.unstubAllGlobals();
      wrapper.unmount();
    }
  });
  it("shows the shared empty state and permits a successful refresh", async () => {
    vi.mocked(api.listPrepays).mockResolvedValueOnce({ items: [], total: "0", page: 1, size: 20 });
    const wrapper = await open();
    expect(wrapper.getComponent(DataTable).text()).toContain(i18n.global.t("prepay.empty"));
    wrapper.getComponent(DataTable).vm.$emit("refresh");
    await flushPromises();
    expect(wrapper.getComponent(DataTable).props("rows")).toEqual([result]);
    wrapper.unmount();
  });
});
