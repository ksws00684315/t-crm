import { describe, expect, it } from "vitest";
import {
  addDays,
  beijingDate,
  buildTrend,
  daySpan,
  trendApiParams,
  trendWindow,
  type TrendWindow
} from "../sales-trend";

const day = (date: string, sales: string, commission: string, orderCount: number) => ({
  date,
  salesCny: sales,
  commissionCny: commission,
  orderCount
});
// 2026-11-01 02:00 UTC is already 10:00 on 2026-11-01 in Beijing; 17:00 UTC on 10-31 is 01:00 on 11-01.
const NOW = new Date("2026-10-31T17:00:00Z");

describe("Beijing dates", () => {
  it("takes the calendar date at +08:00, not the local or UTC one", () => {
    expect(beijingDate(new Date("2026-10-31T17:00:00Z"))).toBe("2026-11-01");
    expect(beijingDate(new Date("2026-10-31T15:59:59Z"))).toBe("2026-10-31");
  });
  it("adds days across month and year ends and counts inclusive spans", () => {
    expect(addDays("2026-12-31", 1)).toBe("2027-01-01");
    expect(addDays("2026-03-01", -1)).toBe("2026-02-28");
    expect(daySpan("2026-10-01", "2026-10-01")).toBe(1);
    expect(daySpan("2026-10-01", "2026-10-31")).toBe(31);
  });
});

describe("trend windows", () => {
  it("includes today in the last 7 and 30 days, per day", () => {
    expect(trendWindow("last7", NOW)).toEqual({
      fromDate: "2026-10-26",
      throughDate: "2026-11-01",
      granularity: "day"
    });
    expect(trendWindow("last30", NOW)).toEqual({
      fromDate: "2026-10-03",
      throughDate: "2026-11-01",
      granularity: "day"
    });
  });

  it("sends Beijing midnight boundaries with an exclusive end", () => {
    const window = trendWindow("last7", NOW) as TrendWindow;
    expect(trendApiParams(window)).toEqual({
      from: "2026-10-26T00:00:00+08:00",
      to: "2026-11-02T00:00:00+08:00"
    });
  });

  it("runs a 31-day natural month per day", () => {
    expect(trendWindow("month", NOW, { month: "2026-10" })).toEqual({
      fromDate: "2026-10-01",
      throughDate: "2026-10-31",
      granularity: "day"
    });
  });

  it("stops the current month at today and rejects a future month", () => {
    expect(trendWindow("month", NOW, { month: "2026-11" })).toEqual({
      fromDate: "2026-11-01",
      throughDate: "2026-11-01",
      granularity: "day"
    });
    expect(trendWindow("month", NOW, { month: "2026-12" })).toBe("invalid");
    expect(trendWindow("month", NOW, {})).toBe("invalid");
  });

  it("groups a range of up to 30 days per day and a longer one per month", () => {
    expect(trendWindow("range", NOW, { range: ["2026-10-01", "2026-10-30"] })).toMatchObject({
      granularity: "day"
    });
    // A 31-day range is longer than 30 days, so it is grouped per month.
    expect(trendWindow("range", NOW, { range: ["2026-10-01", "2026-10-31"] })).toMatchObject({
      granularity: "month"
    });
  });

  it("allows at most 366 days and never builds a request beyond it", () => {
    expect(trendWindow("range", NOW, { range: ["2025-11-01", "2026-10-31"] })).toMatchObject({
      granularity: "month"
    });
    expect(daySpan("2025-10-31", "2026-10-31")).toBe(366);
    expect(trendWindow("range", NOW, { range: ["2025-10-31", "2026-10-31"] })).toMatchObject({
      granularity: "month"
    });
    expect(trendWindow("range", NOW, { range: ["2025-10-30", "2026-10-31"] })).toBe("tooLong");
  });

  it("rejects incomplete or reversed ranges", () => {
    expect(trendWindow("range", NOW, { range: null })).toBe("invalid");
    expect(trendWindow("range", NOW, { range: ["2026-10-05", "2026-10-01"] })).toBe("invalid");
  });
});

describe("trend series", () => {
  it("fills days without orders with zero and keeps the order", () => {
    const window = trendWindow("range", NOW, {
      range: ["2026-10-01", "2026-10-05"]
    }) as TrendWindow;
    const series = buildTrend(
      [day("2026-10-02", "100.10", "10.01", 2), day("2026-10-05", "0.20", "0.02", 1)],
      window
    );
    expect(series.points.map(point => point.key)).toEqual([
      "2026-10-01",
      "2026-10-02",
      "2026-10-03",
      "2026-10-04",
      "2026-10-05"
    ]);
    expect(series.points.map(point => point.salesCny)).toEqual([
      "0.00",
      "100.10",
      "0.00",
      "0.00",
      "0.20"
    ]);
    expect(series.points[0].orderCount).toBe(0);
    expect(series.points[1].orderCount).toBe(2);
    expect(series.salesTotal).toBe("100.30");
    expect(series.commissionTotal).toBe("10.03");
    expect(series.orderTotal).toBe(3);
  });

  it("returns 31 daily points for a 31-day month", () => {
    const window = trendWindow("month", NOW, { month: "2026-10" }) as TrendWindow;
    expect(buildTrend([], window).points).toHaveLength(31);
  });

  it("sums cents exactly instead of accumulating floating point errors", () => {
    const window = trendWindow("range", NOW, {
      range: ["2026-10-01", "2026-10-03"]
    }) as TrendWindow;
    const series = buildTrend(
      [
        day("2026-10-01", "0.10", "0.10", 1),
        day("2026-10-02", "0.20", "0.20", 1),
        day("2026-10-03", "0.30", "0.30", 1)
      ],
      window
    );
    expect(series.salesTotal).toBe("0.60");
    expect(series.commissionTotal).toBe("0.60");
  });

  it("groups a 31-day range by month and labels it YYYY-MM", () => {
    const window = trendWindow("range", NOW, {
      range: ["2026-10-01", "2026-10-31"]
    }) as TrendWindow;
    const series = buildTrend(
      [day("2026-10-01", "10.00", "1.00", 1), day("2026-10-31", "5.50", "0.55", 2)],
      window
    );
    expect(series.points).toEqual([
      { key: "2026-10", salesCny: "15.50", commissionCny: "1.55", orderCount: 3 }
    ]);
  });

  it("counts only the part of the first and last month inside a cross-year range", () => {
    const window = trendWindow("range", NOW, {
      range: ["2025-12-15", "2026-02-10"]
    }) as TrendWindow;
    const series = buildTrend(
      [
        day("2025-12-10", "999.00", "99.00", 9), // before the range: ignored
        day("2025-12-15", "10.00", "1.00", 1),
        day("2025-12-31", "20.00", "2.00", 1),
        day("2026-01-01", "30.00", "3.00", 1),
        day("2026-02-10", "40.00", "4.00", 1),
        day("2026-02-11", "999.00", "99.00", 9) // after the range: ignored
      ],
      window
    );
    expect(series.points.map(point => point.key)).toEqual(["2025-12", "2026-01", "2026-02"]);
    expect(series.points.map(point => point.salesCny)).toEqual(["30.00", "30.00", "40.00"]);
    expect(series.salesTotal).toBe("100.00");
  });
});
