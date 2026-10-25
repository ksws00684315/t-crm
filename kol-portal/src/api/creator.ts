import { http } from "./http";

export const isDemoMode = import.meta.env.DEV && import.meta.env.MODE === "demo";
const moneyCents = (value: string) => {
  const [whole, fraction = ""] = value.split(".");
  return BigInt(whole) * 100n + BigInt((fraction + "00").slice(0, 2));
};
const centsMoney = (value: bigint) =>
  `${value / 100n}.${(value % 100n).toString().padStart(2, "0")}`;
const demoFunds: FundSummary = {
  totalIncomeCny: "2400.00",
  commissionIncomeCny: "2400.00",
  estimatedCommissionCny: "5960.00",
  totalCommissionCny: "4680.00",
  withdrawnCny: "2400.00",
  withdrawingCny: "800.00",
  frozenCny: "1480.00",
  receivedPrepayCny: "0.00",
  availableCny: "1280.00",
  prepayDeductionCny: "0.00",
  prepayOutstandingCny: "0.00",
  asOf: "2026-10-24T08:00:00Z"
};
const demoDaily = [
  ["10-18", "4200", "390"],
  ["10-19", "5600", "720"],
  ["10-20", "4800", "540"],
  ["10-21", "8200", "980"],
  ["10-22", "7400", "860"],
  ["10-23", "9100", "1170"],
  ["10-24", "9300", "1300"]
].map(([date, salesCny, commissionCny]) => ({ date: `2026-${date}`, salesCny, commissionCny }));
const demoOrders: CreatorOrder[] = [
  {
    orderNo: "ST-20261024-0091",
    payTime: "2026-10-24T07:30:00Z",
    orderStatus: "COMPLETED",
    settlementStatus: "WITHDRAWABLE",
    payAmount: "1280.00",
    refundAmount: "0.00",
    items: [
      {
        itemName: "Aurora Travel Kit",
        skuName: "Blue · Set of 2",
        quantity: 1,
        payAmount: "1280.00",
        refundAmount: "0.00",
        rateSnapshot: "0.12",
        commissionAmount: "153.60",
        settlementStatus: "WITHDRAWABLE"
      }
    ]
  },
  {
    orderNo: "ST-20261023-0172",
    payTime: "2026-10-23T11:20:00Z",
    orderStatus: "RECEIVED",
    settlementStatus: "FROZEN",
    payAmount: "860.00",
    refundAmount: "0.00",
    items: [
      {
        itemName: "Luma Desk Lamp",
        skuName: "Warm White",
        quantity: 1,
        payAmount: "860.00",
        refundAmount: "0.00",
        rateSnapshot: "0.15",
        commissionAmount: "129.00",
        settlementStatus: "FROZEN"
      }
    ]
  },
  {
    orderNo: "ST-20261022-0218",
    payTime: "2026-10-22T06:40:00Z",
    orderStatus: "COMPLETED",
    settlementStatus: "WITHDRAWING",
    payAmount: "540.00",
    refundAmount: "0.00",
    items: [
      {
        itemName: "Cloud Cotton Towel",
        skuName: "Ivory · 3 pcs",
        quantity: 1,
        payAmount: "540.00",
        refundAmount: "0.00",
        rateSnapshot: "0.10",
        commissionAmount: "54.00",
        settlementStatus: "WITHDRAWING"
      }
    ]
  },
  {
    orderNo: "ST-20261021-0306",
    payTime: "2026-10-21T15:05:00Z",
    orderStatus: "CLOSED",
    settlementStatus: "INVALID",
    payAmount: "320.00",
    refundAmount: "320.00",
    items: [
      {
        itemName: "Minimal Phone Stand",
        skuName: "Graphite",
        quantity: 1,
        payAmount: "320.00",
        refundAmount: "320.00",
        rateSnapshot: "0.08",
        commissionAmount: "0.00",
        settlementStatus: "INVALID"
      }
    ]
  },
  {
    orderNo: "ST-20261020-0108",
    payTime: "2026-10-20T04:10:00Z",
    orderStatus: "COMPLETED",
    settlementStatus: "WITHDRAWN",
    payAmount: "960.00",
    refundAmount: "0.00",
    items: [
      {
        itemName: "Sora Ceramic Mug",
        skuName: "Sand · 2 pcs",
        quantity: 1,
        payAmount: "960.00",
        refundAmount: "0.00",
        rateSnapshot: "0.12",
        commissionAmount: "115.20",
        settlementStatus: "WITHDRAWN"
      }
    ]
  }
];
const demoFundPage: FundRecordPage = {
  items: [
    {
      id: "w-42",
      type: "COMMISSION",
      appliedAt: "2026-10-23T08:00:00Z",
      applicationNo: "WD-20261023-0042",
      amount: "800.00",
      currency: "CNY",
      maskedRecipient: "PayPal · a***@mail.com",
      receivedAt: null,
      publicStatus: "PENDING_REVIEW",
      sourceId: "42"
    }
  ],
  total: 1,
  page: 1,
  size: 20
};
let demoWithdrawalPage = {
  items: [
    {
      id: "42",
      applicationNo: "WD-20261023-0042",
      amount: "800.00",
      currency: "CNY",
      status: "PENDING_REVIEW",
      createdAt: "2026-10-23T08:00:00Z",
      account: {
        id: "1",
        accountType: "PAYPAL" as const,
        account: "a***@mail.com",
        holderName: "Demo Creator",
        currency: "USD",
        network: null
      }
    }
  ],
  total: 1,
  page: 1,
  size: 20
};

