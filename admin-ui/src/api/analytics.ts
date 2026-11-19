import { http } from "@/api/http";
import type { DisplayRelationStatus } from "@/api/types";

export type Board = "shop-performance" | "best-sellers" | "kol-ranking" | "inviter-performance";
export type Scalar = number | string;
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
  channel?: "WEIDIAN";
  shopId?: string;
  kolId?: string;
  inviterId?: string;
  keyword?: string;
  creatorKeyword?: string;
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
  };
}
export interface Meta {
  period: Pick<Filters, "startDate" | "endDate">;
  previousPeriod: Pick<Filters, "startDate" | "endDate">;
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
  imageUrl?: string;
  bestCreator?: { id: string; name: string | null; netSalesAmount: Scalar; code?: string | null };
  metrics?: Metrics;
  [key: string]: unknown;
}
export interface AnalyticsResult {
  meta: Meta;
  summary: Summary;
  previous: Summary | Metrics;
  change: Metrics;
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
export interface EvidenceRow {
  id: string;
  orderNo: string;
  refundNo?: string;
  product?: string;
  sku?: string;
  units?: number;
  amount: Scalar;
  refundAmount?: Scalar;
  netTransactionAmount?: Scalar;
  eventTime: string;
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
interface DetailResult {
  daily: DailyAmount[] | null;
  activity: { items: EvidenceRow[]; total: Scalar; page: number; size: number } | null;
  shopOverview?: ShopDetailOverview | null;
  productOrderCounts?: { paidOrders: Scalar; refundedOrders: Scalar } | null;
}
export function queryAnalyticsDetail(
  board: Board,
  params: DetailQuery & { kind?: ActivityKind; page?: number; size?: number }
) {
  return http.request<DetailResult>({ method: "get", url: `/stats/${board}/details`, params });
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
  params: DetailQuery & { group: ProductBreakdown; page: number; size: number }
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
  transferred: boolean;
  currentInviter: string | null;
}

export function queryInviterCreators(
  params: DetailQuery & { page: number; size: number; currentTeam: boolean }
) {
  return http.request<{ items: InviterCreatorRow[]; total: Scalar; page: number; size: number }>({
    method: "get",
    url: "/stats/inviter-performance/creators",
    params
  });
}
export function queryAnalytics(board: Board, params: RankingQuery) {
  return http.request<AnalyticsResult>({ method: "get", url: `/stats/${board}`, params });
}
export function analyticsOptions() {
  return http.request<Options>({ method: "get", url: "/stats/options" });
}
export function exportAnalytics(board: Board, params: RankingQuery) {
  return http.download({ method: "get", url: `/stats/${board}/exports`, params });
}
