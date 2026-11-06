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
import { saveSession } from "@/utils/session";
import OrderChainPage from "../chain/index.vue";

vi.mock("@/api/order/order");
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

/**
 * The order chain (task 40 §4): every product line carries the copy job (with its jump to the
 * product task page), the creator item link, the order line and its refunds. Task 72 wires the
 * commission data: funds cards, per-line commission blocks and the finance flows; the base fixture
 * mirrors a backend answer without financial data (all blocks null).
 */
const chain: OrderChainVO = {
  orderId: "101",
  orderNo: "2026102100001",
  funds: null,
  financeFlows: null,
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
      commission: null
    },
    {
      orderItemId: "302",
      subOrderId: null,
      copyJob: null,
      link: null,
      quantity: 1,
      payAmount: "60.00",
      refunds: [],
      commission: null
    }
  ]
};

/** Same wiring with the financial blocks filled in, as commission:view viewers receive it. */
const financedChain: OrderChainVO = {
  ...chain,
  funds: {
    payAmount: "180.00",
    refundTotal: "20.00",
    kolCommission: "16.00",
    accountStatus: "UNVERIFIED"
  },
  financeFlows: [
    {
      flowNo: "CL9001",
      orderItemId: "301",
      shopName: "t40 Shop",
      type: "KOL_COMMISSION",
      direction: "INCREASE",
      amount: "18.00",
      businessAt: "2026-10-21T00:00:00Z",
      transferredAt: null,
      withdrawalNo: null
    },
    {
      flowNo: "CL9002",
      orderItemId: "301",
      shopName: "t40 Shop",
      type: "KOL_COMMISSION",
      direction: "DECREASE",
      amount: "2.00",
      businessAt: "2026-10-22T00:00:00Z",
      transferredAt: null,
      withdrawalNo: null
    },
    {
      flowNo: "WA7001",
      orderItemId: "301",
      shopName: "t40 Shop",
      type: "KOL_COMMISSION",
      direction: "DECREASE",
      amount: "10.00",
      businessAt: "2026-10-23T00:00:00Z",
      transferredAt: "2026-10-24T00:00:00Z",
      withdrawalNo: "W20261023000001"
    },
    {
      flowNo: "WA7002",
      orderItemId: "301",
      shopName: "t40 Shop",
      type: "KOL_COMMISSION",
      direction: "DECREASE",
      amount: "6.00",
      businessAt: "2026-10-25T00:00:00Z",
      transferredAt: null,
      withdrawalNo: "W20261025000002"
    }
  ],
  lines: [
    {
      ...chain.lines[0],
      commission: {
        rateSnapshot: "0.1000",
        rateSupplemented: false,
        netCommission: "16.00",
        releaseBasis: "CONFIRMED",
        occupiedTotal: "16.00",
        withdrawals: [
          {
            withdrawalId: "601",
            applicationNo: "W20261023000001",
            occupiedCny: "10.00",
            status: "PAID",
            paidAt: "2026-10-24T00:00:00Z"
          },
          {
            withdrawalId: "602",
            applicationNo: "W20261025000002",
            occupiedCny: "6.00",
            status: "PENDING_PAYMENT",
            paidAt: null
          },
          {
            withdrawalId: "603",
            applicationNo: "W20261025000003",
            occupiedCny: "5.00",
            status: "REJECTED",
            paidAt: null
          }
        ]
      }
    },
    {
      ...chain.lines[1],
      commission: {
        rateSnapshot: null,
        rateSupplemented: false,
        netCommission: null,
        releaseBasis: "UNVERIFIED",
        occupiedTotal: "0.00",
        withdrawals: []
      }
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
  payAmount: "180.00",
  freight: "5.00",
  orderAmount: "185.00",
  discount: "5.00",
  refundAmount: "20.00",
  netReceived: "160.00",
  commission: null,
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
      totalAmount: "120.00",
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
      totalAmount: "60.00",
      payAmount: "60.00",
      refundAmount: null,
      status: null
    }
  ]
};

/** The detail as commission:view viewers receive it: the order-level commission block filled. */
const financedDetail: OrderDetailVO = {
  ...orderDetail,
  commission: {
    commissionableAmount: "160.00",
    rates: ["0.1000"],
    estimatedCommission: "16.00",
    afterSales: false
  }
};