export type SettlementStatus = "WITHDRAWN" | "FROZEN" | "INVALID" | "WITHDRAWING" | "WITHDRAWABLE";

export interface CreatorAnalyticsOverview {
  asOf: string;
  salesCny: string;
  paidOrderCount: number;
  commissionCny: string;
  withdrawnCny: string;
  withdrawingCny: string;
  frozenCny: string;
  daily: { date: string; salesCny: string; commissionCny: string }[];
}

export interface FundSummary {
  totalIncomeCny: string;
  commissionIncomeCny: string;
  estimatedCommissionCny: string;
  totalCommissionCny: string;
  withdrawnCny: string;
  withdrawingCny: string;
  frozenCny: string;
  receivedPrepayCny: string;
  availableCny: string;
  prepayDeductionCny: string;
  prepayOutstandingCny: string;
  asOf: string;
}

export interface FundRecord {
  id: string;
  type: "COMMISSION" | "PREPAY";
  appliedAt: string | null;
  applicationNo: string;
  amount: string;
  currency: string;
  maskedRecipient: string | null;
  receivedAt: string | null;
  publicStatus: string;
  sourceId: string;
}

export interface FundRecordPage {
  items: FundRecord[];
  total: number | string;
  page: number;
  size: number;
}

export interface CreatorOrderLine {
  itemId?: string;
  itemName: string;
  skuName: string | null;
  quantity: number;
  payAmount: string;
  refundAmount: string;
  rateSnapshot?: string | null;
  commissionAmount?: string | null;
  settlementStatus?: SettlementStatus | null;
}

export interface CreatorOrder {
  orderNo: string;
  payTime: string;
  orderStatus: string;
  settlementStatus?: SettlementStatus | null;
  payAmount: string;
  refundAmount: string;
  items: CreatorOrderLine[];
}

export interface CreatorOrderPage {
  items: CreatorOrder[];
  total: number | string;
  page: number;
  size: number;
}

export function getCreatorAnalytics(params: { from: string; to: string }) {
  if (isDemoMode)
    return Promise.resolve({
      asOf: demoFunds.asOf,
      salesCny: "48600.00",
      paidOrderCount: 168,
      commissionCny: "5960.00",
      withdrawnCny: demoFunds.withdrawnCny,
      withdrawingCny: demoFunds.withdrawingCny,
      frozenCny: demoFunds.frozenCny,
      daily: demoDaily
    });
  return http
    .request<
      Omit<CreatorAnalyticsOverview, "paidOrderCount"> & { paidOrderCount: string | number }
    >({
      method: "GET",
      url: "/analytics/overview",
      params
    })
    .then(overview => ({ ...overview, paidOrderCount: Number(overview.paidOrderCount) }));
}

