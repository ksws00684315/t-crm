import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { describe, expect, it } from "vitest";
import type { CommissionLine } from "@/api/commission/finance-types";
import { i18n } from "@/locales";
import CommissionTable from "@/components/CommissionTable.vue";
import DataTable from "@/components/DataTable.vue";

function line(): CommissionLine {
  return {
    id: "102351",
    orderNo: "t1023_WD001",
    itemId: "102361",
    product: "t1023_Canvas Tote",
    sku: "TOTE-NAVY",
    image: "",
    base: "13000.00",
    rate: "0.2000",
    rateVersion: "RATE-2026-09-V2",
    ruleVersion: "WD-2026-10-V1",
    commission: "2600.00",
    adjustment: "0.00",
    releaseBasis: "CONFIRMED",
    businessAt: "2026-10-12T08:00:00Z",
    direction: "CREDIT",
    subject: "SKU-A",
    kol: "达人一",
    afterSales: false
  };
}

function columnKeys(wrapper: ReturnType<typeof mount>) {
  return wrapper
    .getComponent(DataTable)
    .props("columns")
    .map((column: { key: string }) => column.key);
}

describe("CommissionTable compactLedger (task 115, 1105 sheet B8)", () => {
  it("hides the item id and the rate/rule versions in the income ledger drawer", () => {
    const wrapper = mount(CommissionTable, {
      props: { rows: [line()], tableId: "finance-income-ledger", compactLedger: true },
      global: { plugins: [ElementPlus, i18n] }
    });
    const keys = columnKeys(wrapper);
    expect(keys).not.toContain("itemId");
    expect(keys).not.toContain("rateVersion");
    expect(keys).not.toContain("ruleVersion");
    // The commission columns stay readable in the compact ledger.
    expect(keys).toEqual(
      expect.arrayContaining(["orderNo", "product", "base", "rate", "commission", "businessAt"])
    );
    wrapper.unmount();
  });

  it("keeps every evidence column in the settlement details", () => {
    const wrapper = mount(CommissionTable, {
      props: { rows: [line()], tableId: "settlement-commission-evidence" },
      global: { plugins: [ElementPlus, i18n] }
    });
    expect(columnKeys(wrapper)).toEqual([
      "orderNo",
      "product",
      "itemId",
      "base",
      "rate",
      "rateVersion",
      "ruleVersion",
      "commission",
      "adjustment",
      "releaseBasis",
      "businessAt"
    ]);
    wrapper.unmount();
  });
});
