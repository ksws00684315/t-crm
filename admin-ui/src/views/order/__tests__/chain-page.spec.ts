import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as orderApi from "@/api/order/order";
import type { OrderChainVO, OrderDetailVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useUserStore } from "@/stores/user";
import { notifyError } from "@/utils/notify";
import OrderChainPage from "../chain/index.vue";

vi.mock("@/api/order/order");
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

/**
 * The order chain (task 40 §4): every product line carries the copy job (with its jump to the
 * product task page), the creator item link, the order line and its refunds; ledger and
 * withdrawal blocks stay "developing" until the settlement scope lands.
 */
const chain: OrderChainVO = {
  orderId: "101",
  orderNo: "2026102100001",
  lines: [
    {
      orderItemId: "301",
      subOrderId: null,
      copyJob: { jobCode: "JOB-20261020-01", createTime: "2026-10-20T08:00:00Z", status: "done" },
      link: {
        kolItemId: "701",
        linkCode: "LC001",
        sourceProductId: "8001",
        targetItemId: "9001",
        kolId: "201",
        kolName: "Zoe",
        kolCode: "KOL-ZS01"
      },
      quantity: 2,
      payAmount: "120.00",
      refunds: [
        {
          id: "501",
          refundNo: "R20261021001",
          refundType: "RETURN_REFUND",
          refundStatus: "SUCCESS",
          amount: "20.00",
          finishTime: "2026-10-20T13:00:00Z"
        }
      ],
      ledger: null,
      withdrawals: null
    },
    {
      orderItemId: "302",
      subOrderId: null,
      copyJob: null,
      link: null,
      quantity: 1,
      payAmount: "60.00",
      refunds: [],
      ledger: null,
      withdrawals: null
    }
  ]
};

const orderDetail: OrderDetailVO = {
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
  itemAmount: "180.00",
  totalAmount: "185.00",
  payAmount: "180.00",
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
    },
    {
      id: "302",
      imageUrl: null,
      itemName: "t40 Item 2",
      weidianItemId: "9002",
      skuName: null,
      kolId: null,
      kolName: null,
      kolCode: null,
      quantity: 1,
      unitPrice: "60.00",
      payAmount: "60.00",
      refundAmount: null,
      status: null
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
  vi.mocked(orderApi.getOrderChain).mockResolvedValue(chain);
  vi.mocked(orderApi.getOrder).mockResolvedValue(orderDetail);
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/order/orders/101/chain");
  const wrapper = mount(OrderChainPage, {
    global: { plugins: [ElementPlus, i18n, router] },
    attachTo: document.body
  });
  await flushPromises();
  return { wrapper, router };
}

describe("order chain page (task 40)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.clear();
    vi.mocked(orderApi.getOrderChain).mockReset();
    vi.mocked(orderApi.getOrder).mockReset();
    vi.mocked(notifyError).mockClear();
    setActivePinia(createPinia());
  });

  it("loads the chain for the route id and renders one block per line", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const { wrapper } = await mountPage();
    expect(vi.mocked(orderApi.getOrderChain)).toHaveBeenCalledWith("101");
    expect(wrapper.findAll('[data-test="chain-line"]')).toHaveLength(2);
    const text = wrapper.text();
    expect(text).toContain("LC001");
    expect(text).toContain("8001");
    expect(text).toContain("9001");
    expect(text).toContain("Zoe（KOL-ZS01）");
    expect(text).toContain("t40 Shop（S001）");
    wrapper.unmount();
  });

  it("jumps to the product task page with the jobCode filter", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const { wrapper, router } = await mountPage();
    const link = document.body.querySelector<HTMLButtonElement>(
      '[data-test="chain-copy-job-link"]'
    );
    expect(link?.textContent).toContain("JOB-20261020-01");
    link?.click();
    // The task route lazy-loads its page; poll instead of guessing flush counts.
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/product/tasks"));
    expect(router.currentRoute.value.query.jobCode).toBe("JOB-20261020-01");
    wrapper.unmount();
  });

  it("shows a dash for missing links, no-refund lines, and developing blocks", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const { wrapper } = await mountPage();
    const lines = wrapper.findAll('[data-test="chain-line"]');
    const second = lines[1].element as HTMLElement;
    // No copy job and no creator link: the blocks keep their title and show "-".
    const copyJobBlock = second.querySelector('[data-test="chain-copy-job"]')?.textContent ?? "";
    expect(copyJobBlock).toContain("商品创建任务");
    expect(copyJobBlock).not.toContain("JOB-");
    expect(second.querySelector('[data-test="chain-kol-item"]')?.textContent).toContain("-");
    expect(second.querySelector('[data-test="chain-refunds"]')?.textContent).toContain("无退款");
    const first = lines[0].element as HTMLElement;
    for (const testId of ["chain-ledger", "chain-withdrawals"]) {
      expect(first.querySelector(`[data-test="${testId}"]`)?.textContent).toContain("开发中");
    }
    wrapper.unmount();
  });
});