function signIn(role: Role, permissions: string[], dataScope: "ALL" | "OWN" = "ALL") {
  // Seed the stored session before the store instance is created: its access flags key on a
  // live token, and expiresAt itself is not a writable store field (task 72 commission gating).
  saveSession({
    token: "test-token",
    expiresAt: Date.now() + 3_600_000,
    user: {
      id: "1",
      username: "me",
      nickname: "Me",
      email: null,
      role,
      postTemplate: null,
      permissions,
      dataScope,
      tenantId: "1",
      tenantName: "默认公司",
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-01T00:00:00Z"
    }
  });
  useUserStore();
}

async function mountPage() {
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
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(chain);
    vi.mocked(orderApi.getOrder).mockResolvedValue(orderDetail);
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
    expect(wrapper.findAll('[data-test="identity-name"]').some(item => item.text() === "Zoe")).toBe(
      true
    );
    expect(
      wrapper.findAll('[data-test="identity-code"]').some(item => item.text() === "KOL-ZS01")
    ).toBe(true);
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

  it("jumps to the refund list and the creator item list with filter params (task 59)", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const { wrapper, router } = await mountPage();
    // el-table renders a hidden calc copy of each row; take the link that actually has the text.
    const refundLink = [
      ...document.body.querySelectorAll<HTMLButtonElement>('[data-test="chain-refund-link"]')
    ].find(item => item.textContent?.includes("R20261021001"));
    expect(refundLink).toBeTruthy();
    refundLink?.click();
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/order/refunds"));
    expect(router.currentRoute.value.query.refundNo).toBe("R20261021001");

    const itemLink = document.body.querySelector<HTMLButtonElement>(
      '[data-test="chain-kol-item-link"]'
    );
    itemLink?.click();
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/kol/kol-items"));
    expect(router.currentRoute.value.query.linkCode).toBe("LC001");
    wrapper.unmount();
  });

  it("labels the product line title with a field label (R-18)", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const { wrapper } = await mountPage();
    const title = wrapper.get('[data-test="chain-line"] .chain-line__title');
    expect(title.text()).toContain("商品");
    expect(title.text()).toContain("t40 Item");
    expect(title.text()).toContain("Blue / L");
    wrapper.unmount();
  });

  it("shows missing-link states and keeps unknown fund amounts distinct from zero", async () => {
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
    expect(wrapper.find('[data-test="chain-funds"]').text()).toContain("180.00");
    // Task 112: the order-level amounts come from the detail for every viewer; a commission
    // block missing for a viewer who may see it reads 待核实, never a fake 0 or 开发中.
    const funds = wrapper.find('[data-test="chain-funds"]').text();
    expect(funds).toContain("¥20.00");
    expect(funds).toContain("¥160.00");
    expect(funds).toContain("待核实");
    expect(funds).not.toContain("开发中");
    expect(wrapper.find('[data-test="chain-flows-incomplete"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="chain-order-info"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("marks a supplemented rate on the summary row and the line block", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    const supplemented: OrderChainVO = {
      ...financedChain,
      lines: financedChain.lines.map((line, index) => ({
        ...line,
        commission: line.commission
          ? { ...line.commission, rateSupplemented: index === 0 }
          : line.commission
      }))
    };
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(supplemented);
    const { wrapper } = await mountPage();

    // Both the product summary row and the commission block of that line carry the marker.
    const tags = wrapper.findAll('[data-test="rate-supplemented"]');
    expect(tags).toHaveLength(2);
    expect(tags[0].text()).toBe("补录");

    // The second line keeps the pay-time config rate, so it must not claim to be supplemented.
    const lines = wrapper.findAll('[data-test="chain-line"]');
    expect(lines).toHaveLength(2);
    expect(lines[1].text()).not.toContain("补录");

    wrapper.unmount();
  });

  it("renders commission funds, line blocks and finance flows (task 72)", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    vi.mocked(orderApi.getOrder).mockResolvedValue(financedDetail);
    const { wrapper } = await mountPage();

    const funds = wrapper.find('[data-test="chain-funds"]').text();
    expect(funds).toContain("¥16.00");
    // Shop profit is gone from the funds card (task 81, L-024).
    expect(funds).not.toContain("店铺获利");

    // Line 1: rate snapshot, occupation split by application state machine, rejected released.
    const blocks = wrapper.findAll('[data-test="chain-commission"]');
    expect(blocks).toHaveLength(2);
    const first = blocks[0].text();
    expect(first).toContain("佣金与提现");
    expect(first).toContain("10%");
    expect(first).toContain("¥16.00");
    expect(first).toContain("微店确认结算");
    expect(first).toContain("W20261023000001");
    expect(first).toContain("W20261025000002");
    expect(first).toContain("W20261025000003");
    expect(first).toContain("已支付");
    expect(first).toContain("待支付");
    expect(first).toContain("已驳回");
    // Line 2 stays unverified: null amounts read 待核实, the known occupation zero reads 0.00 CNY.
    const secondText = blocks[1].text();
    expect(secondText).toContain("待核实");
    expect(secondText).toContain("¥0.00");
    expect(secondText).toContain("无提现申请");

    // Finance flows: increase for the release credit, decrease for clawback and allocations,
    // per-withdrawal numbers with payment times, "-" when no withdrawal is linked.
    const flows = wrapper.find('[data-test="chain-flows"]').text();
    expect(flows).toContain("CL9001");
    expect(flows).toContain("CL9002");
    expect(flows).toContain("WA7001");
    expect(flows).toContain("WA7002");
    expect(flows).toContain("达人佣金");
    expect(flows).toContain("增加");
    expect(flows).toContain("减少");
    expect(flows).toContain("W20261023000001");
    expect(flows).toContain("W20261025000002");
    expect(flows).toContain("-");
    expect(flows).not.toContain("－");
    expect(flows).toContain("t40 Shop");
    expect(flows).toContain("t40 Item");
    wrapper.unmount();
  });

  it("keeps the 1024 product summary and eight finance columns without guessing missing values", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    const { wrapper } = await mountPage();
    const summary = wrapper.get('[data-test="chain-product-summary"]');
    expect(summary.text()).toContain("t40 Item");
    expect(summary.text()).toContain("Blue / L");
    expect(summary.text()).toContain("Zoe");
    expect(summary.text()).toContain("10%");
    expect(summary.text()).toContain("¥16.00");
    expect(summary.text()).toContain("待核实");
    const summaryTable = wrapper
      .findAllComponents({ name: "DataTable" })
      .find(table => table.props("tableId") === "chain-product-summary");
    expect(
      summaryTable?.props("columns").find((column: { key: string }) => column.key === "kol")?.align
    ).toBe("left");
    const flowTable = wrapper
      .findAllComponents({ name: "DataTable" })
      .find(table => table.props("tableId") === "chain-finance-flows");
    expect(flowTable?.props("columns").map((column: { key: string }) => column.key)).toEqual([
      "flowNo",
      "shopName",
      "type",
      "direction",
      "amount",
      "businessAt",
      "transferredAt",
      "withdrawalNo"
    ]);
    expect(wrapper.get('[data-test="chain-wiring-details"]').text()).toContain("8001");
    // Task 115 (§2.3): the per-order occupation is 提现占用 everywhere on the chain.
    const withdrawalTable = wrapper
      .findAllComponents({ name: "DataTable" })
      .find(table => String(table.props("tableId")).startsWith("chain-withdrawals-"));
    expect(
      withdrawalTable
        ?.props("columns")
        .find((column: { key: string }) => column.key === "occupiedCny")?.label
    ).toBe("提现占用");
    expect(wrapper.text()).toContain("提现占用");
    expect(wrapper.text()).not.toContain("已申请提现");
    wrapper.unmount();
  });

  it("explains missing financial data without commission:view (task 72)", async () => {
    signIn("STAFF", ["order:view"]);
    const { wrapper } = await mountPage();
    const funds = wrapper.find('[data-test="chain-funds"]').text();
    expect(funds).toContain("无权限查看佣金");
    // The order wiring itself stays visible; only the financial blocks disappear.
    expect(wrapper.findAll('[data-test="chain-line"]')).toHaveLength(2);
    expect(wrapper.find('[data-test="chain-commission"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="chain-flows-no-permission"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("shows the six lifecycle nodes and backend occupation total without mixing finance flow amounts", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    const { wrapper } = await mountPage();
    expect(wrapper.findAll(".chain-stages > li")).toHaveLength(6);
    expect(wrapper.get('[data-test="chain-stage-order"]').text()).toContain("2026102100001");
    expect(wrapper.get('[data-test="chain-stage-withdrawal"]').text()).toContain("¥16.00");
    expect(wrapper.get('[data-test="chain-stage-withdrawal"]').text()).toContain("2 条");
    expect(wrapper.get('[data-test="chain-stage-withdrawal"]').text()).not.toContain("3 条");
    expect(wrapper.get('[data-test="chain-stage-finance"]').text()).toContain("待核实");
    const band = wrapper.get('[data-test="chain-header"]').text();
    expect(band).not.toContain("Zoe");
    expect(band).not.toContain("订单状态");
    const summary = wrapper.get('[data-test="chain-product-summary"]');
    expect(summary.findAll("thead th").map(item => item.text())).toEqual([
      "商品",
      "状态",
      "所属达人",
      "单价",
      "件数",
      "商品总价",
      "佣金比例",
      "预估佣金",
      "提现占用",
      "操作"
    ]);
    wrapper.unmount();
  });

  it("shows a formatted unit price and marks a missing one explicitly (task 110)", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    vi.mocked(orderApi.getOrder).mockResolvedValue({
      ...orderDetail,
      items: orderDetail.items.map(item =>
        item.id === "301" ? { ...item, unitPrice: null } : item
      )
    });
    const { wrapper } = await mountPage();
    const rows = wrapper
      .get('[data-test="chain-product-summary"]')
      .findAll("tbody tr")
      .map(row => row.findAll("td")[3]?.text());
    expect(rows).toEqual(["缺失历史售价", "¥60.00"]);
    wrapper.unmount();
  });

  it.each([
    ["completed", "已完成", "success"],
    ["failed", "失败", "danger"],
    ["partial_failed", "部分失败", "warning"],
    ["processing", "处理中", "info"],
    [null, "待核实", "info"]
  ])("renders the actual task status %s without inventing a state", async (status, text, tone) => {
    signIn("SUPER_ADMIN", ["order:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...chain,
      lines: [{ ...chain.lines[0], copyJob: { jobCode: "JOB1", createTime: null, status } }]
    });
    const { wrapper } = await mountPage();
    const node = wrapper.get('[data-test="chain-stage-copy"]');
    expect(node.text()).toContain(text);
    expect(node.find(`.chain-stage__state--${tone}`).exists()).toBe(true);
    wrapper.unmount();
  });

  it("does not turn a partly unknown task set into a confirmed mixed state", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...chain,
      lines: chain.lines.map((line, index) => ({
        ...line,
        copyJob: { jobCode: `JOB${index}`, createTime: null, status: index ? null : "completed" }
      }))
    });
    const { wrapper } = await mountPage();
    expect(wrapper.get('[data-test="chain-stage-copy"]').text()).toContain("待核实");
    expect(wrapper.get('[data-test="chain-stage-copy"]').text()).not.toContain("多种状态");
    wrapper.unmount();
  });

  it("explains an incompatible finance response instead of rendering undefined labels", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      financeFlows: [{ ledgerId: "legacy-flow", amount: "18.00" }]
    } as unknown as OrderChainVO);
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-test="chain-flows-incomplete"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="chain-flows"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain("flowType.undefined");
    wrapper.unmount();
  });

  it("does not describe a legacy response without finance fields as an empty ledger", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      orderId: chain.orderId,
      orderNo: chain.orderNo,
      lines: chain.lines
    } as OrderChainVO);
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-test="chain-flows-incomplete"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="chain-finance-ledger"]').text()).not.toContain("暂无财务流水");
    wrapper.unmount();
  });

  it("only calls an explicit empty flow array empty, and rejects blank critical fields", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValueOnce({ ...financedChain, financeFlows: [] });
    let result = await mountPage();
    expect(result.wrapper.find('[data-test="chain-finance-ledger"]').text()).toContain(
      "暂无财务流水"
    );
    result.wrapper.unmount();
    vi.mocked(orderApi.getOrderChain).mockResolvedValueOnce({
      ...financedChain,
      financeFlows: [{ ...financedChain.financeFlows![0], flowNo: " " }]
    });
    result = await mountPage();
    expect(result.wrapper.find('[data-test="chain-flows-incomplete"]').exists()).toBe(true);
    result.wrapper.unmount();
  });

  it("treats null financial blocks of an OWN viewer as scope limited (task 72)", async () => {
    signIn("STAFF", ["order:view", "commission:view"], "OWN");
    const { wrapper } = await mountPage();
    const funds = wrapper.find('[data-test="chain-funds"]').text();
    expect(funds).toContain("当前权限仅展示本人范围商品行；整单金额不可见");
    expect(wrapper.find('[data-test="chain-flows-scope-limited"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("explains a hidden line commission block for each reason (t87, L-018)", async () => {
    signIn("STAFF", ["order:view"]);
    const { wrapper: noPermission } = await mountPage();
    const hiddenForNoPermission = noPermission.find('[data-test="chain-commission-hidden"]');
    expect(hiddenForNoPermission.exists()).toBe(true);
    expect(hiddenForNoPermission.text()).toContain("当前账号无佣金查看权限");
    const summary = noPermission.get('[data-test="chain-product-summary"]');
    expect(summary.findAll('[data-test="chain-line-commission-hidden"]')).toHaveLength(2);
    expect(summary.text()).toContain("无权限查看佣金");
    noPermission.unmount();

    setActivePinia(createPinia());
    signIn("STAFF", ["order:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    const { wrapper: leaked } = await mountPage();
    const leakedSummary = leaked.get('[data-test="chain-product-summary"]');
    expect(leakedSummary.text()).toContain("无权限查看佣金");
    expect(leakedSummary.text()).not.toContain("10%");
    expect(leakedSummary.text()).not.toContain("¥16.00");
    leaked.unmount();

    setActivePinia(createPinia());
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(chain);
    signIn("STAFF", ["order:view", "commission:view"], "OWN");
    const { wrapper: ownScope } = await mountPage();
    const hiddenForOwnScope = ownScope.find('[data-test="chain-commission-hidden"]');
    expect(hiddenForOwnScope.exists()).toBe(true);
    expect(hiddenForOwnScope.text()).toContain("当前权限仅展示本人范围商品行");
    ownScope.unmount();
  });

  it("keeps the scope hint off a funds-visible OWN order whose line has no commission (t87, L-018)", async () => {
    signIn("STAFF", ["order:view", "commission:view"], "OWN");
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      lines: [financedChain.lines[0]!, { ...financedChain.lines[1]!, commission: null }]
    });
    const { wrapper } = await mountPage();
    expect(wrapper.findAll('[data-test="chain-commission"]')).toHaveLength(1);
    expect(wrapper.find('[data-test="chain-commission-hidden"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="chain-flows-scope-limited"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("shows the ledger status next to the funds cards only when funds are present (t87, L-019)", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      funds: { ...financedChain.funds!, accountStatus: "POSTED" }
    });
    const { wrapper: posted } = await mountPage();
    const status = posted.find('[data-test="chain-account-status"]');
    expect(status.text()).toContain("账务状态");
    expect(status.text()).toContain("已入账");
    posted.unmount();

    vi.mocked(orderApi.getOrderChain).mockResolvedValue(chain);
    const { wrapper: noFunds } = await mountPage();
    expect(noFunds.find('[data-test="chain-account-status"]').exists()).toBe(false);
    noFunds.unmount();
  });

  it("hides shop profit for an OWN viewer whose funds are visible", async () => {
    // Mixed orders hide the whole funds block; fully covered orders show it without a shop
    // profit card (task 81, L-024) and without the scope note.
    signIn("STAFF", ["order:view", "commission:view"], "OWN");
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    vi.mocked(orderApi.getOrder).mockResolvedValue(financedDetail);
    const { wrapper } = await mountPage();
    const funds = wrapper.find('[data-test="chain-funds"]').text();
    expect(funds).toContain("¥16.00");
    expect(funds).not.toContain("店铺获利");
    expect(funds).not.toContain("当前权限仅展示本人范围商品行");
    expect(wrapper.find('[data-test="chain-flows"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("deduplicates one refund across lines in the refund node fallback total", async () => {
    // Without commission:view the chain funds are null, so the refund node falls back to the
    // client sum: the same refund attached to both lines must count once (B07 dedup rule).
    signIn("STAFF", ["order:view"]);
    const duplicated: OrderChainVO = {
      ...chain,
      lines: chain.lines.map(line => ({
        ...line,
        refunds:
          line.orderItemId === "302"
            ? [
                {
                  id: "501",
                  refundNo: "R20261021001",
                  refundType: "RETURN_REFUND",
                  refundStatus: "SUCCESS",
                  amount: "20.00",
                  finishTime: "2026-10-20T13:00:00Z"
                }
              ]
            : line.refunds
      }))
    };
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(duplicated);
    const { wrapper } = await mountPage();
    expect(wrapper.get('[data-test="chain-stage-refund"]').text()).toContain("¥20.00");
    expect(wrapper.find('[data-test="chain-funds"]').text()).toContain("¥180.00");
    wrapper.unmount();
  });

  it("lists the order-level amounts in the amount-terms order with commission (task 112)", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    vi.mocked(orderApi.getOrder).mockResolvedValue(financedDetail);
    const { wrapper } = await mountPage();
    const labels = wrapper
      .get('[data-test="chain-funds"]')
      .findAll(".el-descriptions__label")
      .map(item => item.text());
    expect(labels).toEqual([
      "商品总价",
      "销量",
      "运费",
      "订单金额",
      "优惠",
      "支付金额",
      "退款金额",
      "订单实收",
      "计佣金额",
      "佣金比例",
      "预估佣金"
    ]);
    const funds = wrapper.get('[data-test="chain-funds"]').text();
    expect(funds).toContain("¥185.00");
    expect(funds).toContain("¥5.00");
    expect(funds).toContain("¥160.00");
    expect(funds).toContain("10%");
    expect(funds).toContain("¥16.00");
    wrapper.unmount();
  });

  it("explains a zero estimated commission while the order is in after-sales (task 113)", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    vi.mocked(orderApi.getOrder).mockResolvedValue({
      ...financedDetail,
      commission: {
        commissionableAmount: "0.00",
        rates: ["0.1000"],
        estimatedCommission: "0.00",
        afterSales: true
      }
    });
    const { wrapper } = await mountPage();
    const funds = wrapper.get('[data-test="chain-funds"]');
    expect(funds.get('[data-test="order-amounts-estimated"]').text()).toBe("¥0.00");
    expect(funds.get('[data-test="order-amounts-after-sales"]').text()).toBe(
      "售后处理中，暂不计佣；已释放部分冻结，退款成功后按退款金额扣减"
    );
    expect(funds.get('[data-test="order-amounts"]').text()).not.toContain("待核实");
    wrapper.unmount();
  });

  it("drops the after-sales hint once the refund concludes (task 113)", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    vi.mocked(orderApi.getOrder).mockResolvedValue(financedDetail);
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-test="order-amounts-after-sales"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("shows the product total per line and blanks it when the unit price is missing (task 112)", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    vi.mocked(orderApi.getOrder).mockResolvedValue({
      ...orderDetail,
      itemAmount: null,
      orderAmount: null,
      discount: null,
      items: orderDetail.items.map(item =>
        item.id === "301" ? { ...item, unitPrice: null, totalAmount: null } : item
      )
    });
    const { wrapper } = await mountPage();
    const totals = wrapper
      .get('[data-test="chain-product-summary"]')
      .findAll("tbody tr")
      .map(row => row.findAll("td")[5]?.text());
    expect(totals).toEqual(["缺失历史售价", "¥60.00"]);
    const funds = wrapper.get('[data-test="chain-funds"]').text();
    expect(funds).toContain("缺失历史售价");
    expect(funds).toContain("¥180.00");
    wrapper.unmount();
  });

  it("shows an actionable error state when the chain fails and retries successfully", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    vi.mocked(orderApi.getOrderChain).mockRejectedValueOnce(new Error("chain failed"));
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-test="chain-load-failed"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="chain-funds"]').exists()).toBe(false);
    expect(vi.mocked(notifyError)).toHaveBeenCalledTimes(1);

    await wrapper.get('[data-test="chain-retry"]').trigger("click");
    await flushPromises();
    expect(vi.mocked(orderApi.getOrderChain)).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-test="chain-load-failed"]').exists()).toBe(false);
    expect(wrapper.findAll('[data-test="chain-line"]')).toHaveLength(2);
    wrapper.unmount();
  });
});
