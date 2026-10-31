import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as financeApi from "@/api/commission/finance";
import type { Settlement } from "@/api/commission/finance-types";
import { i18n } from "@/locales";
import SettlementDetailsPage from "../index.vue";

vi.mock("@/api/commission/finance", () => ({ listSettlements: vi.fn() }));

const frozenRow: Settlement = {
  id: "5001",
  orderNo: "791029213203",
  channel: "WEIDIAN",
  shop: "Shop A",
  kolNames: ["tsim 达人"],
  payment: "65.00",
  refund: "0.00",
  weidianWithdrawable: null,
  commission: "6.50",
  rates: ["0.1000"],
  status: "FROZEN",
  businessAt: "2026-10-20T08:00:00Z",
  lines: []
};

const postedRow: Settlement = {
  ...frozenRow,
  id: "5002",
  orderNo: "791029213204",
  weidianWithdrawable: "64.70",
  status: "POSTED"
};

const unverifiedRow: Settlement = {
  ...frozenRow,
  id: "5003",
  orderNo: "791029213205",
  commission: null,
  rates: [],
  status: "UNVERIFIED"
};

describe("settlement details page", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.mocked(financeApi.listSettlements).mockResolvedValue({
      items: [frozenRow, postedRow, unverifiedRow],
      total: 3,
      page: 1,
      size: 20
    } as never);
  });

  it("shows the Weidian withdrawable column without a shop profit column", async () => {
    const wrapper = mount(SettlementDetailsPage, {
      global: { plugins: [i18n, ElementPlus] }
    });
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain("微店可提现金额");
    expect(text).not.toContain("店铺获利");
    expect(text).not.toContain("净增减");
    wrapper.unmount();
  });

  it("shows a dash for an unknown withdrawable amount and labels the three row states", async () => {
    const wrapper = mount(SettlementDetailsPage, {
      global: { plugins: [i18n, ElementPlus] }
    });
    await flushPromises();

    const rows = wrapper.findAll(".el-table__body tbody tr");
    expect(rows).toHaveLength(3);
    const headers = wrapper.findAll(".el-table__header th").map(th => th.text());
    const column = headers.findIndex(header => header.includes("微店可提现金额"));
    expect(column).toBeGreaterThan(-1);
    expect(rows[0].findAll("td")[column].text()).toBe("-");
    expect(rows[0].text()).toContain("冻结中");
    expect(rows[1].text()).toContain("已入账");
    expect(rows[1].findAll("td")[column].text()).toContain("64.70");
    expect(rows[2].text()).toContain("待核实");
    wrapper.unmount();
  });
});
