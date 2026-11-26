import { http } from "@/api/http";
import type { DisplayRelationStatus } from "@/api/types";

export type Board = "shop-performance" | "best-sellers" | "kol-ranking" | "inviter-performance";
export type Scalar = number | string;
export type CreatorIncomeStatus = "PROMOTING" | "INVALID";
export type Metric =
  | "paidOrderCount"
  | "units"
  | "salesAmount"
  | "cohortRefundAmount"
  | "completedRefundAmount"
  | "netSalesAmount"
  | "refundedOrderCount"
  | "averageOrderAmount"
  | "amountRefundRate"
  | "periodRefundedOrderRate";
export type Metrics = Record<Metric, Scalar | null>;
export interface Filters {
  startDate: string;
  endDate: string;
  cumulative?: boolean;
  channel?: "WEIDIAN";
  shopId?: string;
  kolId?: string;
  inviterId?: string;
  keyword?: string;
  creatorKeyword?: string;
  creatorStatus?: CreatorIncomeStatus;
  productKeyword?: string;
  inviterKeyword?: string;
  affiliateOnly?: boolean;
}
export interface RankingQuery extends Filters {
  page: number;
  size: number;
  sortBy: string;
  sortDirection: "asc" | "desc";
}
export interface Option {
  id: string;
  name: string;
  code?: string;
}
export interface Options {
  shops: Option[];
  creators: Option[];
  inviters: Option[];
}
export interface Summary {
  metrics: Metrics;
  sellingShopCount?: Scalar;
  shopCount?: Scalar;
  spuCount?: Scalar;
  settlement?: { increase: Scalar; decrease: Scalar; net: Scalar } | null;
  sellingCount?: Scalar;
  totalSpuCount?: Scalar;
  activeCount?: Scalar;
  invitedCount?: Scalar;
  effectiveCount?: Scalar;
  sellingCreatorCount?: Scalar;
  inviterCards?: {
    inviterCount: Scalar;
    newInvitations: Scalar;
    pendingCreators: Scalar;
    totalCommission: Scalar | null;
    estimatedCommission: Scalar | null;
    actualCommission: Scalar | null;
  };
  creatorCards?: {
    periodEffectiveCount: Scalar | null;
    relationshipCount: Scalar;
    spuCount: Scalar;
    totalCommission: Scalar | null;
    commissionBeforeClawback: Scalar | null;
    commissionClawback: Scalar | null;
    expenseThrough?: string | null;
    paidCommission?: Scalar | null;
    paidPrepay?: Scalar | null;
    cumulativeExpense?: Scalar | null;
  };
}
export interface Meta {
  period: Pick<Filters, "startDate" | "endDate" | "cumulative">;
  previousPeriod: Pick<Filters, "startDate" | "endDate"> | null;
  timezone: string;
  filters: Omit<Filters, "startDate" | "endDate">;
  dataScope: "ALL" | "OWN";
  updatedAt: string | null;
}
export interface RankRow {
  id?: string;
  shopId?: string;
  shopName?: string;
  shopCode?: string;
  weidianShopId?: string;
  shopUpdatedAt?: string;
  refundedOrderCount?: Scalar;
  effectiveCount?: Scalar | null;
  status?: number;
  name?: string;
  code?: string;
  sourceItemId?: string;
  onSaleShopCount?: Scalar;
  linkedCreatorCount?: Scalar;
  related?: string;
  enabled?: boolean;
  social?: string[];
  primaryPlatform?: string;
  relationStatus?: DisplayRelationStatus;
  linkCount?: Scalar;
  commissionRate?: Scalar;
  creatorMoney?: Record<string, Scalar | null> | null;
  prepayExpense?: Scalar | null;
  imageUrl?: string;
  bestCreator?: { id: string; name: string | null; netSalesAmount: Scalar; code?: string | null };
  metrics?: Metrics;
  [key: string]: unknown;
}
export interface AnalyticsResult {
  meta: Meta;
  summary: Summary;
  previous: Summary | Metrics | null;
  change: Metrics | null;
  ranking: { items: RankRow[]; total: Scalar; page: number; size: number };
  unavailable: { field: string; reason: string }[];
  sortBy: string;
  sortDirection: "asc" | "desc";
  keyword?: string;
}
export interface DetailQuery extends Filters {
  entityId?: string;
  productCode?: string;
}
export type ActivityKind = "ORDERS" | "REFUNDS";
export type DetailSort =
  "PAID_ORDER_COUNT" | "UNITS" | "SALES_AMOUNT" | "REFUND_AMOUNT" | "NET_SALES_AMOUNT";
export interface DetailSortQuery {
  sortBy: DetailSort;
  sortDirection: "asc" | "desc";
}
export interface EvidenceRow {
  id: string;
  orderNo: string;
  shopId?: string;
  refundNo?: string;
  product?: string;
  sku?: string;
  units?: number;
  amount: Scalar;
  refundAmount?: Scalar;
  netTransactionAmount?: Scalar;
  eventTime: string;
  orderPaidAt?: string | null;
  creatorName?: string;
  creatorCode?: string | null;
  spuId?: string;
  transactionItemId?: string;
  shopName?: string;
  skuId?: string;
  soldUnits?: Scalar;
  salesAmount?: Scalar;
  imageUrl?: string;
}
export interface SettlementFlowRow {
  id: string;
  orderNo: string;
  product?: string;
  sku?: string;
  imageUrl?: string;
  itemCount?: number;
  amountType: "INCREASE" | "REFUND";
  spuId?: string | null;
  amount: Scalar | null;
  eventTime: string;
}
export type CreatorIncomeOrder = Pick<
  EvidenceRow,
  | "id"
  | "orderNo"
  | "shopId"
  | "shopName"
  | "eventTime"
  | "amount"
  | "refundAmount"
  | "netTransactionAmount"
