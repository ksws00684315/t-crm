import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElSelect } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as orderApi from "@/api/order/order";
import type { OrderDetailVO, OrderVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useUserStore } from "@/stores/user";
import { notifyError } from "@/utils/notify";
import OrderListPage from "../index.vue";

vi.mock("@/api/order/order");
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

/**
 * The order list after task 40 §2: the 09.04 filters (order number, channel, shop) on top of
 * the existing ones, the 09.04 columns including the order-level refund status, the detail
 * drawer, and the chain entry that unattributed orders cannot use.
 */
const attributed: OrderVO = {
  id: "101",
  shopId: "1",
  shopName: "t40 Shop",
  shopCode: "S001",
  weidianOrderId: "2026102100001",
  kolId: "201",
  kolCode: "KOL-ZS01",
  kolName: "Zoe",
  bdUserId: null,
  bdUserName: null,
  orderStatus: "PAID",
  attributionStatus: "ATTRIBUTED",
  orderTime: "2026-10-20T10:00:00Z",
  payTime: "2026-10-20T10:01:00Z",
  totalAmount: "125.00",
  payAmount: "120.00",
  refundAmount: "20.00",
  items: [],
  channel: "WEIDIAN",
  refundStatus: "PARTIAL_SUCCESS",
  freight: "5.00",
  createTime: "2026-10-20T10:00:05Z"
};

const unattributed: OrderVO = {
  ...attributed,
  id: "102",
  weidianOrderId: "2026102100002",
  kolId: null,
  kolCode: null,
  kolName: null,
  attributionStatus: "UNMATCHED",
  refundStatus: "NONE",
  refundAmount: "0.00"
};

const detail: OrderDetailVO = {
  id: "101",
  orderNo: "2026102100001",
  channel: "WEIDIAN",
  shopId: "1",
  shopName: "t40 Shop",
  shopCode: "S001",
  merchantNo: "wd-merchant-1",
  kolId: "201",
  kolName: "Zoe",
  kolCode: "KOL-ZS01",
  orderStatus: "PAID",
  refundStatus: "PARTIAL_SUCCESS",
  attributionStatus: "ATTRIBUTED",
  source: "API",
  orderTime: "2026-10-20T10:00:00Z",
  payTime: "2026-10-20T10:01:00Z",
  lastSyncTime: "2026-10-20T11:00:00Z",
  createTime: "2026-10-20T10:00:05Z",
  itemAmount: "115.00",
  totalAmount: "125.00",
  payAmount: "120.00",
  freight: "5.00",
  items: [
    {
      id: "301",
      imageUrl: null,
      itemName: "t40 Item",
      weidianItemId: "9001",
      skuName: "Blue / L",
      kolId: "201",
      kolName: "Zoe",
      kolCode: "KOL-ZS01",
      quantity: 2,
      unitPrice: "60.00",
      payAmount: "120.00",
      refundAmount: "20.00",
      status: "REFUNDED"
    }
  ]
};

