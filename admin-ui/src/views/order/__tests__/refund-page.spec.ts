import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElSelect } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as orderApi from "@/api/order/order";
import type { RefundDetailVO, RefundVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import { notifyError } from "@/utils/notify";
import RefundPage from "../refund/index.vue";

vi.mock("@/api/order/order");
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));
vi.mock("vue-router", () => ({
  useRoute: () => ({ query: routeQuery })
}));
/** Reactive so that mutating it drives the page's route.query watcher (task 59). */
const routeQuery = reactive<Record<string, string | undefined>>({});

/**
 * The refund query page (task 40 §3): filters by refund no., order no. and channel (status and
 * type behind "expand"), the 08.02 rule that type and status are separate columns and that
 * "refunding" never reads as a completed refund, the refund drawer, and the order-detail entry
 * from the original order number.
 */
const processing: RefundVO = {
  id: "501",
  refundNo: "R20261021001",
  orderId: "101",
  orderNo: "2026102100001",
  channel: "WEIDIAN",
  shopName: "t40 Shop",
  shopCode: "S001",
  kolName: "Zoe",
  kolCode: "KOL-ZS01",
  refundType: "RETURN_REFUND",
  refundStatus: "PROCESSING",
  amount: "20.00",
  applyTime: "2026-10-20T12:00:00Z",
  finishTime: null
};

const succeeded: RefundVO = {
  ...processing,
  id: "502",
  refundNo: "R20261021002",
  refundType: "REFUND_ONLY",
  refundStatus: "SUCCESS",
  finishTime: "2026-10-20T13:00:00Z"
};

const refundDetail: RefundDetailVO = {
  ...succeeded,
  merchantNo: "wd-merchant-1",
  lines: [
    {
      orderItemId: "601",
      imageUrl: null,
      itemName: "t40 Item",
      weidianItemId: "9001",
      skuName: "Blue / L",
      kolId: "201",
      kolName: "Zoe",
      kolCode: "KOL-ZS01",
      quantity: 1,
      refundAmount: "20.00"
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
    dataScope: "OWN",
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountPage() {
  const wrapper = mount(RefundPage, {
    global: { plugins: [ElementPlus, i18n] },
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

function statusTags(): HTMLElement[] {
  return [...document.body.querySelectorAll<HTMLElement>('[data-test="refund-status-tag"]')].filter(
    item => item.closest("tr") !== null
  );
}

describe("refund query page (task 40)", () => {
  it("prefills the refund number from the route and re-filters when it changes (task 59)", async () => {
    routeQuery.refundNo = " R20261021001 ";
    const wrapper = await mountPage();
    expect((element("filter-refund-no").element as HTMLInputElement).value).toBe("R20261021001");
    expect(orderApi.listRefunds).toHaveBeenCalledWith(
      expect.objectContaining({ refundNo: "R20261021001" })
    );

    routeQuery.refundNo = "R20261021002";
    await flushPromises();
    expect(orderApi.listRefunds).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, refundNo: "R20261021002" })
    );
    wrapper.unmount();
  });

  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.clear();
    for (const key of Object.keys(routeQuery)) delete routeQuery[key];
    vi.mocked(orderApi.listRefunds).mockReset();
    vi.mocked(orderApi.getRefund).mockReset();
    vi.mocked(orderApi.getOrder).mockReset();
    vi.mocked(notifyError).mockClear();
    setActivePinia(createPinia());
    vi.mocked(orderApi.listRefunds).mockResolvedValue({
      items: [processing, succeeded],
      total: "2",
      page: 1,
      size: 20
    });
  });

  it("shows type and status as separate columns and never reads refunding as success", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const wrapper = await mountPage();
    const labels = wrapper.findAll("th").map(item => item.text());
    // Owner decision 2026-10-24 (task 59 review): the 08.02 columns all show by default.
    expect(labels).toContain("退款类型");
    expect(labels).toContain("退款状态");
    expect(labels).not.toContain("退款类型/状态");
    const tags = statusTags();
    expect(tags[0].textContent).toBe("退款中");
    expect(tags[0].className).not.toContain("el-tag--success");
    expect(tags[1].textContent).toBe("退款成功");
    expect(wrapper.text()).toContain("退货退款");
    expect(wrapper.text()).toContain("仅退款");
    expect(wrapper.text()).toContain("t40 Shop（S001）");
    expect(wrapper.text()).toContain("Zoe（KOL-ZS01）");
    wrapper.unmount();
  });

  it("sends refund number, status and type filters together", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const wrapper = await mountPage();
    await element("filter-refund-no").setValue("R20261021001");
    const statusSelect = wrapper
      .findAllComponents(ElSelect)
      .find(select => select.find('[data-test="filter-refund-status"]').exists());
    statusSelect?.vm.$emit("update:modelValue", "PROCESSING");
    const typeSelect = wrapper
      .findAllComponents(ElSelect)
      .find(select => select.find('[data-test="filter-refund-type"]').exists());
    typeSelect?.vm.$emit("update:modelValue", "RETURN_REFUND");
    await flushPromises();
    element("search").trigger("click");
    await flushPromises();
    expect(vi.mocked(orderApi.listRefunds).mock.lastCall?.[0]).toMatchObject({
      refundNo: "R20261021001",
      refundStatus: "PROCESSING",
      refundType: "RETURN_REFUND"
    });
    wrapper.unmount();
  });

  it("opens the refund drawer with the merchant number and refunded items", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    vi.mocked(orderApi.getRefund).mockResolvedValue(refundDetail);
    const wrapper = await mountPage();
    const buttons = [
      ...document.body.querySelectorAll<HTMLButtonElement>('[data-test="refund-view"]')
    ].filter(item => item.closest("tr") !== null);
    buttons[1].click();
    await flushPromises();
    expect(vi.mocked(orderApi.getRefund)).toHaveBeenCalledWith("502");
    const drawer = document.body.querySelector(".el-drawer");
    expect(drawer?.querySelector('[data-test="refund-detail"]')).not.toBeNull();
    expect(drawer?.textContent).toContain("wd-merchant-1");
    expect(drawer?.textContent).toContain("Blue / L");
    wrapper.unmount();
  });

  it("opens the order detail drawer from the original order number", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    vi.mocked(orderApi.getOrder).mockResolvedValue({
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
      source: "IMPORT",
      orderTime: null,
      payTime: "2026-10-20T10:01:00Z",
      lastSyncTime: null,
      createTime: null,
      itemAmount: "115.00",
      totalAmount: "125.00",
      payAmount: "120.00",
      freight: "5.00",
      items: []
    });
    const wrapper = await mountPage();
    const links = [
      ...document.body.querySelectorAll<HTMLButtonElement>('[data-test="refund-order-link"]')
    ].filter(item => item.closest("tr") !== null);
    links[0].click();
    await flushPromises();
    expect(vi.mocked(orderApi.getOrder)).toHaveBeenCalledWith("101");
    expect(document.body.querySelector('[data-test="order-detail"]')).not.toBeNull();
    wrapper.unmount();
  });

  it("reports query failures through notifyError", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    vi.mocked(orderApi.listRefunds).mockRejectedValue(new Error("boom"));
    const wrapper = await mountPage();
    expect(vi.mocked(notifyError)).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });
});
