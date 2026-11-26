import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { listSettlementLedger } from "@/api/commission/finance";
import { listShops } from "@/api/product/shop";
import DataTable from "@/components/DataTable.vue";
import { i18n } from "@/locales";
import { notifyError } from "@/utils/notify";
import { dates } from "@/views/analytics/board";
import SettlementDetailsPage from "../index.vue";

vi.mock("@/api/commission/finance", () => ({ listSettlementLedger: vi.fn() }));
vi.mock("@/api/product/shop", () => ({ listShops: vi.fn() }));
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

const ledger = {
  settledGoodsAmount: "65.00",
  pendingAmount: "15.00",
  platformFeeAmount: "1.04",
  activity: {
    items: [
      {
        id: "5001",
        occurredAt: "2026-11-24T06:22:17Z",
        tradeType: "支出",
        amount: "-0.39",
        billType: "交易手续费",
        balance: null,
        orderNo: "t87_order",
        creatorName: "演示达人",
        creatorCode: "KOL0001",
        commissionAmount: "13.00"
      }
    ],
    total: "1",
    page: 1,
    size: 20
  }
};

function mountPage() {
  return mount(SettlementDetailsPage, { global: { plugins: [i18n, ElementPlus] } });
}

describe("settlement ledger", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    i18n.global.locale.value = "zh-CN";
    vi.clearAllMocks();
    vi.mocked(listShops).mockResolvedValue([]);
    vi.mocked(listSettlementLedger).mockResolvedValue(ledger);
  });

  it("renders the journal and cumulative settlement metrics", async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(
      wrapper
        .getComponent(DataTable)
        .props("columns")
        .map(column => column.label)
    ).toEqual([
      "时间",
      "收支类型",
      "收入（元）",
      "账单类型",
      "余额",
      "订单号",
      "达人名/ID",
      "佣金金额"
    ]);
    expect(wrapper.get('[data-test="wallet-settled"]').text()).toBe("¥65.00");
    expect(wrapper.get('[data-test="wallet-pending"]').text()).toBe("¥15.00");
    expect(wrapper.get('[data-test="wallet-platform-fee"]').text()).toBe("¥1.04");
    expect(wrapper.text()).toContain("KOL0001");
    expect(wrapper.text()).toContain("¥13.00");
    expect(wrapper.text()).toContain("交易手续费");
    expect(wrapper.text()).toContain("-¥0.39");
    expect(wrapper.text()).not.toContain("提现记录");
    wrapper.unmount();
  });

  it("defaults to seven days and resets pagination when a preset is selected", async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(listSettlementLedger).toHaveBeenLastCalledWith(
      expect.objectContaining({ ...dates(7), page: 1 })
    );
    wrapper.getComponent(DataTable).vm.$emit("update:page", 2);
    await flushPromises();
    await wrapper.get('[data-test="period-30"]').trigger("click");
    await flushPromises();
    expect(listSettlementLedger).toHaveBeenLastCalledWith(
      expect.objectContaining({ ...dates(30), page: 1 })
    );
    wrapper.unmount();
  });

  it("retains a confirmed zero and allows retry after a failed read", async () => {
    vi.mocked(listSettlementLedger).mockRejectedValueOnce(new Error("network"));
    const wrapper = mountPage();
    await flushPromises();
    expect(notifyError).toHaveBeenCalledOnce();
    expect(wrapper.getComponent(DataTable).props("failed")).toBe(true);
    vi.mocked(listSettlementLedger).mockResolvedValue({
      ...ledger,
      settledGoodsAmount: "0.00",
      pendingAmount: "12.23"
    });
    wrapper.getComponent(DataTable).vm.$emit("retry");
    await flushPromises();
    expect(wrapper.get('[data-test="wallet-settled"]').text()).toBe("¥0.00");
    expect(wrapper.get('[data-test="wallet-pending"]').text()).toBe("¥12.23");
    wrapper.unmount();
  });
});
