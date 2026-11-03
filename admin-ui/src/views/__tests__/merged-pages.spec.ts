import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useUserStore } from "@/stores/user";
import ComingSoonPage from "@/views/common/ComingSoon.vue";
import FundPayoutPage from "@/views/fund/payout/index.vue";
import OrderListPage from "@/views/order/index.vue";
import ShopManagementPage from "@/views/product/shop-management/index.vue";

vi.mock("@/api/product/shop", () => ({ listShops: vi.fn().mockResolvedValue([]) }));
vi.mock("@/api/product/product", () => ({
  // Order page reads these through the shared import bindings (task 77).
  downloadProductTemplate: vi.fn(),
  uploadProductImport: vi.fn()
}));
vi.mock("@/api/order/order", () => ({
  listOrders: vi.fn().mockResolvedValue({ items: [], total: "0", page: 1, size: 20 }),
  listSyncStates: vi.fn().mockResolvedValue([]),
  listSyncFailures: vi.fn().mockResolvedValue([]),
  getOrder: vi.fn(),
  getOrderChain: vi.fn(),
  listRefunds: vi.fn().mockResolvedValue({ items: [], total: "0", page: 1, size: 20 }),
  getRefund: vi.fn(),
  triggerOrderSync: vi.fn(),
  downloadOrderImportTemplate: vi.fn(),
  uploadOrderImport: vi.fn(),
  downloadRefundImportTemplate: vi.fn(),
  uploadRefundImport: vi.fn()
}));
vi.mock("@/api/commission/payout-account", () => ({
  listPayoutAccounts: vi.fn().mockResolvedValue({ items: [], total: "0", page: 1, size: 20 })
}));

/**
 * Pages the owner merged into tabs (task 32 §2.1): shop management, order query and creator
 * payouts; plus the shared coming-soon placeholder behind the menu entries still under
 * construction.
 */
function signIn() {
  const store = useUserStore();
  store.user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role: "SUPER_ADMIN",
    postTemplate: null,
    permissions: [],
    dataScope: "ALL",
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountPage(component: typeof ShopManagementPage, path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push(path);
  const wrapper = mount(component, {
    global: { plugins: [ElementPlus, i18n, router] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

describe("merged tab pages (task 32 §2.1)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.clear();
    setActivePinia(createPinia());
    signIn();
  });

  it("shop management shows only the shops page without legacy tabs", async () => {
    const wrapper = await mountPage(ShopManagementPage, "/product/shops");
    expect(wrapper.find(".shop-management").exists()).toBe(true);
    expect(wrapper.findAll(".el-tabs__item")).toHaveLength(0);
    wrapper.unmount();
  });

  it("order query mounts the order list directly, without the single-tab shell", async () => {
    const wrapper = await mountPage(OrderListPage, "/order/orders");
    expect(wrapper.findAll(".el-tabs__item")).toHaveLength(0);
    expect(wrapper.get("h1").text()).toBe("订单查询");
    wrapper.unmount();
  });

  it("creator payouts keeps only the commission withdrawal tab (task 82 removed account review)", async () => {
    vi.spyOn(useUserStore(), "can").mockReturnValue(true);
    const wrapper = await mountPage(FundPayoutPage, "/fund/payouts");
    const labels = wrapper.findAll(".el-tabs__item").map(item => item.text());
    expect(labels).toEqual(["提现申请"]);
    expect(labels).not.toContain("收款账户审核");
    wrapper.unmount();
  });

  it("the shared placeholder names the page and says the feature is developing", async () => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push("/analytics/shop-performance");
    const wrapper = mount(ComingSoonPage, {
      global: { plugins: [ElementPlus, i18n, router] }
    });
    await flushPromises();
    expect(wrapper.get('[data-test="coming-soon"]').text()).toContain("店铺表现");
    expect(wrapper.get('[data-test="coming-soon"]').text()).toContain("该功能正在开发中");
    expect(wrapper.get("h1").text()).toBe("店铺表现");
    expect(wrapper.get('[data-test="coming-soon-home"]').text()).toBe("回工作台");
    wrapper.unmount();
  });
});
