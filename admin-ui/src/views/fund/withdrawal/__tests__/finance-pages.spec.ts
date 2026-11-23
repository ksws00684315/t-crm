import type * as FinanceUtils from "@/utils/finance";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import ElementPlus, { ElMessage } from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/commission/finance";
import * as analytics from "@/api/analytics";
import type { AnalyticsResult } from "@/api/analytics";
import { createFinanceDemo } from "../../../../../preview/commission/finance-engine";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import { clearStoredSession, saveSession } from "@/utils/session";
import DataTable from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import CommissionTable from "@/components/CommissionTable.vue";
import IncomePage from "@/views/kol/income/index.vue";
import ConfigPage from "@/views/fund/settlement-config/index.vue";
import SettlementPage from "@/views/fund/settlement-details/index.vue";
import TasksPage from "../index.vue";
import { notifyError } from "@/utils/notify";
vi.mock("@/api/analytics", () => ({
  analyticsOptions: vi.fn().mockResolvedValue({ shops: [], creators: [], inviters: [] }),
  queryAnalytics: vi.fn()
}));
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
vi.mock("@/api/product/shop", () => ({ listShops: vi.fn().mockResolvedValue([]) }));
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
  const metrics = {
    paidOrderCount: 1,
    units: 2,
    salesAmount: "120.00",
    cohortRefundAmount: "20.00",
    completedRefundAmount: "20.00",
    netSalesAmount: "100.00",
    refundedOrderCount: 1,
    averageOrderAmount: "120.00",
    amountRefundRate: "0.1667",
    periodRefundedOrderRate: "1"
  };
  const period = { startDate: "2026-11-12", endDate: "2026-11-18" };
  const data: AnalyticsResult = {
    meta: {
      period,
      previousPeriod: period,
      filters: {},
      dataScope: "ALL",
      timezone: "Asia/Shanghai",
      updatedAt: null
    },
    summary: {
      metrics,
      sellingCount: 1,
      creatorCards: {
        periodEffectiveCount: 1,
        relationshipCount: 1,
        spuCount: 2,
        totalCommission: "1200.00",
        commissionBeforeClawback: "1250.00",
        commissionClawback: "50.00",
        expenseThrough: "2026-11-23",
        paidCommission: "150.00",
        paidPrepay: "200.00",
        cumulativeExpense: "350.00"
      }
    },
    change: metrics,
    previous: metrics,
    ranking: {
      items: [
        {
          id: "42",
          name: "Creator",
          code: "t1023_K001",
          social: ["creator_social"],
          relationStatus: "PROMOTING",
          metrics,
          commissionRate: "0.15",
          creatorMoney: {
            estimatedCommission: "1200.00",
            paidPrepay: "200.00"
          }
        }
      ],
      total: 1,
      page: 1,
      size: 20
    },
    unavailable: [],
    sortBy: "netSalesAmount",
    sortDirection: "desc"
  };
  vi.mocked(analytics.queryAnalytics).mockResolvedValue(data);
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
const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: "/", component: TasksPage }]
});
const global = {
  plugins: [ElementPlus, i18n, router],
  stubs: { IncomeDrawer: true, WithdrawalDrawer: true, PaymentDialog: true }
};
describe("finance admin pages", () => {
  it("renders the ranking metrics and payment breakdown on creator income", async () => {
    const wrapper = mount(IncomePage, { global });
    await flushPromises();
    expect(
      wrapper
        .getComponent(DataTable)
        .props("columns")
        .map(column => column.key)
    ).toEqual([
      "identity",
      "relationship",
      "ordersAndUnits",
      "salesAmount",
      "cohortRefundAmount",
      "netSalesAmount",
      "commissionRate",
      "estimatedCommission",
      "paidPrepay",
      "actions"
    ]);
    const income = wrapper.get('[data-test="income-page"]');
    expect(income.text()).toContain("t1023_K001");
    expect(income.text()).toContain("creator_social");
    expect(income.text()).toContain("¥1,200.00");
    const card = wrapper.findAll(".analytics-cards article")[2]!;
    expect(card.text()).toContain("当期预估佣金");
    expect(card.text()).toContain("佣金 ¥1,250.00");
    expect(card.text()).toContain("−");
    expect(card.text()).toContain("扣回 ¥50.00");
    const expense = wrapper.findAll(".analytics-cards article")[3]!;
    expect(expense.text()).toContain("截止到11/23");
    expect(expense.text()).toContain("¥350.00");
    expect(income.text()).toContain("¥200.00");
    expect(income.text()).toContain("15%");
    expect(wrapper.find('[data-test="income-ledger"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("submits income filters from the first page and marks retained results as stale on error", async () => {
    const wrapper = mount(IncomePage, { global });
    await flushPromises();
    await wrapper.get('input[data-test="income-keyword"]').setValue("creator@example.test");
    await wrapper.getComponent(ListSearch).vm.$emit("search");
    await flushPromises();
    expect(analytics.queryAnalytics).toHaveBeenLastCalledWith(
      "kol-ranking",
      expect.objectContaining({ creatorKeyword: "creator@example.test", page: 1 }),
      true
    );
    vi.mocked(analytics.queryAnalytics).mockRejectedValueOnce(new Error("network"));
    wrapper.getComponent(DataTable).vm.$emit("refresh");
    await flushPromises();
    expect(wrapper.getComponent(DataTable).props("rows")).toHaveLength(1);
    expect(wrapper.getComponent(DataTable).props("loading")).toBe(false);
    expect(wrapper.find(".el-alert--warning").exists()).toBe(true);
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
    ).toEqual(["申请编号", "达人名称及ID", "申请金额", "状态", "申请时间", "操作"]);
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
  it("always queries commission withdrawals only, through every list entry point", async () => {
    const wrapper = mount(TasksPage, { global });
    await flushPromises();
    expect(api.listFinanceTasks).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: "COMMISSION", page: 1 })
    );
    expect(api.listFinanceTasks).toHaveBeenLastCalledWith(
      expect.not.objectContaining({ type: "PREPAY" })
    );
    await wrapper.get('[data-test="withdrawal-number"]').setValue("t80_WD001");
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(api.listFinanceTasks).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: "COMMISSION", applicationNo: "t80_WD001", page: 1 })
    );
    wrapper.getComponent(ListSearch).vm.$emit("remove", "applicationNo");
    await flushPromises();
    expect(api.listFinanceTasks).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: "COMMISSION", applicationNo: "" })
    );
    wrapper.getComponent(DataTable).vm.$emit("update:page", 2);
    await flushPromises();
    expect(api.listFinanceTasks).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: "COMMISSION", page: 2 })
    );
    wrapper.getComponent(DataTable).vm.$emit("refresh");
    await flushPromises();
    expect(api.listFinanceTasks).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: "COMMISSION", page: 2 })
    );
    await wrapper.get('[data-test="withdrawal-number"]').setValue("t80_WD002");
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    wrapper.getComponent(ListSearch).vm.$emit("reset");
    await flushPromises();
    expect(api.listFinanceTasks).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: "COMMISSION", applicationNo: "", page: 1 })
    );
    wrapper.unmount();
  });
  it("offers only the five commission withdrawal statuses in the filter", async () => {
    const wrapper = mount(TasksPage, { global });
    await flushPromises();
    // DataTable also renders numeric page-size options; only the status filter uses string values.
    const options = wrapper
      .findAllComponents({ name: "ElOption" })
      .filter(option => typeof option.props("value") === "string");
    expect(options.map(option => option.props("value"))).toEqual([
      "PENDING_REVIEW",
      "PENDING_PAYMENT",
      "PAID",
      "REJECTED",
      "WITHDRAWN"
    ]);
    expect(options.map(option => option.props("label"))).toEqual([
      "待审核",
      "待支付",
      "已支付",
      "已驳回",
      "已撤回"
    ]);
    wrapper.unmount();
  });
  it("opens the task drawer for the withdrawalId notification jump (L-017)", async () => {
    await router.push({ path: "/", query: { withdrawalId: "3001" } });
    const wrapper = mount(TasksPage, {
      global: {
        ...global,
        stubs: {
          WithdrawalDrawer: {
            name: "WithdrawalDrawer",
            props: ["modelValue", "id"],
            template: '<div data-test="withdrawal-drawer" />'
          }
        }
      }
    });
    await flushPromises();
    const drawer = wrapper.getComponent({ name: "WithdrawalDrawer" });
    expect(drawer.props("modelValue")).toBe(true);
    expect(drawer.props("id")).toBe("3001");
    // Closing the drawer strips the param, like the prepay page with its id.
    drawer.vm.$emit("update:modelValue", false);
    await flushPromises();
    expect(router.currentRoute.value.query.withdrawalId).toBeUndefined();
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
  it("blocks saving when the freeze days are cleared", async () => {
    const config = createFinanceDemo().getConfig("SUPER_ADMIN", "data");
    config.channels[0]!.days = null as unknown as number;
    vi.mocked(api.getConfig).mockResolvedValue(config);
    const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({ close: () => {} }));
    const wrapper = mount(ConfigPage, { global });
    await flushPromises();
    await wrapper.get('[data-test="config-save"]').trigger("click");
    await flushPromises();
    expect(api.saveConfig).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith("请输入 0 到 365 之间的冻结天数");
    error.mockRestore();
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
  it("shows the accounting currency as copyable text and marks long/interactive columns", async () => {
    const config = createFinanceDemo().getConfig("SUPER_ADMIN", "data");
    vi.mocked(api.getConfig).mockResolvedValue(config);
    const wrapper = mount(ConfigPage, { global });
    await flushPromises();
    const currency = wrapper.get('[data-test="config-currency"]');
    expect(currency.text()).toContain(config.currency);
    expect(currency.find('[data-test="copy"]').exists()).toBe(true);
    const columns = wrapper.getComponent(DataTable).props("columns") as Array<{
      key: string;
      align?: string;
      interactive?: boolean;
    }>;
    expect(columns.find(column => column.key === "channelCode")?.align).toBe("left");
    expect(columns.find(column => column.key === "releaseBasis")?.align).toBe("left");
    expect(columns.find(column => column.key === "freezeDays")?.interactive).toBe(true);
    wrapper.unmount();
  });

  it("keeps all settlement columns, multiple creators and unknown amounts distinct", async () => {
    const wrapper = mount(SettlementPage, { global });
    await flushPromises();
    expect(wrapper.getComponent(DataTable).props("columns")).toHaveLength(12);
    expect(wrapper.get('[data-test="settlement-page"]').text()).toContain("多个");
    expect(wrapper.get('[data-test="settlement-page"]').text()).toContain("待核实");
    expect(wrapper.get('[data-test="settlement-page"]').text()).toContain("t1023_WD001");
    wrapper.unmount();
  });
  it("sends the shop filter as shopId and omits the unsupported BD name filter", async () => {
    // The filter is gated on product:view, which needs a live session rather than a bare user.
    saveSession({
      token: "test-token",
      expiresAt: Date.now() + 3_600_000,
      user: useUserStore().user!
    });
    setActivePinia(createPinia());
    const wrapper = mount(SettlementPage, { global });
    await flushPromises();
    wrapper
      .findAllComponents({ name: "ElSelect" })
      .find(select => select.attributes("data-test") === "settlement-shop-id")!
      .vm.$emit("update:modelValue", "1023001");
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
    clearStoredSession();
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
  it("tags an after-sales commission line with the reason (task 113)", async () => {
    const demo = createFinanceDemo();
    const incomeId = demo.listIncome("SUPER_ADMIN", "data", {}).items[0]!.id;
    const demoLines = demo.incomeDetail("SUPER_ADMIN", "data", incomeId, {}).lines;
    const wrapper = mount(CommissionTable, {
      global,
      props: {
        rows: [
          {
            ...demoLines[0],
            releaseBasis: "AFTER_SALES",
            commission: "0.00",
            base: "0.00",
            afterSales: true
          },
          // An unverified line of an order in after-sales still carries the hint (PR #206).
          {
            ...demoLines[0],
            id: `${demoLines[0].id}-u`,
            releaseBasis: "UNVERIFIED",
            commission: null,
            afterSales: true
          },
          { ...demoLines[0], id: `${demoLines[0].id}-b`, releaseBasis: "CONFIRMED" }
        ],
        tableId: "after-sales-line"
      }
    });
    await flushPromises();
    const tags = wrapper.findAll('[data-test="commission-after-sales"]');
    expect(tags).toHaveLength(2);
    expect(tags[0].text()).toBe("售后处理中");
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