export function getFundSummary() {
  if (isDemoMode) return Promise.resolve({ fundSummary: demoFunds });
  return http
    .request<{
      asOf: string;
      income: {
        estimated: string;
        available: string;
        occupied: string;
        cumulativePaid: string;
        frozen: string;
        deducted: string;
        pendingDeduction: string;
        prepayPaid: string;
        paid: string;
        commissionPaid: string;
      };
    }>({ method: "GET", url: "/commissions/overview" })
    .then(({ income, asOf }) => ({
      fundSummary: {
        totalIncomeCny: income.paid,
        commissionIncomeCny: income.commissionPaid,
        estimatedCommissionCny: income.estimated,
        totalCommissionCny: centsMoney(
          moneyCents(income.commissionPaid) +
            moneyCents(income.occupied) +
            moneyCents(income.frozen)
        ),
        // Owner decision 2026-10-26: commission withdrawals exclude received advances.
        withdrawnCny: income.commissionPaid,
        withdrawingCny: income.occupied,
        frozenCny: income.frozen,
        receivedPrepayCny: income.prepayPaid,
        availableCny: income.available,
        prepayDeductionCny: income.deducted,
        prepayOutstandingCny: income.pendingDeduction,
        asOf
      }
    }));
}

export function getFundRecords(params: { page: number; size: number }) {
  if (isDemoMode) return Promise.resolve({ ...demoFundPage, page: params.page, size: params.size });
  return http.request<FundRecordPage>({ method: "GET", url: "/fund-records", params });
}

export interface WithdrawalAccountOption {
  id: string;
  accountType: "PAYPAL" | "USDT";
  account: string;
  holderName: string | null;
  currency: string | null;
  network: string | null;
}

export interface WithdrawalRecord {
  id: string;
  applicationNo: string;
  amount: string;
  currency: string;
  status: string;
  createdAt: string;
  account: WithdrawalAccountOption | null;
}

/** Task 63 wire fields differ from the portal presentation model. */
interface WithdrawalResponse extends Omit<WithdrawalRecord, "amount" | "account"> {
  amountCny: string;
  account: {
    accountId: string | null;
    type: "PAYPAL" | "USDT";
    account: string;
    holder: string | null;
    currency: string | null;
    network: string | null;
  } | null;
}

function withdrawalRecord(item: WithdrawalResponse): WithdrawalRecord {
  return {
    id: item.id,
    applicationNo: item.applicationNo,
    amount: item.amountCny,
    currency: item.currency,
    status: item.status,
    createdAt: item.createdAt,
    account: item.account
      ? {
          id: item.account.accountId ?? "",
          accountType: item.account.type,
          account: item.account.account,
          holderName: item.account.holder,
          currency: item.account.currency,
          network: item.account.network
        }
      : null
  };
}

export interface WithdrawalConditions {
  currency: string;
  minimum: string;
  version: string;
  available: string;
  occupied: string;
  frozen: string;
  estimated: string;
  withdrawalFrozen: boolean;
  canApply: boolean;
  unverifiedCount: number;
}

export function getWithdrawalConditions(): Promise<WithdrawalConditions> {
  if (isDemoMode)
    return Promise.resolve({
      currency: "CNY",
      minimum: "200.00",
      version: "1",
      available: demoFunds.availableCny,
      occupied: demoFunds.withdrawingCny,
      frozen: demoFunds.frozenCny,
      estimated: demoFunds.estimatedCommissionCny,
      withdrawalFrozen: false,
      canApply: true,
      unverifiedCount: 0
    });
  return http
    .request<Omit<WithdrawalConditions, "unverifiedCount"> & { unverifiedCount: string | number }>({
      method: "GET",
      url: "/withdrawals/conditions"
    })
    .then(conditions => ({ ...conditions, unverifiedCount: Number(conditions.unverifiedCount) }));
}

