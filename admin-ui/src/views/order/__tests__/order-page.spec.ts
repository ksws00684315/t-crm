import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElDatePicker, ElSelect } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
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
 * The order list uses order-level pagination with a shared order band and one line per product.
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
  mountedRouter = router;
  return wrapper;
}

/** The router of the most recent mount, for tests that follow a navigation (R-17). */
let mountedRouter: Router | undefined;

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

  it("renders the five requested filters and grouped product table", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    const wrapper = await mountPage();
    const labels = wrapper.findAll("th").map(item => item.text());
    for (const header of ["商品名称", "达人", "数量", "订单状态", "支付金额", "操作"]) {
      expect(labels).toContain(header);
    }
    expect(refundTags().map(tag => tag.textContent)).toEqual(["部分退款成功"]);
    expect(wrapper.find('[data-test="filter-order-no"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="filter-shop"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="filter-kol-code"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="filter-order-status"]').exists()).toBe(true);
    expect(wrapper.findComponent(ElDatePicker).props("disabled")).toBe(true);
    // The order band keeps the order number, platform creation time, and shop together.
    expect(wrapper.findAll(".order-group-header")).toHaveLength(2);
    expect(wrapper.text()).toContain("t40 Shop（S001）");
    wrapper.unmount();
  });

  it("sends only supported order, shop, creator-code and status filters", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    const wrapper = await mountPage();
    await element("filter-order-no").setValue("2026102100001");
    const channelSelect = wrapper
      .findAllComponents(ElSelect)
      .find(select => select.attributes("data-test") === "filter-shop");
    channelSelect?.vm.$emit("update:modelValue", "1");
    await element("filter-kol-code").setValue("KOL-ZS01");
    const statusSelect = wrapper
      .findAllComponents(ElSelect)
      .find(select => select.attributes("data-test") === "filter-order-status");
    statusSelect?.vm.$emit("update:modelValue", "PAID");
    await flushPromises();
    element("search").trigger("click");
    await flushPromises();
    expect(vi.mocked(orderApi.listOrders).mock.lastCall?.[0]).toMatchObject({
      orderNo: "2026102100001",
      shopId: "1",
      kolCode: "KOL-ZS01",
      orderStatus: "PAID"
    });
    wrapper.unmount();
  });

  it("shows the wide order detail dialog with merchant and item fields", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    vi.mocked(orderApi.getOrder).mockResolvedValue(detail);
    const wrapper = await mountPage();
    rowButtons("action-view")[0].click();
    await flushPromises();
    expect(vi.mocked(orderApi.getOrder)).toHaveBeenCalledWith("101");
    const dialog = document.body.querySelector(".order-detail-dialog");
    expect(dialog?.querySelector('[data-test="order-detail"]')).not.toBeNull();
    const dialogText = dialog?.textContent ?? "";
    for (const expected of ["wd-merchant-1", "115.00", "60.00", "接口同步", "Blue / L"]) {
      expect(dialogText).toContain(expected);
    }
    expect(dialog?.querySelector(".el-dialog__body")?.getAttribute("style") ?? "").not.toContain(
      "overflow-x: scroll"
    );
    wrapper.unmount();
  });

  it("renders per-line creator identity and images from the list response (task 71)", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    const multi = {
      ...attributed,
      items: [
        {
          id: "301",
          subOrderId: null,
          weidianItemId: "9001",
          skuId: null,
          itemName: "First product",
          skuName: null,
          quantity: 1,
          payAmount: "30.00",
          refundAmount: null,
          kolId: "201",
          kolCode: "KOL-ZS01",
          kolName: "Zoe",
          productId: "401",
          kolItemId: null,
          attributionType: 1,
          manualReason: null,
          imageUrl: "https://img.example/first.png"
        },
        {
          id: "302",
          subOrderId: null,
          weidianItemId: "9002",
          skuId: null,
          itemName: "Second product",
          skuName: null,
          quantity: 2,
          payAmount: "90.00",
          refundAmount: null,
          kolId: "202",
          kolCode: "KOL-XH02",
          // A line whose creator has no stored name renders just the code, never a fake name.
          kolName: null,
          productId: "402",
          kolItemId: null,
          attributionType: 1,
          manualReason: null,
          imageUrl: null
        },
        {
          id: "303",
          subOrderId: null,
          weidianItemId: "9003",
          skuId: null,
          itemName: "Third product",
          skuName: null,
          quantity: 1,
          payAmount: "10.00",
          refundAmount: null,
          kolId: null,
          kolCode: null,
          kolName: null,
          productId: "403",
          kolItemId: null,
          attributionType: 3,
          manualReason: null,
          imageUrl: null
        }
      ]
    } satisfies OrderVO;
    vi.mocked(orderApi.listOrders).mockResolvedValue({
      items: [multi],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = await mountPage();

    // el-table keeps one measurement copy of the cell in .hidden-columns; count only real rows.
    const inRows = (selector: string) =>
      [...document.body.querySelectorAll(selector)].filter(el => el.closest("tr") !== null);
    const images = inRows('[data-test="order-item-image"]');
    expect(images).toHaveLength(1);
    expect(images[0]?.querySelector("img")?.getAttribute("src")).toBe(
      "https://img.example/first.png"
    );
    // Lines without an image keep the shared placeholder, not a broken or fake one.
    expect(inRows('[data-test="order-item-image-missing"]')).toHaveLength(2);
    expect(wrapper.text()).toContain("暂无图片");

    const names = inRows('[data-test="identity-name"]')
      .map(el => el.textContent?.trim())
      .filter(Boolean);
    expect(names).toEqual(["Zoe", "-"]);
    const codes = inRows('[data-test="identity-code"]')
      .map(el => el.textContent?.trim())
      .filter(Boolean);
    expect(codes).toEqual(["KOL-ZS01", "KOL-XH02"]);
    // The unattributed line shows the empty marker instead of an identity block.
    expect(inRows('[data-test="order-item-kol-empty"]')).toHaveLength(1);
    wrapper.unmount();
  });

  it("uses order pagination while rendering each product as its own row", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    const multi = {
      ...attributed,
      items: [
        {
          id: "301",
          subOrderId: null,
          weidianItemId: "9001",
          skuId: null,
          itemName: "First product",
          skuName: null,
          quantity: 1,
          payAmount: "30.00",
          refundAmount: null,
          kolId: "201",
          kolCode: "KOL-ZS01",
          kolName: "Zoe",
          productId: null,
          kolItemId: null,
          attributionType: 1,
          manualReason: null,
          imageUrl: null
        },
        {
          id: "302",
          subOrderId: null,
          weidianItemId: "9002",
          skuId: null,
          itemName: "Second product",
          skuName: null,
          quantity: 2,
          payAmount: "90.00",
          refundAmount: null,
          kolId: "201",
          kolCode: "KOL-ZS01",
          kolName: "Zoe",
          productId: null,
          kolItemId: null,
          attributionType: 1,
          manualReason: null,
          imageUrl: null
        }
      ]
    } satisfies OrderVO;
    vi.mocked(orderApi.listOrders).mockResolvedValue({
      items: [multi],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = await mountPage();
    const productRows = [...document.body.querySelectorAll(".order-group-item")];
    expect(productRows).toHaveLength(2);
    expect(wrapper.text()).toContain("First product");
    expect(wrapper.text()).toContain("Second product");
    expect(wrapper.findComponent({ name: "DataTable" }).props("total")).toBe(1);
    expect(typeof wrapper.findComponent({ name: "DataTable" }).props("spanMethod")).toBe(
      "function"
    );
    wrapper.unmount();
  });

  it("disables the chain action for unattributed orders and opens the chain tab otherwise (R-17)", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    const wrapper = await mountPage();
    const buttons = rowButtons("action-chain");
    expect(buttons[1].disabled).toBe(true);
    expect(buttons[0].disabled).toBe(false);
    buttons[0].click();
    // R-17: the chain opens inside the tab system (router.push), never a new browser tab.
    await vi.waitFor(() =>
      expect(mountedRouter?.currentRoute.value.path).toBe("/order/orders/101/chain")
    );
    expect(open).not.toHaveBeenCalled();
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

  it("clears stale order rows and totals when refresh fails, then recovers on retry", async () => {
    signIn("SUPER_ADMIN", MANAGER);
    const wrapper = await mountPage();
    const table = wrapper.findComponent({ name: "DataTable" });
    expect(table.props("total")).toBe(2);
    expect(wrapper.findAll(".order-group-header")).toHaveLength(2);

    vi.mocked(orderApi.listOrders).mockRejectedValueOnce(new Error("refresh failed"));
    table.vm.$emit("refresh");
    await flushPromises();
    expect(table.props("rows")).toEqual([]);
    expect(table.props("total")).toBe(0);
    expect(table.props("loading")).toBe(false);
    expect(wrapper.findAll(".order-group-header")).toHaveLength(0);
    expect(vi.mocked(notifyError)).toHaveBeenCalledTimes(1);

    table.vm.$emit("refresh");
    await flushPromises();
    expect(table.props("total")).toBe(2);
    expect(wrapper.findAll(".order-group-header")).toHaveLength(2);
    wrapper.unmount();
  });
});
