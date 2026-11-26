import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import ElementPlus from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/commission/finance";
import * as analytics from "@/api/analytics";
import { createFinanceDemo } from "../../../../../preview/commission/finance-engine";
import type * as FinanceUtils from "@/utils/finance";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import { currentFinanceRole, financePreviewAllows } from "@/utils/finance";
import DataTable from "@/components/DataTable.vue";
import IncomePage from "@/views/kol/income/index.vue";
import ConfigPage from "@/views/fund/settlement-config/index.vue";
import SettlementPage from "@/views/fund/settlement-details/index.vue";
import TasksPage from "../index.vue";

vi.mock("@/api/analytics", () => ({
  analyticsOptions: vi.fn().mockResolvedValue({ shops: [], creators: [], inviters: [] }),
  queryAnalytics: vi.fn().mockRejectedValue(new Error("No analytics fixture"))
}));
vi.mock("@/api/commission/finance", () => ({
  listIncome: vi.fn(),
  getIncome: vi.fn(),
  getConfig: vi.fn(),
  saveConfig: vi.fn(),
  listSettlementLedger: vi.fn(),
  listFinanceTasks: vi.fn(),
  getWithdrawal: vi.fn(),
  reviewWithdrawal: vi.fn(),
  payWithdrawal: vi.fn(),
  exportWithdrawals: vi.fn(),
  uploadWithdrawalVoucher: vi.fn(),
  downloadWithdrawalVoucher: vi.fn()
}));
vi.mock("@/utils/finance", async original => ({
  ...(await original<typeof FinanceUtils>()),
  financeApiReady: true
}));
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  setActivePinia(createPinia());
  i18n.global.locale.value = "zh-CN";
  useUserStore().user = {
    id: "102311",
    username: "integration_test",
    nickname: "Integration",
    email: null,
    role: "SUPER_ADMIN",
    postTemplate: null,
    permissions: [],
    dataScope: "ALL",
    tenantId: "102300",
    tenantName: "Integration",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-23T00:00:00Z"
  };
  const demo = createFinanceDemo();
  vi.mocked(api.getConfig).mockResolvedValue(demo.getConfig("SUPER_ADMIN", "data"));
  vi.mocked(api.listSettlementLedger).mockResolvedValue({
    settledGoodsAmount: "0",
    pendingAmount: "0",
    platformFeeAmount: "0",
    activity: { items: [], total: "0", page: 1, size: 20 }
  });
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
  plugins: [
    ElementPlus,
    i18n,
    createRouter({ history: createMemoryHistory(), routes: [{ path: "/", component: TasksPage }] })
  ],
  stubs: { IncomeDrawer: true, WithdrawalDrawer: true, PaymentDialog: true }
};

describe("commission pages inside the existing application", () => {
  it("loads income and its confirmed columns outside the standalone preview mode", async () => {
    const wrapper = mount(IncomePage, { global });
    await flushPromises();
    expect(analytics.queryAnalytics).toHaveBeenCalledWith("kol-ranking", expect.anything(), true);
    expect(
      wrapper
        .getComponent(DataTable)
        .props("columns")
        .map(column => column.key)
    ).toContain("estimatedCommission");
    expect(wrapper.find('[data-test="income-keyword"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="finance-preview-notice"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("loads the existing finance tab, settlement details and configuration in normal mode", async () => {
    for (const component of [TasksPage, SettlementPage, ConfigPage]) {
      const wrapper = mount(component, { global });
      await flushPromises();
      expect(wrapper.findComponent(DataTable).exists()).toBe(true);
      wrapper.unmount();
    }
    expect(api.listFinanceTasks).toHaveBeenCalled();
    expect(api.listSettlementLedger).toHaveBeenCalled();
    expect(api.getConfig).toHaveBeenCalled();
  });

  it("uses the signed-in identity for demo access without replacing the session", () => {
    const user = useUserStore();
    const original = user.user;
    expect(currentFinanceRole()).toBe("SUPER_ADMIN");
    expect(user.user).toBe(original);
    user.user = {
      ...user.user!,
      role: "STAFF",
      dataScope: "OWN",
      permissions: ["commission:view"]
    };
    expect(currentFinanceRole()).toBe("BD");
    expect(financePreviewAllows("view")).toBe(true);
    expect(financePreviewAllows("review")).toBe(false);
    expect(financePreviewAllows("pay")).toBe(false);
    expect(financePreviewAllows("export")).toBe(false);
    user.user = { ...user.user!, permissions: ["commission:view", "withdrawal:review"] };
    expect(financePreviewAllows("review")).toBe(true);
    user.user = { ...user.user!, role: "PLATFORM_ADMIN" };
    expect(currentFinanceRole()).toBe("PLATFORM_ADMIN");
    user.user = null;
    expect(currentFinanceRole()).toBe("PLATFORM_ADMIN");
  });
});
