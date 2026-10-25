import type * as FinanceUtils from "@/utils/finance";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import ElementPlus from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/commission/finance";
import { createFinanceDemo } from "../../../../../preview/commission/finance-engine";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import DataTable from "@/components/DataTable.vue";
import CommissionTable from "@/components/CommissionTable.vue";
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
  listFinanceTasks: vi.fn(),
  getWithdrawal: vi.fn(),
  reviewWithdrawal: vi.fn(),
  payWithdrawal: vi.fn(),
  uploadWithdrawalVoucher: vi.fn(),
  downloadWithdrawalVoucher: vi.fn()
}));
vi.mock("@/utils/finance", async original => ({
  ...(await original<typeof FinanceUtils>()),
  financePreview: true,
  financeApiReady: true
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
  vi.mocked(api.listFinanceTasks).mockResolvedValue(
    (() => {
      const result = demo.listWithdrawals("SUPER_ADMIN", "data", {});
      return {
        ...result,
        items: result.items.map(row => ({ ...row, type: "COMMISSION" as const }))
      };
    })()
  );
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
  it("shows a single state-specific action and blocks refund-paused payment", async () => {
    const wrapper = mount(TasksPage, { global });
    await flushPromises();
    expect(
      wrapper
        .getComponent(DataTable)
        .props("columns")
        .map((column: { label: string }) => column.label)
    ).toEqual(["申请编号", "达人名称及ID", "申请类型", "金额", "状态", "申请时间", "操作"]);
    expect(wrapper.find('[data-test="withdrawal-kol-id"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="withdrawal-bd-name"]').exists()).toBe(false);
    expect(wrapper.findAll('[data-test="withdrawal-progress-action"]')).toHaveLength(4);
    expect(
      wrapper.findAll('[data-test="withdrawal-progress-action"]')[3].attributes("disabled")
    ).toBeDefined();
    expect(wrapper.get('[data-test="withdrawal-page"]').text()).toContain("运营审核");
    expect(wrapper.get('[data-test="withdrawal-page"]').text()).not.toContain("财务审核");
    expect(wrapper.get('[data-test="withdrawal-page"]').text()).toContain("财务支付");
    useUserStore().user = {
      ...useUserStore().user!,
      role: "STAFF",
      permissions: ["commission:view"],
      postTemplate: "OPERATOR"
    };
    await flushPromises();
    expect(wrapper.find('[data-test="withdrawal-progress-action"]').exists()).toBe(false);
    expect(wrapper.findAll('[data-test="withdrawal-view"]')).toHaveLength(8);
    expect(wrapper.find('[data-test="finance-scenario"]').exists()).toBe(false);
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
  it("renders channel and release-basis labels without exposing translation keys", async () => {
    const config = createFinanceDemo().getConfig("SUPER_ADMIN", "data");
    const channel = config.channels[0]!;
    vi.mocked(api.getConfig).mockResolvedValue({
      ...config,
      channels: [
        { ...channel, name: "WEIDIAN", basis: "SETTLE_TIME" },
        { ...channel, name: "WEIDIAN", basis: "COMPLETED_PLUS_DAYS" }
      ]
    });
    const wrapper = mount(ConfigPage, { global });
    await flushPromises();
    const panel = wrapper.get('[data-test="config-page"]');
    expect(panel.text()).not.toContain("finance.");
    expect(panel.text()).toContain("微店结算时间");
    expect(panel.text()).toContain("交易完成后按天数兜底");
    expect(panel.text()).toContain("微店");
    i18n.global.locale.value = "en-US";
    await flushPromises();
    expect(panel.text()).toContain("Weidian settlement time");
    expect(panel.text()).toContain("Days after trade completion");
    expect(panel.text()).not.toContain("finance.");
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
  it("sends the shop filter as shopId and omits the unsupported BD name filter", async () => {
    const wrapper = mount(SettlementPage, { global });
    await flushPromises();
    await wrapper.get('[data-test="settlement-shop-id"]').setValue("1023001");
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    const query = vi.mocked(api.listSettlements).mock.lastCall?.[0];
    expect(query).toMatchObject({ shopId: "1023001" });
    expect(query).not.toHaveProperty("shop");
    const taskPage = mount(TasksPage, { global });
    await flushPromises();
    expect(taskPage.find('[data-test="withdrawal-bd-name"]').exists()).toBe(false);
    expect(vi.mocked(api.listFinanceTasks).mock.lastCall?.[0]).not.toHaveProperty("bdName");
    wrapper.unmount();
    taskPage.unmount();
  });
  it("shows an unverified refund adjustment when its commission line is unverified", async () => {
    const demo = createFinanceDemo();
    const incomeId = demo.listIncome("SUPER_ADMIN", "data", {}).items[0]!.id;
    const demoLines = demo.incomeDetail("SUPER_ADMIN", "data", incomeId, {}).lines;
    const wrapper = mount(CommissionTable, {
      global,
      props: {
        rows: [{ ...demoLines[0], releaseBasis: "UNVERIFIED", adjustment: "0.00" }],
        tableId: "unverified-adjustment"
      }
    });
    await flushPromises();
    expect(wrapper.get('[data-test="commission-adjustment"]').text()).toBe("待核实");
    wrapper.unmount();
  });
  it.each([ConfigPage, SettlementPage, TasksPage])(
    "presents API errors and stops loading for %s",
    async component => {
      vi.mocked(api.getConfig).mockRejectedValue(new Error("network"));
      vi.mocked(api.listSettlements).mockRejectedValue(new Error("network"));
      vi.mocked(api.listFinanceTasks).mockRejectedValue(new Error("network"));
      const wrapper = mount(component, { global });
      await flushPromises();
      expect(notifyError).toHaveBeenCalled();
      wrapper.unmount();
    }
  );
});
