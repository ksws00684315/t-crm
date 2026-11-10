import { http } from "@/api/http";

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
  related?: string;
  enabled?: boolean;
  social?: string[];
  imageUrl?: string;
  category?: string;
  highlights?: string[];
  trend?: { day: string; netSalesAmount: Scalar }[];
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
export function queryAnalytics(board: Board, params: RankingQuery) {
  return http.request<AnalyticsResult>({ method: "get", url: `/stats/${board}`, params });
}
export function analyticsOptions() {
  return http.request<Options>({ method: "get", url: "/stats/options" });
}
export function exportAnalytics(board: Board, params: RankingQuery) {
  return http.download({ method: "get", url: `/stats/${board}/exports`, params });
}
