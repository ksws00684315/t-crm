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
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { saveSession } from "@/utils/session";
import { defaultPrefs, saveTablePrefs } from "@/utils/table-prefs";
import OrderChainPage from "../chain/index.vue";

vi.mock("@/api/order/order");
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

const chain: OrderChainVO = {
  orderId: "101",
  orderNo: "2026102100001",
  funds: null,
  financeFlows: null,
  weidianReconciliation: null,
  lines: [
    {
      orderItemId: "301",
      subOrderId: null,
      copyJob: { jobCode: "JOB-20261020-01", createTime: "2026-10-20T08:00:00Z", status: "done" },
      link: {
        kolItemId: "701",
        linkCode: "LC001",
        sourceProductId: "8001",
        sourceItemId: "7900222333",
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
    accountStatus: "UNVERIFIED",
    commissionStatus: "FROZEN"
  },
  weidianReconciliation: {
    settlementAmount: "160.00",
    settlementAt: "2026-10-21T00:00:00Z",
    flows: [
      {
        id: "91001",
        type: "GOODS_INCOME",
        amount: "180.00",
        tradeTime: "2026-10-21T00:00:00Z",
        settlementAt: "2026-10-21T00:00:00Z",
        settlementNo: "WD-SETTLEMENT-1"
      },
      {
        id: "91002",
        type: "REFUND_DEDUCTION",
        amount: "-20.00",
        tradeTime: "2026-10-22T00:00:00Z",
        settlementAt: null,
        settlementNo: null
      },
      {
        id: "91003",
        type: "COMMISSION_EXPENSE",
        amount: "-11.00",
        tradeTime: "2026-10-23T00:00:00Z",
        settlementAt: null,
        settlementNo: null
      }
    ]
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
      withdrawalNo: null,
      orderStatus: "SETTLEABLE",
      commissionStatus: "WITHDRAWABLE",
      settlementAt: "2026-10-21T00:00:00Z",
      settlementNo: "WD-SETTLEMENT-1"
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
      withdrawalNo: "W20261023000001",
      commissionStatus: "WITHDRAWN"
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
  inviterName: "BD Demo",
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
      imageUrl: "https://example.com/t1121-preview-product.jpg",
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

  it("loads the chain for the route id and renders all product rows", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const { wrapper } = await mountPage();
    expect(vi.mocked(orderApi.getOrderChain)).toHaveBeenCalledWith("101");
    expect(wrapper.get('[data-test="chain-product-summary"]').findAll("tbody tr")).toHaveLength(2);
    const text = wrapper.text();
    expect(text).not.toContain("LC001");
    const orderNode = wrapper.get('[data-test="chain-stage-order"]');
    expect(orderNode.text()).toContain("¥180.00");
    expect(orderNode.text()).not.toContain("7900222333");
    expect(orderNode.text()).not.toContain("9001");
    expect(text).not.toContain("8001");
    const header = wrapper.get('[data-test="chain-header"]');
    expect(header.get("h1").text()).toBe("订单信息");
    expect(header.findAll("dl").map(row => row.findAll("dt").map(label => label.text()))).toEqual([
      ["渠道", "店铺", "下单时间", "支付时间"],
      ["达人名", "达人ID", "佣金比例", "邀请人"]
    ]);
    expect(header.text()).toContain("BD Demo");
    const creators = header.get('[data-test="chain-creators"]');
    expect(creators.get('[data-test="identity-name"]').text()).toBe("Zoe");
    const code = header.get('[data-test="identity-code"]');
    expect(code.get('[data-test="value"]').text()).toBe("KOL-ZS01");
    expect(code.find('[data-test="copy"]').exists()).toBe(true);
    expect(text).toContain("t40 Shop（S001）");
    wrapper.unmount();
  });

  it("shows each creator once and uses the chain link when an item has no creator details", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const [first, second] = chain.lines;
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...chain,
      lines: [
        first,
        {
          ...second,
          link: { ...first.link!, kolId: "202", kolName: "Lena", kolCode: "KOL-LN02" }
        },
        { ...first, orderItemId: "303" }
      ]
    });
    const { wrapper } = await mountPage();
    const creators = wrapper.get('[data-test="chain-creators"]');
    expect(creators.findAll('[data-test="identity-name"]').map(name => name.text())).toEqual([
      "Zoe",
      "Lena"
    ]);
    expect(
      wrapper
        .get('[data-test="chain-header"]')
        .findAll('[data-test="identity-code"] [data-test="value"]')
        .map(code => code.text())
    ).toEqual(["KOL-ZS01", "KOL-LN02"]);
    expect(wrapper.findAll('[data-test="identity-code"] [data-test="copy"]')).toHaveLength(2);
    wrapper.unmount();
  });

  it("shows each task with its measured status in the five-stage journey", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const { wrapper } = await mountPage();
    const journey = wrapper.get('[data-test="chain-order-info"]');
    expect(journey.findAll(".chain-stages > li")).toHaveLength(5);
    const copyJob = journey.get('[data-test="chain-stage-copy"]');
    expect(copyJob.get('[data-test="value"]').text()).toBe("JOB-20261020-01");
    expect(journey.findAll("button").map(button => button.attributes("data-test"))).toEqual([
      "copy"
    ]);
    wrapper.unmount();
  });

  it("shows both platform product IDs and net sales after the line refund", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    saveTablePrefs("chain-order-items", defaultPrefs(["status", "product", "sku"], 20));
    const { wrapper } = await mountPage();
    const summary = wrapper.get('[data-test="chain-product-summary"]');
    expect(summary.findAll("thead th").map(item => item.text())).toEqual([
      "商品图",
      "商品名/SKU",
      "原商品ID",
      "新商品ID",
      "状态",
      "单价",
      "件数",
      "总价",
      "实付金额",
      "退款金额",
      "净销售额"
    ]);
    const rows = summary.findAll("tbody tr");
    expect(rows).toHaveLength(2);
    expect(rows[0].findAll("td")[0]?.find('[data-test="detail-product-image"]').exists()).toBe(
      true
    );
    expect(rows[0].text()).toContain("t40 Item");
    expect(rows[0].text()).toContain("Blue / L");
    const cells = rows[0].findAll("td");
    expect(cells[2]?.get('[data-test="value"]').text()).toBe("7900222333");
    expect(cells[3]?.get('[data-test="value"]').text()).toBe("9001");
    expect(cells[2]?.get('[data-test="copy"]').exists()).toBe(true);
    expect(cells[3]?.get('[data-test="copy"]').exists()).toBe(true);
    const unlinked = rows[1].findAll("td");
    expect(unlinked[2]?.text()).toBe("—");
    expect(unlinked[3]?.get('[data-test="value"]').text()).toBe("9002");
    expect(cells[4]?.classes()).toContain("is-left");
    expect(summary.findAll("thead th")[4]?.classes()).toContain("is-left");
    expect(cells.slice(8).map(cell => cell.text())).toEqual(["¥120.00", "¥20.00", "¥100.00"]);
    expect(summary.findAll('[data-test="chain-item-total"]').map(cell => cell.text())).toEqual([
      "¥120.00",
      "¥60.00"
    ]);
    expect(summary.find('[data-test="identity-code"]').exists()).toBe(false);
    expect(summary.find("details").exists()).toBe(false);
    wrapper.unmount();
  });

  it("marks a supplemented commission rate in the order header", async () => {
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

    const tags = wrapper.findAll('[data-test="rate-supplemented"]');
    expect(tags).toHaveLength(1);
    expect(tags[0].text()).toBe("补录");

    // The second line keeps the pay-time config rate, so it must not claim to be supplemented.
    const lines = wrapper.get('[data-test="chain-product-summary"]').findAll("tbody tr");
    expect(lines).toHaveLength(2);
    expect(lines[1].text()).not.toContain("补录");

    wrapper.unmount();
  });

  it("keeps the chain first and displays platform and creator funds in one table", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    vi.mocked(orderApi.getOrder).mockResolvedValue(financedDetail);
    const { wrapper } = await mountPage();

    expect(
      wrapper
        .findAll(".chain-page > section[data-test]")
        .map(section => section.attributes("data-test"))
    ).toEqual(["chain-order-info", "chain-product-details", "chain-reconciliation"]);
    expect(wrapper.findAll(".chain-stages > li")).toHaveLength(5);
    const withdrawalNode = wrapper.get('[data-test="chain-stage-withdrawal"]').text();
    expect(withdrawalNode).toContain("提现中");
    expect(withdrawalNode).toContain("¥16.00");
    const band = wrapper.get('[data-test="chain-header"]').text();
    expect(band).toContain("2026102100001");
    expect(band).not.toContain("7900222333");
    expect(band).not.toContain("9001");

    const finance = wrapper.get('[data-test="chain-reconciliation"]');
    expect(finance.get("h2").text()).toBe("资金变动");
    expect(finance.find('[data-test="chain-funds"]').exists()).toBe(false);
    const flows = finance.get('[data-test="chain-flows"]');
    expect(finance.findAll("table thead")).toHaveLength(1);
    expect(flows.findAll("thead th").map(item => item.text())).toEqual([
      "资金类型",
      "方向",
      "金额",
      "状态",
      "变动时间",
      "结算单号/提现单号"
    ]);
    const rows = flows.findAll("tbody tr").map(row => row.findAll("td").map(cell => cell.text()));
    expect(rows).toHaveLength(4);
    expect(rows[0].slice(0, 4)).toEqual(["货款", "应收增加", "+¥180.00", "可结算"]);
    expect(rows[0][5]).toBe("WD-SETTLEMENT-1");
    expect(rows[1]).toEqual([
      "货款",
      "应收减少",
      "-¥20.00",
      "已退款",
      formatInstant("2026-10-22T00:00:00Z"),
      "—"
    ]);
    expect(rows[2]).toEqual([
      "佣金",
      "应付减少",
      "-¥11.00",
      "平台已支出",
      formatInstant("2026-10-23T00:00:00Z"),
      "—"
    ]);
    expect(rows[3].slice(0, 4)).toEqual(["佣金", "应付减少", "-¥10.00", "部分已提现"]);
    expect(rows[3][4]).not.toBe("—");
    expect(rows[3][5]).toContain("W20261023000001");
    expect(rows[3][5]).toContain("W20261025000002");
    const flowRows = flows.findAll("tbody tr");
    expect(flowRows[0].findAll("td")[1]?.find(".el-tag").exists()).toBe(false);
    expect(flowRows[0].get(".chain-money--positive").text()).toBe("+¥180.00");
    expect(flowRows[1].get(".chain-money--negative").text()).toBe("-¥20.00");
    expect(flowRows[3].get(".chain-money--positive").text()).toBe("-¥10.00");
    expect(flowRows[3].get(".chain-fund-tag--commission").text()).toBe("佣金");
    expect(flows.text()).not.toContain("待支付");
    expect(flows.text()).not.toContain("W20261025000003");
    expect(flows.text()).not.toContain("¥18.00");
    wrapper.unmount();
  });

  it("shows frozen commission and the available balance after subtracting occupied allocations", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      funds: { ...financedChain.funds!, financeStatus: "FROZEN", kolCommission: "25.00" },
      financeFlows: financedChain.financeFlows!.map((flow, index) =>
        index === 0 ? { ...flow, amount: "24.00" } : flow
      )
    });
    const { wrapper } = await mountPage();
    const flows = wrapper.get('[data-test="chain-flows"]');
    expect(flows.text()).not.toContain("¥3.00");
    expect(flows.text()).not.toContain("待提现");
    wrapper.unmount();
  });

  it("shows only the two payment events for a paid order with frozen commission", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    const payment = {
      orderItemId: null,
      shopName: "t40 Shop",
      direction: "INCREASE" as const,
      businessAt: "2026-10-21T00:00:00Z",
      transferredAt: null,
      withdrawalNo: null,
      orderStatus: "PENDING_SETTLEMENT"
    };
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      funds: {
        ...financedChain.funds!,
        payAmount: "150.00",
        kolCommission: "30.00",
        financeStatus: "FROZEN"
      },
      lines: financedChain.lines.map(line => ({
        ...line,
        commission: { ...line.commission!, occupiedTotal: "0.00", withdrawals: [] }
      })),
      weidianReconciliation: { settlementAmount: null, settlementAt: null, flows: [] },
      financeFlows: [
        { ...payment, flowNo: "SR1", type: "SHOP_RECEIVABLE", amount: "150.00" },
        { ...payment, flowNo: "CA1", type: "COMMISSION_ACCRUAL", amount: "30.00" }
      ]
    });
    const { wrapper } = await mountPage();
    const rows = wrapper.get('[data-test="chain-flows"]').findAll("tbody tr");
    expect(
      rows.map(row =>
        row
          .findAll("td")
          .map(cell => cell.text())
          .slice(0, 4)
      )
    ).toEqual([
      ["货款", "应收增加", "¥150.00", "冻结中"],
      ["佣金", "应付增加", "¥30.00", "冻结中"]
    ]);
    expect(rows[1].get(".chain-money--negative").text()).toBe("¥30.00");
    wrapper.unmount();
  });

  it("keeps order amounts and products visible without letting financial rows leak", async () => {
    signIn("STAFF", ["order:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    const { wrapper } = await mountPage();
    expect(wrapper.get('[data-test="chain-product-summary"]').text()).not.toContain("10%");
    expect(wrapper.get('[data-test="chain-header"]').text()).toContain("当前账号无佣金查看权限");
    expect(wrapper.get('[data-test="chain-stage-withdrawal"]').text()).not.toContain("W202610");
    expect(wrapper.find('[data-test="chain-flows-no-permission"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="chain-flows"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("keeps the estimated commission after a pending partial refund frozen", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      funds: {
        ...financedChain.funds!,
        financeStatus: "REFUNDING",
        financeAmount: "26.00",
        kolCommission: "26.00",
        commissionStatus: "FROZEN"
      },
      financeFlows: [],
      lines: financedChain.lines.map(line => ({
        ...line,
        commission: { ...line.commission!, occupiedTotal: "0.00", withdrawals: [] }
      }))
    });
    vi.mocked(orderApi.getOrder).mockResolvedValue({
      ...financedDetail,
      commission: { ...financedDetail.commission!, afterSales: true }
    });
    const { wrapper } = await mountPage();
    expect(wrapper.get('[data-test="chain-flows"]').text()).not.toContain("¥26.00");
    expect(wrapper.get('[data-test="chain-stage-withdrawal"]').text()).toContain("冻结中");
    wrapper.unmount();
  });

  it("shows one refund after completion and one payout row after commission release", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    const common = {
      orderItemId: "301",
      shopName: "t40 Shop",
      transferredAt: null,
      withdrawalNo: null
    };
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      financeFlows: [
        ...financedChain.financeFlows!,
        {
          ...common,
          flowNo: "SRA1",
          type: "REFUND_REQUEST",
          direction: null,
          orderStatus: "REFUNDING",
          amount: "20.00",
          businessAt: "2026-10-21T10:00:00Z"
        },
        {
          ...common,
          flowNo: "SRF1",
          type: "SHOP_RECEIVABLE",
          direction: "DECREASE",
          orderStatus: "DEDUCTION",
          amount: "20.00",
          businessAt: "2026-10-22T00:00:00Z"
        },
        {
          ...common,
          flowNo: "WS1",
          type: "ORDER_SETTLEMENT",
          direction: null,
          orderStatus: "SETTLEABLE",
          amount: "160.00",
          businessAt: "2026-10-23T00:00:00Z"
        }
      ]
    });
    const { wrapper } = await mountPage();
    const rows = wrapper
      .get('[data-test="chain-flows"]')
      .findAll("tbody tr")
      .map(row =>
        row
          .findAll("td")
          .map(cell => cell.text())
          .slice(0, 4)
      );
    expect(rows).not.toContainEqual(["货款", "—", "¥20.00", "退款中"]);
    expect(
      rows.filter(row => row[0] === "佣金" && ["可提现", "部分已提现"].includes(row[3]))
    ).toEqual([["佣金", "应付减少", "-¥10.00", "部分已提现"]]);
    expect(rows.filter(row => row[2] === "-¥20.00")).toEqual([
      ["货款", "应收减少", "-¥20.00", "已退款"]
    ]);
    expect(rows).toContainEqual(["货款", "—", "¥160.00", "可结算"]);
    expect(rows).not.toContainEqual(["货款", "应收增加", "+¥180.00", "可结算"]);
    wrapper.unmount();
  });

  it("shows an unverified settlement when platform goods flows cannot establish a total", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      weidianReconciliation: { ...financedChain.weidianReconciliation!, settlementAmount: null }
    });
    const { wrapper } = await mountPage();
    expect(wrapper.get('[data-test="chain-stage-settlement"]').text()).toContain("待核实");
    wrapper.unmount();
  });

  it("combines this order's allocations on one paid application and opens its withdrawal drawer", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    const paid = financedChain.lines[0].commission!.withdrawals[0];
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      lines: financedChain.lines.map((line, index) =>
        index === 0
          ? line
          : {
              ...line,
              commission: { ...line.commission!, withdrawals: [{ ...paid, occupiedCny: "5.00" }] }
            }
      )
    });
    const { wrapper, router } = await mountPage();
    const rows = wrapper.get('[data-test="chain-flows"]').findAll("tbody tr");
    const paidRows = rows.filter(row => row.text().includes(paid.applicationNo));
    expect(paidRows).toHaveLength(1);
    expect(paidRows[0].text()).toContain("-¥15.00");
    await paidRows[0].get('[data-test="chain-withdrawal-link"]').trigger("click");
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe("FundPayouts"), {
      timeout: 5000
    });
    expect(router.currentRoute.value.query.withdrawalId).toBe(paid.withdrawalId);
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
      .map(row => row.findAll("td")[5]?.text());
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
    expect(node.find(`.el-tag--${tone}`).exists()).toBe(true);
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

  it("does not substitute synthetic ledger rows when the response has no platform evidence", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      weidianReconciliation: null
    });
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-test="chain-flows-incomplete"]').exists()).toBe(true);
    const flows = wrapper.get('[data-test="chain-flows"]');
    expect(flows.findAll("tbody tr")).toHaveLength(1);
    expect(flows.text()).toContain("W20261023000001");
    expect(flows.text()).not.toContain("货款");
    wrapper.unmount();
  });

  it("shows a pending settlement and dashes for an explicit empty platform response", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      weidianReconciliation: {
        settlementAmount: null,
        settlementAt: null,
        flows: []
      }
    });
    const { wrapper } = await mountPage();
    const reconciliation = wrapper.get('[data-test="chain-reconciliation"]');
    expect(wrapper.get('[data-test="chain-stage-settlement"]').text()).toContain("待结算");
    expect(reconciliation.get('[data-test="chain-flows"]').findAll("tbody tr")).toHaveLength(1);
    wrapper.unmount();
  });

  it("treats null financial blocks of an OWN viewer as scope limited", async () => {
    signIn("STAFF", ["order:view", "commission:view"], "OWN");
    const { wrapper } = await mountPage();
    expect(wrapper.get('[data-test="chain-flows-scope-limited"]').text()).toContain(
      "当前权限仅展示本人范围商品行"
    );
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
    vi.mocked(orderApi.getOrder).mockRejectedValue(new Error("detail unavailable"));
    const { wrapper } = await mountPage();
    expect(wrapper.get('[data-test="chain-stage-refund"]').text()).toContain("¥20.00");
    wrapper.unmount();
  });

  it("shows an unconcluded refund without subtracting it from sales or commission", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...financedChain,
      funds: { ...financedChain.funds!, kolCommission: "21.00", commissionStatus: "FROZEN" },
      financeFlows: [],
      lines: [
        {
          ...chain.lines[0]!,
          refunds: [{ ...chain.lines[0]!.refunds[0]!, refundStatus: "UNKNOWN", amount: "105.00" }]
        }
      ]
    });
    vi.mocked(orderApi.getOrder).mockResolvedValue({
      ...financedDetail,
      refundStatus: "NONE",
      payAmount: "105.00",
      refundAmount: "0.00",
      netReceived: "105.00",
      items: [{ ...orderDetail.items[0]!, payAmount: "105.00", refundAmount: "0.00" }],
      commission: {
        commissionableAmount: "105.00",
        rates: ["0.2000"],
        estimatedCommission: "21.00",
        afterSales: true
      }
    });
    const { wrapper } = await mountPage();
    const refundStage = wrapper.get('[data-test="chain-stage-refund"]');
    expect(refundStage.text()).toContain("退款中");
    expect(refundStage.text()).toContain("¥105.00");
    expect(wrapper.get('[data-test="chain-stage-withdrawal"]').text()).toContain("¥21.00");
    const item = wrapper.get('[data-test="chain-product-summary"]').get("tbody tr");
    expect(item.text()).toContain("退款中");
    expect(item.text()).toContain("¥105.00");
    expect(item.text()).toContain("¥0.00");
    wrapper.unmount();
  });

  it("explains that after-sales freezes accrued commission", async () => {
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
    expect(
      wrapper
        .get('[data-test="chain-stage-withdrawal"]')
        .get('[data-test="chain-after-sales"]')
        .text()
    ).toBe("退款中佣金冻结，退款完成后扣减");
    wrapper.unmount();
  });

  it("drops the after-sales hint once the refund concludes (task 113)", async () => {
    signIn("SUPER_ADMIN", ["order:view", "commission:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    vi.mocked(orderApi.getOrder).mockResolvedValue(financedDetail);
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-test="chain-after-sales"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("shows the actual line payment even when the historical unit price is missing", async () => {
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
      .findAll('[data-test="chain-item-pay"]')
      .map(cell => cell.text());
    expect(totals).toEqual(["¥120.00", "¥60.00"]);
    expect(wrapper.get('[data-test="chain-item-total"]').text()).toBe("—");
    expect(wrapper.get('[data-test="chain-product-summary"]').text()).toContain("缺失历史售价");
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
    expect(wrapper.get('[data-test="chain-product-summary"]').findAll("tbody tr")).toHaveLength(2);
    wrapper.unmount();
  });
});
