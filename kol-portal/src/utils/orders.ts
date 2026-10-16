export type OrderRangeDays = 7 | 30 | 90;

function localDateString(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return year + "-" + month + "-" + day;
}

export function recentLocalDateRange(days: OrderRangeDays, now = new Date()): [string, string] {
  const from = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  from.setDate(from.getDate() - days + 1);
  const through = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return [localDateString(from), localDateString(through)];
}

/** Treats the chosen local dates as inclusive and returns a UTC half-open interval. */
export function localDateRangeToUtc(fromDate: string, throughDate: string): [string, string] {
  const [fromYear, fromMonth, fromDay] = fromDate.split("-").map(Number);
  const [toYear, toMonth, toDay] = throughDate.split("-").map(Number);
  const from = new Date(fromYear, fromMonth - 1, fromDay);
  const to = new Date(toYear, toMonth - 1, toDay);
  if (
    Number.isNaN(from.getTime()) ||
    Number.isNaN(to.getTime()) ||
    fromDate.length !== 10 ||
    throughDate.length !== 10 ||
    from > to
  ) {
    throw new RangeError("Invalid order date range");
  }
  to.setDate(to.getDate() + 1);
  return [from.toISOString(), to.toISOString()];
}

export function formatMoney(amount: string, locale: string): string {
  const formatted = Number(amount).toLocaleString(locale, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
  return "¥" + formatted;
}