> & {
  estimatedCommission: Scalar | null;
  orderStatus: string | null;
  commissionStatus: "FROZEN" | "AVAILABLE" | "WITHDRAWN" | null;
};

export function queryCreatorIncomeOrders(
  params: DetailQuery & Partial<DetailSortQuery> & { page: number; size: number }
) {
  return http.request<{ items: CreatorIncomeOrder[]; total: Scalar; page: number; size: number }>({
    method: "get",
    url: "/stats/creator-income/orders",
    params
  });
}

export interface CreatorFundApplication {
  id: string;
  fundType: "COMMISSION" | "PREPAY";
  amount: string;
  status: "PENDING_REVIEW" | "PENDING_PAYMENT" | "PAID";
  applicationNo: string;
}

export function queryCreatorFundApplications(
  params: Pick<DetailQuery, "startDate" | "endDate" | "cumulative" | "shopId" | "entityId"> & {
    page: number;
    size: number;
  }
) {
  return http.request<{
    items: CreatorFundApplication[];
    total: Scalar;
    page: number;
    size: number;
  }>({
    method: "get",
    url: "/stats/creator-income/fund-applications",
    params
  });
}

export function queryShopSettlementFlows(params: DetailQuery & { page: number; size: number }) {
  return http.request<{ items: SettlementFlowRow[]; total: Scalar; page: number; size: number }>({
    method: "get",
    url: "/stats/shop-performance/settlement-flows",
    params
  });
}
export interface DailyAmount {
  day: string;
  sales: Scalar;
  refunds: Scalar;
}
export interface ShopDetailOverview {
  settlementAmount: Scalar | null;
  creatorCounts: { selling: Scalar; total: Scalar };
}
interface OrderCounts {
  paidOrders: Scalar;
  refundedOrders: Scalar;
}
export interface CreatorDetailOverview {
  settlementAmount: Scalar | null;
  totalCommission: Scalar | null;
  orderCounts: OrderCounts | null;
}
export interface InviterDetailOverview {
  settlementAmount: Scalar | null;
  orderCounts: OrderCounts | null;
}
export interface CreatorIncomeOverview {
  metrics: Metrics;
  dailyNetSales: { day: string; netSales: Scalar }[];
  through: string;
  cumulativeSales: Scalar;
  cumulativeRefunds: Scalar;
  cumulativeNetSales: Scalar;
  totalCommission: Scalar | null;
  funds: { frozen: Scalar; available: Scalar; withdrawn: Scalar; prepaid: Scalar | null } | null;
}
interface DetailResult {
  daily: DailyAmount[] | null;
  activity: { items: EvidenceRow[]; total: Scalar; page: number; size: number } | null;
  shopOverview?: ShopDetailOverview | null;
  productOrderCounts?: OrderCounts | null;
  creatorOverview?: CreatorDetailOverview | null;
  inviterOverview?: InviterDetailOverview | null;
  incomeOverview?: CreatorIncomeOverview | null;
}
export function queryAnalyticsDetail(
  board: Board,
  params: DetailQuery &
    Partial<DetailSortQuery> & { kind?: ActivityKind; page?: number; size?: number },
  income = false
) {
  return http.request<DetailResult>({
    method: "get",
    url: `/stats/${income ? "creator-income" : board}/details`,
    params
  });
}
export type ProductBreakdown = "SKUS" | "CREATORS" | "SHOPS";
export interface ProductGroup {
  code: string;
  name?: string;
  paidOrderCount: Scalar;
  units: Scalar;
  salesAmount: Scalar;
  completedRefundAmount: Scalar;
  netSalesAmount: Scalar;
  creatorCode?: string;
  inviterName?: string;
  skuId?: string;
  imageUrl?: string;
}
export function queryProductBreakdown(
  params: DetailQuery &
    Partial<DetailSortQuery> & { group: ProductBreakdown; page: number; size: number }
) {
  return http.request<{ items: ProductGroup[]; total: Scalar }>({
    method: "get",
    url: "/stats/best-sellers/breakdowns",
    params
  });
}
export interface InviterCreatorRow {
  id: string;
  name: string | null;
  code: string | null;
  enabled: boolean | null;
  metrics: Metrics;
  commissionRate?: Scalar | null;
  totalEstimatedCommission?: Scalar | null;
}

export function queryInviterCreators(
  params: DetailQuery &
    Partial<DetailSortQuery> & { page: number; size: number; currentTeam: boolean }
) {
  return http.request<{ items: InviterCreatorRow[]; total: Scalar; page: number; size: number }>({
    method: "get",
    url: "/stats/inviter-performance/creators",
    params
  });
}
export function queryAnalytics(board: Board, params: RankingQuery, income = false) {
  return http.request<AnalyticsResult>({
    method: "get",
    url: `/stats/${income ? "creator-income" : board}`,
    params
  });
}
export function analyticsOptions(income = false) {
  return http.request<Options>({
    method: "get",
    url: income ? "/stats/creator-income/options" : "/stats/options"
  });
}
export function exportAnalytics(board: Board, params: RankingQuery) {
  return http.download({ method: "get", url: `/stats/${board}/exports`, params });
}
