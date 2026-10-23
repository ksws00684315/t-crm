import type * as FinanceUtils from "@/utils/finance";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import ElementPlus from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/commission/finance";
import { createFinanceDemo } from "@/api/commission/finance-engine";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import DataTable from "@/components/DataTable.vue";
import IncomePage from "@/views/kol/income/index.vue";
import ConfigPage from "@/views/fund/settlement-config/index.vue";
import SettlementPage from "@/views/fund/settlement-details/index.vue";
import TasksPage from "../index.vue";
import { notifyError } from "@/utils/notify";
vi.mock("@/api/commission/finance", () => ({
  exportWithdrawals: vi.fn(),
  listIncome: vi.fn(),
  getIncome: vi.fn(),
  getConfig: vi.fn(),
  saveConfig: vi.fn(),
  listSettlements: vi.fn(),
  listWithdrawals: vi.fn(),
  getWithdrawal: vi.fn(),
  reviewWithdrawal: vi.fn(),
  payWithdrawal: vi.fn(),
  uploadWithdrawalVoucher: vi.fn(),
  downloadWithdrawalVoucher: vi.fn()
}));
vi.mock("@/utils/finance", async original => ({
  ...(await original<typeof FinanceUtils>()),
  financePreview: true
}));
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));
beforeEach(() => {
  vi.clearAllMocks();
  setActivePinia(createPinia());
  i18n.global.locale.value = "zh-CN";
  useUserStore().user = {
    id: "102311",
    username: "t1023_test",
    nickname: "t1023_test",
    email: null,
    role: "SUPER_ADMIN",
    postTemplate: null,
    permissions: [],
    dataScope: "ALL",
    tenantId: "102300",
    tenantName: "t1023_test",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-23T00:00:00Z"
  };
  const demo = createFinanceDemo();
  vi.mocked(api.listIncome).mockResolvedValue(demo.listIncome("SUPER_ADMIN", "data", {}));
  vi.mocked(api.getConfig).mockResolvedValue(demo.getConfig("SUPER_ADMIN", "data"));
  vi.mocked(api.listSettlements).mockResolvedValue(demo.settlements("SUPER_ADMIN", "data", {}));
  vi.mocked(api.listWithdrawals).mockResolvedValue(demo.listWithdrawals("SUPER_ADMIN", "data", {}));
});
const global = {
  plugins: [ElementPlus, i18n],
  stubs: { IncomeDrawer: true, WithdrawalDrawer: true, PaymentDialog: true }
};
describe("finance admin pages", () => {
  it("renders exact income headers, copyable identity and independently paid subamounts", async () => {
    const wrapper = mount(IncomePage, { global });
    await flushPromises();
    expect(
      wrapper
        .getComponent(DataTable)
        .props("columns")
        .map((column: { label: string }) => column.label)
    ).toEqual([
      "达人",
      "商务",
      "销售订单数／件数",
      "预估总佣金",
      "冻结佣金",
      "可提现",
      "已付金额",
      "最近打款时间",
      "操作"
    ]);
    expect(wrapper.get('[data-test="income-page"]').text()).toContain("t1023_K001");
    expect(wrapper.get('[data-test="income-page"]').text()).toContain("1320.00");
    expect(wrapper.findAll('[data-test="income-ledger"]')).toHaveLength(3);
    i18n.global.locale.value = "en-US";
    await flushPromises();
    expect(wrapper.getComponent(DataTable).props("columns")[6].label).toBe("Paid amount");
    wrapper.unmount();
  });
  it("queries from the first page, resets filters and handles failures without stale income", async () => {
    const wrapper = mount(IncomePage, { global });
    await flushPromises();
    await wrapper.get('[data-test="income-keyword"]').setValue("t1023_K001");
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(api.listIncome).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: "t1023_K001", page: 1 })
    );
    vi.mocked(api.listIncome).mockRejectedValueOnce(new Error("network"));
    wrapper.getComponent(DataTable).vm.$emit("refresh");
    await flushPromises();
    expect(notifyError).toHaveBeenCalled();
    expect(wrapper.getComponent(DataTable).props("rows")).toEqual([]);
    expect(wrapper.getComponent(DataTable).props("loading")).toBe(false);
    wrapper.unmount();
  });
  it("shows review and payment actions by role and blocks refund-paused payment", async () => {
    const wrapper = mount(TasksPage, { global });
    await flushPromises();
    expect(wrapper.findAll('[data-test="withdrawal-pay"]')).toHaveLength(2);
    expect(wrapper.findAll('[data-test="withdrawal-pay"]')[1].attributes("disabled")).toBeDefined();
    useUserStore().user = {
      ...useUserStore().user!,
      role: "STAFF",
      permissions: ["commission:view"],
      postTemplate: "OPERATOR"
    };
    await flushPromises();
    expect(wrapper.find('[data-test="withdrawal-approve"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="withdrawal-pay"]').exists()).toBe(false);
    expect(wrapper.findAll('[data-test="withdrawal-view"]')).toHaveLength(7);
    wrapper.unmount();
  });
  it("does not submit a rejection without a reason", async () => {
    const wrapper = mount(TasksPage, { global, attachTo: document.body });
    await flushPromises();
    await wrapper.get('[data-test="withdrawal-reject"]').trigger("click");
    await flushPromises();
    const submit = document.querySelector<HTMLButtonElement>('[data-test="review-submit"]');
    submit?.click();
    await flushPromises();
    expect(api.reviewWithdrawal).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it("keeps configuration read only without authorization and validates amounts", async () => {
    useUserStore().user = {
      ...useUserStore().user!,
      role: "STAFF",
      permissions: ["commission:view"],
      postTemplate: "OPERATOR"
    };
    const wrapper = mount(ConfigPage, { global });
    await flushPromises();
    expect(wrapper.find('[data-test="config-save"]').exists()).toBe(false);
    expect(wrapper.get('[data-test="config-minimum"]').attributes("disabled")).toBeDefined();
    wrapper.unmount();
  });
  it("keeps all settlement columns, multiple creators and unknown amounts distinct", async () => {
    const wrapper = mount(SettlementPage, { global });
    await flushPromises();
    expect(wrapper.getComponent(DataTable).props("columns")).toHaveLength(13);
    expect(wrapper.get('[data-test="settlement-page"]').text()).toContain("多个");
    expect(wrapper.get('[data-test="settlement-page"]').text()).toContain("待核实");
    expect(wrapper.get('[data-test="settlement-page"]').text()).toContain("t1023_WD001");
    wrapper.unmount();
  });
  it.each([ConfigPage, SettlementPage, TasksPage])(
    "presents API errors and stops loading for %s",
    async component => {
      vi.mocked(api.getConfig).mockRejectedValue(new Error("network"));
      vi.mocked(api.listSettlements).mockRejectedValue(new Error("network"));
      vi.mocked(api.listWithdrawals).mockRejectedValue(new Error("network"));
      const wrapper = mount(component, { global });
      await flushPromises();
      expect(notifyError).toHaveBeenCalled();
      wrapper.unmount();
    }
  );
});
