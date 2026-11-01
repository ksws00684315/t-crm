import type { CreatorAnalyticsOverview } from "@/api/creator";
import { centsMoney, moneyCents } from "@/utils/money";

/**
 * Sales trend windows and series (task 92). Every boundary is a Beijing-time calendar date (UTC+8)
 * sent to `/kol-api/analytics/overview` as `+08:00`; the API only returns days that have orders,
 * so the gaps are filled with zero before the series is grouped by day or month. Amounts are
 * summed in integer cents.
 */
export type TrendMode = "last7" | "last30" | "month" | "range";
export type TrendGranularity = "day" | "month";

/** Server limit of the overview (`KolAnalyticsService`): at most 366 days per request. */
export const MAX_RANGE_DAYS = 366;
/** A custom range up to this many days is shown per day, a longer one per month. */
export const DAILY_RANGE_DAYS = 30;

const DAY_MS = 86_400_000;
const BEIJING_OFFSET_MS = 8 * 3_600_000;

export interface TrendWindow {
  /** First day, inclusive, `YYYY-MM-DD` Beijing time. */
  fromDate: string;
  /** Last day, inclusive. */
  throughDate: string;
  granularity: TrendGranularity;
}

export interface TrendPoint {
  /** `YYYY-MM-DD` per day, `YYYY-MM` per month. */
  key: string;
  salesCny: string;
  commissionCny: string;
  orderCount: number;
}

export interface TrendSeries {
  points: TrendPoint[];
  salesTotal: string;
  commissionTotal: string;
  orderTotal: number;
}

export type TrendWindowError = "invalid" | "tooLong";

function parseDate(date: string): number {
  const [year, month, day] = date.split("-").map(Number);
  return Date.UTC(year, month - 1, day);
}

function formatDate(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

export function addDays(date: string, days: number): string {
  return formatDate(parseDate(date) + days * DAY_MS);
}

/** Calendar date in Beijing time for an instant. */
export function beijingDate(now: Date): string {
  return formatDate(now.getTime() + BEIJING_OFFSET_MS);
}

/** Number of calendar days from `fromDate` through `throughDate`, both inclusive. */
export function daySpan(fromDate: string, throughDate: string): number {
  return Math.round((parseDate(throughDate) - parseDate(fromDate)) / DAY_MS) + 1;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const MONTH = /^\d{4}-\d{2}$/;

/** Last day of a `YYYY-MM` month. */
export function lastDayOfMonth(month: string): string {
  const [year, number] = month.split("-").map(Number);
  return formatDate(Date.UTC(year, number, 0));
}

/**
 * The window of a filter choice. "Last N days" includes today; a natural month runs day by day
 * and stops at today when it is the current month; a custom range is per day up to 30 days and
 * per month beyond. A range longer than 366 days or reversed is rejected.
 */
export function trendWindow(
  mode: TrendMode,
  now: Date,
  options: { month?: string; range?: [string, string] | null } = {}
): TrendWindow | TrendWindowError {
  const today = beijingDate(now);
  if (mode === "last7" || mode === "last30") {
    const days = mode === "last7" ? 7 : 30;
    return { fromDate: addDays(today, 1 - days), throughDate: today, granularity: "day" };
  }
  if (mode === "month") {
    const month = options.month;
    if (!month || !MONTH.test(month)) return "invalid";
    const fromDate = `${month}-01`;
    if (fromDate > today) return "invalid";
    const last = lastDayOfMonth(month);
    return {
      fromDate,
      throughDate: last > today ? today : last,
      granularity: "day"
    };
  }
  const range = options.range;
  if (!range || !DATE.test(range[0]) || !DATE.test(range[1]) || range[0] > range[1]) {
    return "invalid";
  }
  const span = daySpan(range[0], range[1]);
  if (span > MAX_RANGE_DAYS) return "tooLong";
  return {
    fromDate: range[0],
    throughDate: range[1],
    granularity: span > DAILY_RANGE_DAYS ? "month" : "day"
  };
}

/** `from` inclusive, `to` exclusive, both at Beijing midnight. */
export function trendApiParams(window: TrendWindow): { from: string; to: string } {
  return {
    from: `${window.fromDate}T00:00:00+08:00`,
    to: `${addDays(window.throughDate, 1)}T00:00:00+08:00`
  };
}

/** Fills the missing days with zero and groups the result by day or month. */
export function buildTrend(
  daily: CreatorAnalyticsOverview["daily"],
  window: TrendWindow
): TrendSeries {
  const byDay = new Map(daily.map(day => [day.date, day]));
  const buckets = new Map<string, { sales: bigint; commission: bigint; orders: number }>();
  const span = daySpan(window.fromDate, window.throughDate);
  for (let offset = 0; offset < span; offset += 1) {
    const date = addDays(window.fromDate, offset);
    const key = window.granularity === "month" ? date.slice(0, 7) : date;
    const bucket = buckets.get(key) ?? { sales: 0n, commission: 0n, orders: 0 };
    const day = byDay.get(date);
    if (day) {
      bucket.sales += moneyCents(day.salesCny);
      bucket.commission += moneyCents(day.commissionCny);
      bucket.orders += day.orderCount;
    }
    buckets.set(key, bucket);
  }
  let sales = 0n;
  let commission = 0n;
  let orders = 0;
  const points: TrendPoint[] = [];
  for (const [key, bucket] of buckets) {
    sales += bucket.sales;
    commission += bucket.commission;
    orders += bucket.orders;
    points.push({
      key,
      salesCny: centsMoney(bucket.sales),
      commissionCny: centsMoney(bucket.commission),
      orderCount: bucket.orders
    });
  }
  return {
    points,
    salesTotal: centsMoney(sales),
    commissionTotal: centsMoney(commission),
    orderTotal: orders
  };
}
