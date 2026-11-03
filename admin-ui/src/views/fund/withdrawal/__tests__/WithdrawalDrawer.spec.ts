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
