import { LineChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import * as echarts from "echarts/core";
import type { EChartsCoreOption } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";

/**
 * The single place that registers ECharts modules (engineering standard 11 §2). Registers the chart types used by the dashboard. Pages never call
 * `echarts.init`; `ChartPanel` is the only caller of `createChart`.
 */
echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

export type ChartOption = EChartsCoreOption;

/** The slice of an ECharts instance that `ChartPanel` uses. */
export interface ChartHandle {
  setOption(option: ChartOption, notMerge?: boolean): void;
  resize(): void;
  dispose(): void;
}

export function createChart(element: HTMLElement): ChartHandle {
  return echarts.init(element, undefined, { renderer: "canvas" });
}
