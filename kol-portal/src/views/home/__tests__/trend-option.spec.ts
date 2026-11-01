import { describe, expect, it } from "vitest";
import { trendOption } from "../trend-option";

const labels = {
  gmv: "GMV",
  commission: "Commission",
  orders: "Orders",
  gmvNote: "GMV is before refunds."
};
const points = [
  { key: "2026-10-01", salesCny: "1000.00", commissionCny: "120.00", orderCount: 4 },
  { key: "2026-10-02", salesCny: "0.00", commissionCny: "0.00", orderCount: 0 }
];

type Series = { name: string; type: string; yAxisIndex: number; data: number[] };

describe("trend chart option", () => {
  it("draws GMV as bars on the left axis and commission as a line on the right axis", () => {
    const option = trendOption(points, "day", labels, "en-US") as {
      xAxis: { data: string[] };
      yAxis: unknown[];
      series: Series[];
    };
    expect(option.series).toMatchObject([
      { name: "GMV", type: "bar", yAxisIndex: 0, data: [1000, 0] },
      { name: "Commission", type: "line", yAxisIndex: 1, data: [120, 0] }
    ]);
    expect(option.yAxis).toHaveLength(2);
    expect(option.xAxis.data).toEqual(["10-01", "10-02"]);
  });

  it("labels the axis YYYY-MM when grouped by month", () => {
    const option = trendOption(
      [{ key: "2026-10", salesCny: "1.00", commissionCny: "0.10", orderCount: 1 }],
      "month",
      labels,
      "en-US"
    ) as { xAxis: { data: string[] } };
    expect(option.xAxis.data).toEqual(["2026-10"]);
  });

  it("puts date, GMV, commission, order count and the before-refund note in the tooltip", () => {
    const option = trendOption(points, "day", labels, "en-US") as {
      tooltip: { formatter: (params: unknown[]) => string };
    };
    const html = option.tooltip.formatter([
      { dataIndex: 0, marker: "<i>", seriesName: "GMV" },
      { dataIndex: 0, marker: "<i>", seriesName: "Commission" }
    ]);
    expect(html).toContain("2026-10-01");
    expect(html).toContain("GMV: ¥1,000.00");
    expect(html).toContain("Commission: ¥120.00");
    expect(html).toContain("Orders: 4");
    expect(html).toContain("GMV is before refunds.");
  });

  it("carries no colour or font size, which ChartPanel adds from tokens", () => {
    const text = JSON.stringify(trendOption(points, "day", labels, "en-US"));
    expect(text).not.toMatch(/#[0-9a-f]{3,8}|rgb|fontSize|color/i);
  });
});
