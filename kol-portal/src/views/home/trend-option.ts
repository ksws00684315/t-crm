import type { ChartOption } from "@/utils/echarts";
import { formatMoney } from "@/utils/money";
import type { TrendGranularity, TrendPoint } from "@/utils/sales-trend";

/** Labels the chart needs, already translated by the page. */
export interface TrendLabels {
  gmv: string;
  commission: string;
  orders: string;
  gmvNote: string;
}

/**
 * Sales as bars on the left axis, commission as a line on the right axis. The option carries no
 * colours or font sizes: `ChartPanel` themes it from tokens.css. Plot values are display-only
 * numbers; every total shown as text is summed in cents (`buildTrend`).
 */
export function trendOption(
  points: TrendPoint[],
  granularity: TrendGranularity,
  labels: TrendLabels,
  locale: string
): ChartOption {
  const compact = new Intl.NumberFormat(locale, { notation: "compact", maximumFractionDigits: 1 });
  const axisLabel = (value: number) => compact.format(value);
  return {
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
      formatter: (params: { dataIndex: number; marker: string; seriesName: string }[]) => {
        const point = points[params[0]?.dataIndex ?? 0];
        if (!point) return "";
        const rows = params.map(item => {
          const value = item.seriesName === labels.gmv ? point.salesCny : point.commissionCny;
          return `${item.marker}${item.seriesName}: ${formatMoney(value)}`;
        });
        return [
          `<strong>${point.key}</strong>`,
          ...rows,
          `${labels.orders}: ${point.orderCount}`,
          labels.gmvNote
        ].join("<br/>");
      }
    },
    xAxis: {
      type: "category",
      data: points.map(point => (granularity === "day" ? point.key.slice(5) : point.key)),
      axisTick: { alignWithLabel: true }
    },
    yAxis: [
      { type: "value", axisLabel: { formatter: axisLabel } },
      { type: "value", splitLine: { show: false }, axisLabel: { formatter: axisLabel } }
    ],
    series: [
      {
        name: labels.gmv,
        type: "bar",
        yAxisIndex: 0,
        barMaxWidth: 32,
        data: points.map(point => Number(point.salesCny))
      },
      {
        name: labels.commission,
        type: "line",
        yAxisIndex: 1,
        symbol: "circle",
        data: points.map(point => Number(point.commissionCny))
      }
    ]
  };
}
