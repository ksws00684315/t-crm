import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getKolOrders } from "@/api/orders";
import { i18n } from "@/locales";
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
});
