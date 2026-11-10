import type { Board, Filters } from "@/api/analytics";

export const boards: Record<string, Board> = {
  AnalyticsShopPerformance: "shop-performance",
  AnalyticsBestSellers: "best-sellers",
  AnalyticsKolRanking: "kol-ranking",
  AnalyticsInviterPerformance: "inviter-performance"
};

const trade = ["paidOrderCount", "units", "salesAmount", "completedRefundAmount", "netSalesAmount"];
const risk = ["averageOrderAmount", "amountRefundRate", "periodRefundedOrderRate", "netCommission"];
export const columnsByBoard: Record<Board, string[]> = {
  "shop-performance": [
    "identity",
    "channelStatus",
    "paidOrderCount",
    "units",
    "coverage",
    "salesAmount",
    "completedRefundAmount",
    "netSalesAmount",
    "averageOrderAmount"
  ],
  "best-sellers": [
    "rank",
    "identity",
    "channelCode",
    "shop",
    "category",
    "paidOrderCount",
    "units",
    "refundUnits",
    "netUnits",
    "salesAmount",
    "completedRefundAmount",
    "netSalesAmount",
    "refundedOrderCount",
    "periodRefundedOrderRate",
    "kolCount",
    "rankChange",
    "trend"
  ],
  "kol-ranking": [
    "identity",
    "relationship",
    ...trade.slice(0, 2),
    "spuCount",
    ...trade.slice(2),
    ...risk,
    "topProducts"
  ],
  "inviter-performance": [
    "identity",
    "status",
    "invitedCount",
    "effectiveCount",
    "kolCount",
    ...trade,
    ...risk,
    "bestCreator"
  ]
};
export const groupedSort = ["spuCount", "skuCount", "kolCount"];
export const sortable = new Set([
  ...trade,
  ...groupedSort,
  "rank",
  "rankChange",
  "invitedCount",
  "effectiveCount",
  "refundedOrderCount",
  ...risk.slice(0, 3)
]);
export const money = new Set([
  "salesAmount",
  "cohortRefundAmount",
  "completedRefundAmount",
  "netSalesAmount",
  "averageOrderAmount",
  "netCommission",
  "settlement"
]);
export const rates = new Set(["amountRefundRate", "periodRefundedOrderRate"]);
export const pending = new Set(["refundUnits", "netUnits", "netCommission"]);

const DAY = 86_400_000;
export function dates(days = 7, now = new Date()): Pick<Filters, "startDate" | "endDate"> {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(now);
  const field = (name: string) => parts.find(part => part.type === name)?.value;
  const endDate = `${field("year")}-${field("month")}-${field("day")}`;
  const startDate = new Date(Date.parse(endDate) - (days - 1) * DAY).toISOString().slice(0, 10);
  return { startDate, endDate };
}

export function datePreset(preset: "today" | "yesterday" | "month", now = new Date()) {
  const today = dates(1, now).endDate;
  if (preset === "today") return { startDate: today, endDate: today };
  if (preset === "month") return { startDate: `${today.slice(0, 7)}-01`, endDate: today };
  const yesterday = new Date(Date.parse(today) - DAY).toISOString().slice(0, 10);
  return { startDate: yesterday, endDate: yesterday };
}

export function validDates(filters: Filters): boolean {
  const start = Date.parse(filters.startDate);
  const end = Date.parse(filters.endDate);
  return Number.isFinite(start) && Number.isFinite(end) && end >= start && end - start < 90 * DAY;
}