function signIn(role: Role, permissions: string[]) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: null,
    permissions,
    dataScope: "ALL",
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountPage() {
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/order/orders");
  const wrapper = mount(OrderListPage, {
    global: { plugins: [ElementPlus, i18n, router] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

function element(testId: string): DOMWrapper<Element> {
  const matches = [...document.body.querySelectorAll(`[data-test="${testId}"]`)];
  const el = matches.find(item => item.closest("tr") !== null) ?? matches[0];
  if (!el) throw new Error(`missing ${testId}`);
  return new DOMWrapper(el);
}

function rowButtons(id: string): HTMLButtonElement[] {
  return [...document.body.querySelectorAll<HTMLButtonElement>(`[data-test="${id}"]`)].filter(
    item => item.closest("tr") !== null
  );
}

function refundTags(): HTMLElement[] {
  return [...document.body.querySelectorAll<HTMLElement>('[data-test="refund-status"]')].filter(
    item => item.closest("tr") !== null
  );
}

const MANAGER = ["order:view", "order:manage", "kol:view"];

describe("order list page (task 40)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.clear();
    vi.mocked(orderApi.listOrders).mockReset();
    vi.mocked(orderApi.listSyncStates).mockReset();
    vi.mocked(orderApi.getOrder).mockReset();
    vi.mocked(notifyError).mockClear();
    setActivePinia(createPinia());
    vi.mocked(orderApi.listOrders).mockResolvedValue({
      items: [attributed, unattributed],
      total: "2",
      page: 1,
      size: 20
    });
    vi.mocked(orderApi.listSyncStates).mockResolvedValue([
      { shopId: "1", shopName: "t40 Shop", enabled: true, authorized: true }
    ]);
  });

  it("renders the 09.04 filters and columns including channel and refund status", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    const wrapper = await mountPage();
    const labels = wrapper.findAll("th").map(item => item.text());
    for (const header of [
      "订单号",
      "渠道",
      "店铺",
      "达人",
      "订单状态",
      "退款状态",
      "支付金额",
      "运费",
      "支付时间",
      "创建时间"
    ]) {
      expect(labels).toContain(header);
    }
    expect(refundTags().map(tag => tag.textContent)).toEqual(["部分退款成功", "无退款"]);
    // Shop and creator follow the "name (code)" form; unattributed shows "-".
    expect(wrapper.text()).toContain("t40 Shop（S001）");
    expect(wrapper.text()).toContain("Zoe（KOL-ZS01）");
    wrapper.unmount();
  });

  it("sends the order number and channel filters to the query", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    const wrapper = await mountPage();
    await element("filter-order-no").setValue("2026102100001");
    const channelSelect = wrapper
      .findAllComponents(ElSelect)
      .find(select => select.find('[data-test="filter-channel"]').exists());
    channelSelect?.vm.$emit("update:modelValue", "WEIDIAN");
    await flushPromises();
    element("search").trigger("click");
    await flushPromises();
    expect(vi.mocked(orderApi.listOrders).mock.lastCall?.[0]).toMatchObject({
      orderNo: "2026102100001",
      channel: "WEIDIAN"
    });
    wrapper.unmount();
  });

  it("shows the order detail drawer with merchant and item fields", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    vi.mocked(orderApi.getOrder).mockResolvedValue(detail);
    const wrapper = await mountPage();
    rowButtons("action-view")[0].click();
    await flushPromises();
    expect(vi.mocked(orderApi.getOrder)).toHaveBeenCalledWith("101");
    const drawer = document.body.querySelector(".el-drawer");
    expect(drawer?.querySelector('[data-test="order-detail"]')).not.toBeNull();
    const drawerText = drawer?.textContent ?? "";
    for (const expected of ["wd-merchant-1", "115.00", "60.00", "接口同步", "Blue / L"]) {
      expect(drawerText).toContain(expected);
    }
    wrapper.unmount();
  });

  it("disables the chain action for unattributed orders and opens a tab otherwise", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    const wrapper = await mountPage();
    const buttons = rowButtons("action-chain");
    expect(buttons[1].disabled).toBe(true);
    expect(buttons[0].disabled).toBe(false);
    buttons[0].click();
    await flushPromises();
    expect(open).toHaveBeenCalledTimes(1);
    expect(open.mock.calls[0][0]).toContain("/order/orders/101/chain");
    open.mockRestore();
    wrapper.unmount();
  });

  it("keeps manage entries behind order:manage", async () => {
    signIn("STAFF", ["order:view", "kol:view"]);
    const wrapper = await mountPage();
    expect(document.body.querySelector('[data-test="sync-states"]')).toBeNull();
    expect(document.body.querySelector('[data-test="import-orders"]')).toBeNull();
    wrapper.unmount();
  });

  it("reports query failures and stops loading", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    vi.mocked(orderApi.listOrders).mockRejectedValue(new Error("boom"));
    const wrapper = await mountPage();
    expect(vi.mocked(notifyError)).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).not.toContain("部分退款成功");
    wrapper.unmount();
  });
});
