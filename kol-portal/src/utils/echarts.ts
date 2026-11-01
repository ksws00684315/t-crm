import { BarChart, LineChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import * as echarts from "echarts/core";
import type { EChartsCoreOption } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";

/**
 * The single place that registers ECharts modules (规范 11 §2). Only what the creator portal draws is
 * registered: bar and line series, grid, tooltip and the canvas renderer. Pages never call
 * `echarts.init`; `ChartPanel` is the only caller of `createChart`.
 */
echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

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
