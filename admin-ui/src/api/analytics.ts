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
  sellingCount?: Scalar;
  activeCount?: Scalar;
  invitedCount?: Scalar;
  effectiveCount?: Scalar;
  sellingCreatorCount?: Scalar;
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
  status?: number;
  name?: string;
  code?: string;
  sourceItemId?: string;
  related?: string;
  enabled?: boolean;
  social?: string[];
  primaryPlatform?: string;
  relationStatus?: DisplayRelationStatus;
  linkCount?: Scalar;
  commissionRate?: Scalar;
  imageUrl?: string;
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
  eventTime: string;
}
export interface DailyAmount {
  day: string;
  sales: Scalar;
  refunds: Scalar;
}
interface DetailResult {
  daily: DailyAmount[] | null;
  activity: { items: EvidenceRow[]; total: Scalar; page: number; size: number } | null;
}
export function queryAnalyticsDetail(
  board: Board,
  params: DetailQuery & { kind?: ActivityKind; page?: number; size?: number }
) {
  return http.request<DetailResult>({ method: "get", url: `/stats/${board}/details`, params });
}
export type ProductBreakdown = "SKUS" | "CREATORS";
export interface ProductGroup {
  code: string;
  name?: string;
  paidOrderCount: Scalar;
  units: Scalar;
  salesAmount: Scalar;
  completedRefundAmount: Scalar;
  netSalesAmount: Scalar;
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
