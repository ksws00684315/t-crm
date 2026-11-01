import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElSelect } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getCreatorOrders, getCreatorOrderSummary, type CreatorOrder } from "@/api/creator";
import { enUS, i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import type { LocaleId } from "@/utils/locale";
import OrdersPage from "../index.vue";

vi.mock("@/api/creator", () => ({ getCreatorOrders: vi.fn(), getCreatorOrderSummary: vi.fn() }));

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
  vi.mocked(getCreatorOrderSummary).mockResolvedValue({
    salesCny: "1000.00",
    settledCny: "500.00",
    pendingCny: "300.00",
    refundedCny: "120.00"
  });
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

  it("explains the amount once above the desktop table and on every phone card", async () => {
    vi.mocked(getCreatorOrders).mockResolvedValue({
      items: [order, order],
      total: 2,
      page: 1,
      size: 20
    });
    const wrapper = await mountPage();
    const desktopNotes = wrapper.findAll('[data-test="orders-amount-note"]');
    expect(desktopNotes).toHaveLength(1);
    expect(desktopNotes[0].text()).toBe(enUS.orders.amountNote);
    // The note sits directly above the table, not inside any of its rows.
    expect(desktopNotes[0].element.nextElementSibling).toBe(wrapper.get(".orders-table").element);
    expect(wrapper.find(".orders-table .amount-note").exists()).toBe(false);
    const cardNotes = wrapper.findAll(".order-card .amount-note");
    expect(cardNotes).toHaveLength(2);
    expect(cardNotes.every(note => note.text() === enUS.orders.amountNote)).toBe(true);
    wrapper.unmount();
  });

  it("shows no amount note without data", async () => {
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="orders-amount-note"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("hides the empty copy, the amount note and the pager when loading fails", async () => {
    vi.mocked(getCreatorOrders).mockRejectedValue(new Error("network"));
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="orders-amount-note"]').exists()).toBe(false);
    expect(wrapper.text()).toContain(enUS.orders.loadFailed);
    expect(wrapper.text()).not.toContain(enUS.orders.empty);
    expect(wrapper.find(".data-table__empty").exists()).toBe(false);
    expect(wrapper.find(".data-table__pager").exists()).toBe(false);
    expect(wrapper.find(".orders-pager").exists()).toBe(false);
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

describe("sales summary cards (task 92 section 4.4)", () => {
  const amount = (wrapper: Awaited<ReturnType<typeof mountPage>>, key: string) =>
    wrapper.get(`[data-test="summary-${key}-amount"]`).text();

  it("shows the four sales figures in the owner's order, with the refund note beneath", async () => {
    const wrapper = await mountPage();
    const cards = wrapper.findAll(".summary-card");
    expect(cards.map(card => card.text())).toEqual([
      `${enUS.orders.summaryTotal}¥1,000.00`,
      `${enUS.orders.summarySettled}¥500.00`,
      `${enUS.orders.summaryPending}¥300.00`,
      `${enUS.orders.summaryRefunded}¥120.00`
    ]);
    expect(wrapper.get('[data-test="summary-note"]').text()).toBe(
      "Total sales are before refunds. Settled and unsettled sales are after refunds."
    );
    wrapper.unmount();
  });

  it("keeps the owner's Chinese wording", () => {
    const zh = (key: string) => i18n.global.t(key, {}, { locale: "zh-CN" });
    expect(zh("orders.summaryTotal")).toBe("总销售额");
    expect(zh("orders.summarySettled")).toBe("已结算销售额");
    expect(zh("orders.summaryPending")).toBe("待结算销售额");
    expect(zh("orders.summaryRefunded")).toBe("已退款销售额");
    expect(zh("orders.summaryNote")).toBe("总销售额未扣除退款，已结算和待结算销售额已扣除退款。");
  });

  it("asks for the summary with the same filters as the list", async () => {
    const wrapper = await mountPage();
    const listFilter = vi.mocked(getCreatorOrders).mock.calls[0][0];
    const expected: Record<string, unknown> = { ...listFilter };
    delete expected.page;
    delete expected.size;
    expect(getCreatorOrderSummary).toHaveBeenCalledWith(expected);
    wrapper.unmount();
  });

  it("refreshes after a search and after a reset, but not when only the page changes", async () => {
    vi.mocked(getCreatorOrders).mockResolvedValue({ items: [order], total: 60, page: 1, size: 20 });
    const wrapper = await mountPage();
    vi.mocked(getCreatorOrderSummary).mockClear();
    await wrapper
      .get('[data-test="orders-order-no"] input, input[data-test="orders-order-no"]')
      .setValue("1234");
    wrapper.findComponent({ name: "ListSearch" }).vm.$emit("search");
    await flushPromises();
    expect(getCreatorOrderSummary).toHaveBeenCalledTimes(1);
    expect(getCreatorOrderSummary).toHaveBeenLastCalledWith(
      expect.objectContaining({ orderNo: "1234" })
    );
    wrapper.findComponent({ name: "DataTable" }).vm.$emit("update:page", 2);
    await flushPromises();
    expect(getCreatorOrderSummary).toHaveBeenCalledTimes(1);
    wrapper.findComponent({ name: "ListSearch" }).vm.$emit("reset");
    await flushPromises();
    expect(getCreatorOrderSummary).toHaveBeenCalledTimes(2);
    expect(vi.mocked(getCreatorOrderSummary).mock.calls[1][0]).not.toHaveProperty("orderNo");
    wrapper.unmount();
  });

  it("shows a failure with a retry instead of zeros, and recovers on retry", async () => {
    vi.mocked(getCreatorOrderSummary).mockRejectedValueOnce(new Error("down"));
    const wrapper = await mountPage();
    expect(wrapper.get('[data-test="summary-failed"]').text()).toContain(enUS.orders.summaryFailed);
    expect(wrapper.find('[data-test="summary-total-amount"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain("¥0.00");
    await wrapper.get('[data-test="summary-retry"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="summary-failed"]').exists()).toBe(false);
    expect(amount(wrapper, "total")).toBe("¥1,000.00");
    wrapper.unmount();
  });

  it("shows long amounts in full", async () => {
    vi.mocked(getCreatorOrderSummary).mockResolvedValue({
      salesCny: "12345678.90",
      settledCny: "9876543.21",
      pendingCny: "0.00",
      refundedCny: "0.00"
    });
    const wrapper = await mountPage();
    expect(amount(wrapper, "total")).toBe("¥12,345,678.90");
    expect(amount(wrapper, "pending")).toBe("¥0.00");
    wrapper.unmount();
  });
});
