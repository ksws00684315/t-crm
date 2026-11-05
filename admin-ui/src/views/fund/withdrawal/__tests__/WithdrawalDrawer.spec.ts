import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/commission/finance";
import type { Page, Withdrawal, WithdrawalOrder } from "@/api/commission/finance-types";
import { i18n } from "@/locales";
import WithdrawalDrawer from "../WithdrawalDrawer.vue";

vi.mock("@/api/commission/finance", () => ({
  getWithdrawal: vi.fn(),
  getWithdrawalOrders: vi.fn()
}));
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

/** Full detail fixture; tests override history and the stored notes. */
function detail(overrides: Partial<Withdrawal> = {}): Withdrawal {
  return {
    id: "102371",
    applicationNo: "t1023_WD001",
    kolId: "101",
    kolName: "达人一",
    kolCode: "t1023_K001",
    bdId: "5",
    bdName: "商务五",
    type: "COMMISSION",
    amount: "200.00",
    currency: "CNY",
    status: "PAID",
    createdAt: "2026-10-01T08:00:00Z",
    paused: false,
    pauseReason: null,
    account: {
      type: "PAYPAL",
      account: "kol@example.com",
      currency: "USD",
      network: "",
      holder: "KOL ONE"
    },
    note: "",
    reviewNote: "",
    operationsNote: null,
    financeNote: "",
    allocations: [],
    history: [],
    payment: null,
    previousId: null,
    ...overrides
  };
}

function contentCellAfter(label: string): string | undefined {
  const labels = [...document.querySelectorAll(".withdrawal-info .el-descriptions__label")];
  const found = labels.find(item => item.textContent?.trim() === label);
  return found?.nextElementSibling?.textContent?.trim();
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getWithdrawalOrders).mockResolvedValue({
    items: [] as WithdrawalOrder[],
    total: "0",
    page: 1,
    size: 10
  } satisfies Page<WithdrawalOrder>);
});

describe("WithdrawalDrawer notes", () => {
  it("takes the finance note from the PAID history record, not the APPROVED one (L-051)", async () => {
    vi.mocked(api.getWithdrawal).mockResolvedValue(
      detail({
        history: [
          {
            action: "APPROVED",
            at: "2026-10-01T09:00:00Z",
            actor: "运营甲",
            note: "审批通过，备注A"
          },
          { action: "PAID", at: "2026-10-02T09:00:00Z", actor: "财务乙", note: "已打款，备注B" }
        ]
      })
    );
    const wrapper = mount(WithdrawalDrawer, {
      props: { modelValue: true, id: "102371" },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();

    // The two fallbacks must land in their own cells: the APPROVED note feeds the operations
    // note, and the finance note reads the PAID record (the backend writes no stored note here).
    expect(contentCellAfter(i18n.global.t("finance.operationsNote"))).toBe("审批通过，备注A");
    expect(contentCellAfter(i18n.global.t("finance.financeNote"))).toBe("已打款，备注B");
    wrapper.unmount();
  });

  it("still prefers the stored finance notes and falls back to the dash placeholder", async () => {
    vi.mocked(api.getWithdrawal).mockResolvedValue(
      detail({
        financeNote: "线下外币打款",
        history: [
          { action: "APPROVED", at: "2026-10-01T09:00:00Z", actor: "运营甲", note: "备注A" }
        ]
      })
    );
    const wrapper = mount(WithdrawalDrawer, {
      props: { modelValue: true, id: "102371" },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    expect(contentCellAfter(i18n.global.t("finance.financeNote"))).toBe("线下外币打款");
    wrapper.unmount();

    vi.mocked(api.getWithdrawal).mockResolvedValue(detail({ history: [] }));
    const empty = mount(WithdrawalDrawer, {
      props: { modelValue: true, id: "102371" },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    expect(contentCellAfter(i18n.global.t("finance.financeNote"))).toBe("—");
    empty.unmount();
  });
});

describe("WithdrawalDrawer order columns (task 115, B9/B10)", () => {
  function orderFixture(overrides: Partial<WithdrawalOrder> = {}): WithdrawalOrder {
    return {
      id: "1001",
      orderNo: "t1023_WD001",
      products: [{ id: "1", image: "", product: "商品甲", sku: "SKU-A" }],
      orderAmount: "530.00",
      rates: ["0.1500"],
      occupied: "350.00",
      remainingSnapshot: "180.00",
      ...overrides
    };
  }

  async function mountWithOrders(rows: WithdrawalOrder[]) {
    vi.mocked(api.getWithdrawal).mockResolvedValue(detail());
    vi.mocked(api.getWithdrawalOrders).mockResolvedValue({
      items: rows,
      total: String(rows.length),
      page: 1,
      size: 10
    } satisfies Page<WithdrawalOrder>);
    const wrapper = mount(WithdrawalDrawer, {
      props: { modelValue: true, id: "102371" },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    return wrapper;
  }

  it("shows the creator on one line as 名称（编号） and the amount as 申请金额", async () => {
    const wrapper = await mountWithOrders([]);
    const text = document.querySelector('[data-test="withdrawal-detail"]')?.textContent;
    expect(text).toContain("达人一（t1023_K001）");
    expect(contentCellAfter(i18n.global.t("finance.requestedAmount"))).toBe("¥200.00");
    wrapper.unmount();
  });

  it("renders the B9 order columns with amount, rate, 待确认 occupation and remaining", async () => {
    const wrapper = await mountWithOrders([orderFixture()]);
    const text = document.querySelector('[data-test="withdrawal-detail"]')?.textContent;
    expect(text).toContain(i18n.global.t("finance.orderAmount"));
    expect(text).toContain(i18n.global.t("finance.rate"));
    expect(text).toContain(i18n.global.t("finance.actualCommission"));
    expect(text).toContain(i18n.global.t("finance.orderOccupied"));
    expect(text).toContain(i18n.global.t("finance.remainingSnapshot"));
    // 订单金额 and 佣金比例 render the fetched values; 实际佣金 stays 待确认 (M0 pending).
    expect(text).toContain("¥530.00");
    expect(text).toContain("15%");
    expect(text).toContain(i18n.global.t("finance.pendingConfirm"));
    expect(text).toContain("¥350.00");
    expect(text).toContain("¥180.00");
    wrapper.unmount();
  });

  it("joins every distinct rate snapshot with the localized format on mixed-rate orders", async () => {
    // Task 115 review: rates[] comes per snapshot; the cell formats each instead of parsing.
    const wrapper = await mountWithOrders([orderFixture({ rates: ["0.1200", "0.1500"] })]);
    const text = document.querySelector('[data-test="withdrawal-detail"]')?.textContent;
    expect(text).toContain("12% / 15%");
    wrapper.unmount();
  });

  it("blanks 订单金额 when a line misses its historical price and shows the rate dash", async () => {
    const wrapper = await mountWithOrders([orderFixture({ orderAmount: null, rates: [] })]);
    const text = document.querySelector('[data-test="withdrawal-detail"]')?.textContent;
    expect(text).toContain(i18n.global.t("finance.missingHistoricalPrice"));
    expect(text).toContain("—");
    wrapper.unmount();
  });

  it("keeps the product column and the snapshot note about remaining amount", async () => {
    const wrapper = await mountWithOrders([orderFixture()]);
    const text = document.querySelector('[data-test="withdrawal-detail"]')?.textContent;
    expect(text).toContain(i18n.global.t("finance.orderProduct"));
    expect(text).toContain("商品甲");
    expect(text).toContain(i18n.global.t("finance.orderSnapshotNote"));
    wrapper.unmount();
  });
});
