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
    expect(orderNode.text()).toContain("2026102100001");
    expect(text).toContain("7900222333");
    expect(text).not.toContain("8001");
    expect(text).toContain("9001");
    const header = wrapper.get('[data-test="chain-header"]');
    expect(header.text()).toContain("订单信息");
    const creators = header.get('[data-test="chain-creators"]');
    expect(creators.get('[data-test="identity-name"]').text()).toBe("Zoe");
    const code = creators.get('[data-test="identity-code"]');
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
    expect(creators.findAll('[data-test="value"]').map(code => code.text())).toEqual([
      "KOL-ZS01",
      "KOL-LN02"
    ]);
    expect(creators.findAll('[data-test="copy"]')).toHaveLength(2);
    wrapper.unmount();
  });

  it("shows product IDs only with their respective item rows, and keeps the copy job in the journey", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    const [first, second] = chain.lines;
    vi.mocked(orderApi.getOrderChain).mockResolvedValue({
      ...chain,
      lines: [
        first,
        {
          ...second,
          link: {
            ...first.link!,
            kolItemId: "702",
            linkCode: "LC002",
            sourceItemId: "7900222444",
            targetItemId: "9002"
          }
        }
      ]
    });
    const { wrapper, router } = await mountPage();
    const journey = wrapper.get('[data-test="chain-order-info"]');
    expect(journey.find("details, a").exists()).toBe(false);
    expect(journey.get('[data-test="chain-stage-source"]').text()).toBe("原商品已关联");
    expect(journey.get('[data-test="chain-stage-link"]').text()).toBe("达人商品链接已生成");
    const copyJob = journey.get('[data-test="chain-stage-copy"]');
    expect(copyJob.get('[data-test="value"]').text()).toBe("JOB-20261020-01");
    expect(journey.findAll("button").map(button => button.attributes("data-test"))).toEqual([
      "copy"
    ]);
    await copyJob.trigger("click");
    expect(router.currentRoute.value.name).toBe("OrderChain");

    const rows = wrapper.get('[data-test="chain-product-summary"]').findAll("tbody tr");
    expect(rows).toHaveLength(2);
    for (const [index, ids] of [
      [0, ["9001", "7900222333"]],
      [1, ["9002", "7900222444"]]
    ] as const) {
      const row = rows[index];
      expect(row.findAll('[data-test="value"]').map(value => value.text())).toEqual([...ids]);
      expect(row.findAll('[data-test="copy"]')).toHaveLength(2);
    }
    wrapper.unmount();
  });

  it("renders the item fields directly and keeps both platform IDs in the product cell", async () => {
    signIn("SUPER_ADMIN", ["order:view"]);
    saveTablePrefs("chain-order-items", defaultPrefs(["status", "product", "sku"], 20));
    const { wrapper } = await mountPage();
    const summary = wrapper.get('[data-test="chain-product-summary"]');
    expect(summary.findAll("thead th").map(item => item.text())).toEqual([
      "商品图",
      "商品名/SKU",
      "新商品ID/原商品ID",
      "状态",
      "单价",
      "件数",
      "商品总价",
      "运费",
      "实付金额",
      "佣金比例",
      "预估佣金",
      "提现占用"
    ]);
    const rows = summary.findAll("tbody tr");
    expect(rows).toHaveLength(2);
    expect(rows[0].findAll("td")[0]?.find('[data-test="detail-product-image"]').exists()).toBe(
      true
    );
    expect(rows[0].text()).toContain("t40 Item");
    expect(rows[0].text()).toContain("Blue / L");
    const cells = rows[0].findAll("td");
    expect(cells[2]?.text()).toContain("新商品ID9001");
    expect(cells[2]?.text()).toContain("原商品ID7900222333");
    expect(cells[3]?.classes()).toContain("is-left");
    expect(summary.findAll("thead th")[3]?.classes()).toContain("is-left");
    expect(rows[1].text()).toContain("9002");
    expect(rows[1].text()).toContain("—");
    expect(summary.findAll('[data-test="chain-item-total"]').map(cell => cell.text())).toEqual([
      "¥120.00",
      "¥60.00"
    ]);
    expect(summary.findAll('tbody [data-test="chain-order-freight"]')).toHaveLength(1);
    const freightCell = rows[0].get('td[rowspan="2"]');
    expect(freightCell.get('[data-test="chain-order-freight"]').text()).toContain("¥5.00");
    expect(freightCell.text()).toContain("整单");
    expect(summary.find('[data-test="identity-code"]').exists()).toBe(false);
    expect(summary.find("details").exists()).toBe(false);
    wrapper.unmount();
  });

  it("marks a supplemented rate on the corresponding product row", async () => {
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
    expect(wrapper.findAll(".chain-stages > li")).toHaveLength(7);
    const withdrawalNode = wrapper.get('[data-test="chain-stage-withdrawal"]').text();
    expect(withdrawalNode).toContain("提现中");
    expect(withdrawalNode).toContain("提现中 1 笔");
    expect(withdrawalNode).toContain("已提现 1 笔");
    const band = wrapper.get('[data-test="chain-header"]').text();
    expect(band).toContain("2026102100001");
    expect(band).not.toContain("7900222333");
    expect(band).not.toContain("9001");

    const finance = wrapper.get('[data-test="chain-reconciliation"]');
    expect(finance.get("h2").text()).toBe("资金变动");
    const funds = finance.get('[data-test="chain-funds"]').text();
    expect(funds).toContain("微店结算金额¥160.00");
    expect(funds).toContain("¥10.00");
    expect(funds).not.toContain("已入账");
    const flows = finance.get('[data-test="chain-flows"]');
    expect(finance.findAll("table thead")).toHaveLength(1);
    expect(flows.findAll("thead th").map(item => item.text())).toEqual([
      "资金类型",
      "方向",
      "金额",
      "状态",
      "结算/发生/付款时间",
      "结算单号/提现单号"
    ]);
    const rows = flows.findAll("tbody tr").map(row => row.findAll("td").map(cell => cell.text()));
    expect(rows).toHaveLength(5);
    expect(rows[0].slice(0, 4)).toEqual(["货款", "应收增加", "+¥180.00", "已结算"]);
    expect(rows[0][5]).toBe("WD-SETTLEMENT-1");
    expect(rows[1]).toEqual([
      "货款",
      "应收减少",
      "-¥20.00",
      "已结算",
      formatInstant("2026-10-22T00:00:00Z"),
      "—"
    ]);
    expect(rows[2]).toEqual([
      "佣金",
      "支出",
      "-¥11.00",
      "已结算",
      formatInstant("2026-10-23T00:00:00Z"),
      "—"
    ]);
    expect(rows[3].slice(0, 4)).toEqual(["佣金", "支出", "-¥10.00", "已提现"]);
    expect(rows[3][4]).not.toBe("—");
    expect(rows[3][5]).toBe("W20261023000001");
    expect(rows[4]).toEqual(["佣金", "待支出", "¥6.00", "提现中", "—", "W20261025000002"]);
    const flowRows = flows.findAll("tbody tr");
    expect(flowRows[0].findAll("td")[1]?.find(".el-tag").exists()).toBe(false);
    expect(flowRows[0].get(".chain-money--positive").text()).toBe("+¥180.00");
    expect(flowRows[3].get(".chain-money--negative").text()).toBe("-¥10.00");
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
    const rows = wrapper.get('[data-test="chain-flows"]').findAll("tbody tr");
    expect(rows.map(row => row.findAll("td").map(cell => cell.text()))).toEqual(
      expect.arrayContaining([
        ["佣金", "—", "¥3.00", "冻结中", "—", "—"],
        ["佣金", "—", "¥6.00", "待提现", "—", "—"]
      ])
    );
    wrapper.unmount();
  });

  it("keeps order amounts and products visible without letting financial rows leak", async () => {
    signIn("STAFF", ["order:view"]);
    vi.mocked(orderApi.getOrderChain).mockResolvedValue(financedChain);
    const { wrapper } = await mountPage();
    expect(wrapper.get('[data-test="chain-funds"]').text()).toContain("¥180.00");
    expect(wrapper.get('[data-test="chain-funds"]').text()).not.toContain("¥11.00");
    expect(wrapper.get('[data-test="chain-product-summary"]').text()).not.toContain("10%");
    expect(wrapper.get('[data-test="chain-product-summary"]').text()).toContain("无权限查看佣金");
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
        kolCommission: "0.00",
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
    const rows = wrapper.get('[data-test="chain-flows"]').findAll("tbody tr");
    expect(rows.map(row => row.findAll("td").map(cell => cell.text()))).toContainEqual([
      "佣金",
      "—",
      "¥26.00",
      "冻结中",
      "—",
      "—"
    ]);
    expect(wrapper.get('[data-test="chain-stage-withdrawal"]').text()).toContain("冻结中");
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
    expect(wrapper.get('[data-test="chain-funds"]').text()).toContain("微店结算金额—待核实");
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
    expect(wrapper.get('[data-test="chain-funds"]').text()).toContain("¥15.00");
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
      .map(row => row.findAll("td")[4]?.text());
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
    expect(flows.findAll("tbody tr")).toHaveLength(2);
    expect(flows.text()).toContain("W20261023000001");
    expect(flows.text()).not.toContain("货款");
    expect(wrapper.get('[data-test="chain-funds"]').text()).not.toContain("¥16.00");
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
    expect(
      reconciliation
        .get('[data-test="chain-funds"]')
        .findAll("dd")
        .map(amount => amount.text())
    ).toEqual(["¥180.00", "¥20.00", "¥160.00", "—", "¥10.00"]);
    expect(reconciliation.text()).toContain("待结算");
    expect(reconciliation.get('[data-test="chain-flows"]').findAll("tbody tr")).toHaveLength(2);
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
    expect(wrapper.get('[data-test="chain-funds"]').text()).toContain("¥20.00");
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
    expect(
      wrapper
        .get('[data-test="chain-stage-withdrawal"]')
        .get('[data-test="chain-after-sales"]')
        .text()
    ).toBe("售后处理中，暂不计佣；已释放部分冻结，退款成功后按退款金额扣减");
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
    const funds = wrapper.get('[data-test="chain-funds"]').text();
    expect(wrapper.get('[data-test="chain-product-summary"]').text()).toContain("缺失历史售价");
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
    expect(wrapper.get('[data-test="chain-product-summary"]').findAll("tbody tr")).toHaveLength(2);
    wrapper.unmount();
  });
});
