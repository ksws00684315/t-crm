import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElSelect } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getCreatorOrders, type CreatorOrder } from "@/api/creator";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import type { LocaleId } from "@/utils/locale";
import OrdersPage from "../index.vue";

vi.mock("@/api/creator", () => ({ getCreatorOrders: vi.fn() }));

const order: CreatorOrder = {
  orderNo: "1234****9012",
  payTime: "2026-10-15T12:00:00Z",
  orderStatus: "PAID",
  settlementStatus: "WITHDRAWABLE",
  payAmount: "128.00",
  refundAmount: "8.00",
  items: [
    {
      itemName: "Widget",
      skuName: "Blue / M",
      quantity: 2,
      payAmount: "128.00",
      refundAmount: "8.00",
      rateSnapshot: "0.15",
      commissionAmount: "18.00",
      settlementStatus: "WITHDRAWABLE"
    }
  ]
};

async function mountPage(locale: LocaleId = "en-US") {
  const pinia = createPinia();
  setActivePinia(pinia);
  useLocaleStore().setLocale(locale);
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/orders");
  const wrapper = mount(OrdersPage, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, pinia, router] }
  });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  vi.mocked(getCreatorOrders).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 });
});

describe("creator order details", () => {
  it("shows all distinct commission rates in an order with multiple SKUs", async () => {
    vi.mocked(getCreatorOrders).mockResolvedValue({
      items: [
        {
          ...order,
          items: [...order.items, { ...order.items[0], skuName: "Red", rateSnapshot: "0.10" }]
        }
      ],
      total: 1,
      page: 1,
      size: 20
    });
    const wrapper = await mountPage();
    expect(wrapper.get('[data-test="order-rates"]').text()).toBe("15.00% / 10.00%");
    wrapper.unmount();
  });
  it("requests the latest 30 local dates and shows the empty state", async () => {
    const wrapper = await mountPage();
    expect(getCreatorOrders).toHaveBeenCalledWith(
      expect.objectContaining({
        page: 1,
        size: 20,
        payFrom: expect.any(String),
        payTo: expect.any(String)
      })
    );
    expect(wrapper.text()).toContain("No orders yet");
    wrapper.unmount();
  });

  it("shows the required columns and settlement color label in both desktop and phone layouts", async () => {
    vi.mocked(getCreatorOrders).mockResolvedValue({ items: [order], total: 1, page: 1, size: 20 });
    const wrapper = await mountPage("zh-CN");
    expect(wrapper.find(".orders-table").exists()).toBe(true);
    expect(wrapper.find(".order-cards").exists()).toBe(true);
    expect(wrapper.text()).toContain("可提现");
    const headers = wrapper.find(".orders-table").text();
    for (const label of [
      "订单号",
      "商品名及SKU",
      "订单金额",
      "支付时间",
      "佣金比例",
      "佣金金额",
      "订单状态"
    ]) {
      expect(headers).toContain(label);
    }
    await wrapper.find(".order-card__summary").trigger("click");
    expect(wrapper.text()).toContain("Widget");
    wrapper.unmount();
  });

  it("keeps duplicate masked order numbers independently expandable", async () => {
    vi.mocked(getCreatorOrders).mockResolvedValue({
      items: [order, order],
      total: 2,
      page: 1,
      size: 20
    });
    const wrapper = await mountPage();
    await wrapper.findAll(".order-card__summary")[0].trigger("click");
    expect(wrapper.findAll(".order-card__details")).toHaveLength(1);
    expect(wrapper.findAll(".order-card__summary")[0].attributes("aria-expanded")).toBe("true");
    expect(wrapper.findAll(".order-card__summary")[1].attributes("aria-expanded")).toBe("false");
    wrapper.unmount();
  });

  it("uses settlement status filters and sends them only after search", async () => {
    const wrapper = await mountPage();
    const statusSelect = wrapper
      .findAllComponents(ElSelect)
      .find(select => select.props("placeholder") === "All statuses");
    await statusSelect?.setValue("FROZEN");
    await flushPromises();
    expect(getCreatorOrders).toHaveBeenCalledTimes(1);
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(getCreatorOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ settlementStatus: "FROZEN", page: 1 })
    );
    await wrapper.get('[data-test="reset"]').trigger("click");
    await flushPromises();
    expect(vi.mocked(getCreatorOrders).mock.lastCall?.[0]).not.toHaveProperty("settlementStatus");
    wrapper.unmount();
  });

  it("includes order number and product name in the applied query", async () => {
    const wrapper = await mountPage();
    await wrapper.get('[data-test="orders-order-no"]').setValue("ORDER-9");
    await wrapper.get('[data-test="orders-product-name"]').setValue("Widget");
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(getCreatorOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ orderNo: "ORDER-9", productName: "Widget" })
    );
    wrapper.unmount();
  });
});
