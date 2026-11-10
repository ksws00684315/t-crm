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
    "paidOrderCount",
    "units",
    "refundUnits",
    "netUnits",
    "salesAmount",
    "completedRefundAmount",
    "netSalesAmount",
    "refundedOrderCount",
    "periodRefundedOrderRate"
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
export const coverageKeys = ["spuCount", "skuCount", "kolCount"];
export const sortable = new Set([
  ...trade,
  "spuCount",
  "kolCount",
  "rank",

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

export const periodModes = ["live", "sevenDays", "thirtyDays", "day", "month", "custom"] as const;
export type PeriodMode = (typeof periodModes)[number];
type DateRange = Pick<Filters, "startDate" | "endDate">;

export function shiftDate(day: string, offset: number): string {
  return new Date(Date.parse(day) + offset * DAY).toISOString().slice(0, 10);
}

export function monthRange(month: string, today: string): DateRange {
  const startDate = `${month}-01`;
  const end = new Date(`${startDate}T00:00:00Z`);
  end.setUTCMonth(end.getUTCMonth() + 1, 0);
  return { startDate, endDate: [end.toISOString().slice(0, 10), today].sort()[0]! };
}

export function periodRange(mode: Exclude<PeriodMode, "custom">, now = new Date()): DateRange {
  if (mode === "sevenDays") return dates(7, now);
  if (mode === "thirtyDays") return dates(30, now);
  const day = dates(1, now);
  return mode === "month" ? monthRange(day.endDate.slice(0, 7), day.endDate) : day;
}

export function adjacentPeriod(
  mode: PeriodMode,
  range: DateRange,
  direction: -1 | 1,
  today: string
): DateRange | undefined {
  if (mode === "live") return undefined;
  if (mode === "month") {
    const start = new Date(`${range.startDate}T00:00:00Z`);
    start.setUTCMonth(start.getUTCMonth() + direction, 1);
    const month = start.toISOString().slice(0, 7);
    return month <= today.slice(0, 7) ? monthRange(month, today) : undefined;
  }
  const days = (Date.parse(range.endDate) - Date.parse(range.startDate)) / DAY + 1;
  const endDate = shiftDate(range.endDate, days * direction);
  if (endDate > today) return undefined;
  return { startDate: shiftDate(endDate, 1 - days), endDate };
}

export function validDates(filters: Filters): boolean {
  const start = Date.parse(filters.startDate);
  const end = Date.parse(filters.endDate);
  return Number.isFinite(start) && Number.isFinite(end) && end >= start && end - start < 90 * DAY;
}
