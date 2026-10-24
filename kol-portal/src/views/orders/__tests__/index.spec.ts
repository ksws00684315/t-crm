import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ElSelect } from "element-plus";
import { getKolOrders } from "@/api/orders";
import { enUS, i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import type { LocaleId } from "@/utils/locale";
import OrdersPage from "../index.vue";

vi.mock("@/api/orders", () => ({ getKolOrders: vi.fn() }));

const order = {
  orderNo: "1234****9012",
  payTime: "2026-10-15T12:00:00Z",
  orderStatus: "PAID" as const,
  payAmount: "128.00",
  refundAmount: "8.00",
  items: [
    {
      itemName: "Widget",
      skuName: "Blue / M",
      quantity: 2,
      payAmount: "128.00",
      refundAmount: "8.00"
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
  vi.mocked(getKolOrders).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 });
});

describe("orders page", () => {
  it("requests the default latest 30 local dates and shows the English empty state", async () => {
    const wrapper = await mountPage();
    expect(getKolOrders).toHaveBeenCalledWith(
      expect.objectContaining({
        page: 1,
        size: 20,
        payFrom: expect.any(String),
        payTo: expect.any(String)
      })
    );
    expect(wrapper.text()).toContain("No orders yet. Share your exclusive link");
    wrapper.unmount();
  });

  it("shows Chinese empty text and provides the desktop table and mobile cards", async () => {
    vi.mocked(getKolOrders).mockResolvedValue({ items: [order], total: 1, page: 1, size: 20 });
    const wrapper = await mountPage("zh-CN");
    expect(wrapper.find(".orders-table").exists()).toBe(true);
    expect(wrapper.find(".order-cards").exists()).toBe(true);
    expect(wrapper.text()).toContain("已支付");
    await wrapper.find(".order-card__summary").trigger("click");
    expect(wrapper.text()).toContain("Widget");
    wrapper.unmount();
  });

  it("keeps cards with duplicate masked order numbers independently expandable", async () => {
    vi.mocked(getKolOrders).mockResolvedValue({
      items: [order, { ...order, payTime: "2026-10-14T12:00:00Z" }],
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

  it("shows the Chinese empty state when there are no orders", async () => {
    const wrapper = await mountPage("zh-CN");
    expect(wrapper.text()).toContain("还没有订单。把你的专属链接分享出去后，订单会显示在这里。");
    wrapper.unmount();
  });

  it("normalizes string totals returned by the backend long serializer", async () => {
    vi.mocked(getKolOrders).mockResolvedValue({ items: [], total: "0", page: 1, size: 20 });
    const wrapper = await mountPage();
    expect(wrapper.text()).toContain("No orders yet. Share your exclusive link");
    wrapper.unmount();
  });

  it("lists the English column headers of the desktop table", async () => {
    vi.mocked(getKolOrders).mockResolvedValue({ items: [order], total: 1, page: 1, size: 20 });
    const wrapper = await mountPage();
    const headers = wrapper.find(".orders-table").text();
    for (const label of [
      enUS.orders.orderNo,
      enUS.orders.payTime,
      enUS.orders.status,
      enUS.orders.payAmount,
      enUS.orders.refundAmount
    ]) {
      expect(headers).toContain(label);
    }
    wrapper.unmount();
  });

  it("sends the status filter only after search and drops it on reset", async () => {
    const wrapper = await mountPage();
    const statusSelect = wrapper.findAllComponents(ElSelect)[1];
    statusSelect.vm.$emit("update:modelValue", "PAID");
    await flushPromises();
    expect(getKolOrders).toHaveBeenCalledTimes(1);

    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(getKolOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ orderStatus: "PAID", page: 1, size: 20 })
    );
    expect(wrapper.text()).toContain(`${enUS.orders.status}: ${enUS.orders.statuses.PAID}`);

    await wrapper.get('[data-test="reset"]').trigger("click");
    await flushPromises();
    const lastQuery = vi.mocked(getKolOrders).mock.lastCall?.[0];
    expect(lastQuery).toBeDefined();
    expect(lastQuery).not.toHaveProperty("orderStatus");
    wrapper.unmount();
  });
});