export function getWithdrawalAccounts() {
  if (isDemoMode)
    return Promise.resolve([
      {
        id: "1",
        accountType: "PAYPAL" as const,
        account: "a***@mail.com",
        holderName: "Demo Creator",
        currency: "USD",
        network: null
      }
    ]);
  return http
    .request<
      {
        id: string;
        type: "PAYPAL" | "USDT";
        account: string;
        holder: string | null;
        currency: string | null;
        network: string | null;
      }[]
    >({
      method: "GET",
      url: "/withdrawals/recipient-options"
    })
    .then(rows =>
      rows.map(row => ({
        id: row.id,
        accountType: row.type,
        account: row.account,
        holderName: row.holder,
        currency: row.currency,
        network: row.network
      }))
    );
}

export function getWithdrawals(params: { page: number; size: number }) {
  if (isDemoMode)
    return Promise.resolve({ ...demoWithdrawalPage, page: params.page, size: params.size });
  return http
    .request<{
      items: WithdrawalResponse[];
      total: number | string;
      page: number;
      size: number;
    }>({ method: "GET", url: "/withdrawals", params })
    .then(page => ({
      ...page,
      items: page.items.map(withdrawalRecord)
    }));
}

export function submitWithdrawal(data: {
  amount: string;
  payoutAccountId?: string;
  account?: {
    type: "PAYPAL" | "USDT";
    account: string;
    currency?: string;
    network?: string;
    holder: string;
    qrFileId?: string;
  };
  note: string;
  previousId?: string;
  idempotencyKey: string;
}) {
  if (isDemoMode) {
    const record = {
      id: `demo-${Date.now()}`,
      applicationNo: "WD-DEMO-0001",
      amount: data.amount,
      currency: "CNY",
      status: "PENDING_REVIEW",
      createdAt: new Date().toISOString(),
      account: {
        id: data.payoutAccountId ?? "manual",
        accountType: "PAYPAL" as const,
        account: "a***@mail.com",
        holderName: "Demo Creator",
        currency: "USD",
        network: null
      }
    };
    demoWithdrawalPage = {
      ...demoWithdrawalPage,
      items: [record, ...demoWithdrawalPage.items],
      total: Number(demoWithdrawalPage.total) + 1
    };
    return Promise.resolve(record);
  }
  return http
    .request<WithdrawalResponse>({ method: "POST", url: "/withdrawals", data })
    .then(withdrawalRecord);
}

export function withdrawWithdrawal(id: string) {
  if (isDemoMode) {
    demoWithdrawalPage = {
      ...demoWithdrawalPage,
      items: demoWithdrawalPage.items.map(item =>
        item.id === id ? { ...item, status: "WITHDRAWN" } : item
      )
    };
    return Promise.resolve();
  }
  return http.request({ method: "PUT", url: `/withdrawals/${id}/withdraw`, data: {} });
}

export interface CreatorOrderQuery {
  payFrom: string;
  payTo: string;
  orderNo?: string;
  productName?: string;
  settlementStatus?: SettlementStatus;
  page: number;
  size: number;
}

export function getCreatorOrders(params: CreatorOrderQuery) {
  if (isDemoMode) {
    const items = demoOrders.filter(
      order =>
        (!params.orderNo || order.orderNo.toLowerCase().includes(params.orderNo.toLowerCase())) &&
        (!params.productName ||
          order.items.some(item =>
            item.itemName.toLowerCase().includes(params.productName!.toLowerCase())
          )) &&
        (!params.settlementStatus || order.settlementStatus === params.settlementStatus)
    );
    const start = (params.page - 1) * params.size;
    return Promise.resolve({
      items: items.slice(start, start + params.size),
      total: items.length,
      page: params.page,
      size: params.size
    });
  }
  return http.request<CreatorOrderPage>({ method: "GET", url: "/orders", params });
}
