<!--
  Creator-portal chart container (task 92, 规范 11 §2). Same duties as the admin dashboard `ChartPanel`:
  the only place that creates an ECharts instance, width follows the container, the instance is
  disposed on unmount, colours and font sizes come from tokens.css, and the loading, empty and
  failed states are all drawn here. Pages hand over a plain option without colours or font sizes.

  Series colours are applied by position: the first series uses the primary colour, the second the
  success colour. Axis, grid and tooltip styling is merged underneath the page's own settings.
-->
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { createChart, type ChartHandle, type ChartOption } from "@/utils/echarts";

defineOptions({ name: "ChartPanel" });

const props = withDefaults(
  defineProps<{
    /** Page-built option (series, axes, tooltip formatter); no colours or font sizes in it. */
    option: ChartOption | null;
    /** Accessible name of the chart. */
    label: string;
    loading?: boolean;
    failed?: boolean;
    /** True when the request succeeded but there is nothing to draw. */
    empty?: boolean;
    /** Replaces the default empty-state text. */
    emptyText?: string;
  }>(),
  { loading: false, failed: false, empty: false, emptyText: undefined }
);
const emit = defineEmits<{ retry: [] }>();

const { t } = useI18n();
const element = ref<HTMLElement | null>(null);
let chart: ChartHandle | null = null;
let observer: ResizeObserver | null = null;

type Loose = Record<string, unknown>;

/** Reads a design token; the canvas cannot resolve CSS variables itself. */
function token(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function asObject(value: unknown): Loose {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Loose) : {};
}

function themed(option: ChartOption): ChartOption {
  const fontSize = Number.parseFloat(token("--tcrm-font-size-body"));
  const text = token("--tcrm-color-text");
  const secondary = token("--tcrm-color-text-secondary");
  const border = token("--tcrm-color-border");
  const input = option as Loose;
  const axisBase = (axis: Loose): Loose => ({
    ...axis,
    axisLine: { ...asObject(axis.axisLine), lineStyle: { color: border } },
    axisTick: { ...asObject(axis.axisTick), lineStyle: { color: border } },
    axisLabel: { color: secondary, fontSize, ...asObject(axis.axisLabel) },
    splitLine: { ...asObject(axis.splitLine), lineStyle: { color: border } }
  });
  const axes = (value: unknown): unknown =>
    Array.isArray(value) ? value.map(axis => axisBase(asObject(axis))) : axisBase(asObject(value));
  const tooltip = asObject(input.tooltip);
  return {
    color: [token("--tcrm-color-primary"), token("--tcrm-color-success")],
    textStyle: { fontFamily: token("--tcrm-font-sans"), fontSize, color: secondary },
    ...input,
    grid: { left: 8, right: 8, top: 16, bottom: 8, containLabel: true, ...asObject(input.grid) },
    tooltip: {
      backgroundColor: token("--tcrm-color-card"),
      borderColor: border,
      ...tooltip,
      textStyle: { color: text, fontSize, ...asObject(tooltip.textStyle) }
    },
    xAxis: axes(input.xAxis),
    yAxis: axes(input.yAxis)
  };
}

const showChart = computed(() => !props.failed && !props.empty && props.option !== null);

function destroy(): void {
  observer?.disconnect();
  observer = null;
  chart?.dispose();
  chart = null;
}

function sync(): void {
  const host = element.value;
  if (!showChart.value || !host || !props.option) {
    destroy();
    return;
  }
  if (!chart) {
    chart = createChart(host);
    if (typeof ResizeObserver !== "undefined") {
      observer = new ResizeObserver(() => chart?.resize());
      observer.observe(host);
    }
  }
  chart.setOption(themed(props.option), true);
}

watch(
  () => [props.option, showChart.value],
  async () => {
    await nextTick();
    sync();
  }
);
onMounted(sync);
onBeforeUnmount(destroy);
</script>

<template>
  <div class="chart-panel" data-test="chart-panel">
    <div v-if="failed" class="chart-panel__state" role="alert" data-test="chart-failed">
      <span>{{ t("chart.failed") }}</span>
      <el-button data-test="chart-retry" @click="emit('retry')">{{ t("chart.retry") }}</el-button>
    </div>
    <div
      v-else-if="loading && option === null"
      class="chart-panel__state"
      role="status"
      data-test="chart-loading"
    >
      {{ t("chart.loading") }}
    </div>
    <div v-else-if="empty || option === null" class="chart-panel__state" data-test="chart-empty">
      {{ emptyText ?? t("chart.empty") }}
    </div>
    <div
      v-else
      ref="element"
      v-loading="loading"
      class="chart-panel__canvas"
      role="img"
      :aria-label="label"
      :aria-busy="loading"
      data-test="chart-canvas"
    />
  </div>
</template>

<style scoped>
.chart-panel {
  min-width: 0;
}
.chart-panel__canvas,
.chart-panel__state {
  width: 100%;
  height: 320px;
}
.chart-panel__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--tcrm-space-3);
  color: var(--tcrm-color-text-secondary);
  text-align: center;
}
@media (max-width: 767px) {
  .chart-panel__canvas,
  .chart-panel__state {
    height: 260px;
  }
}
</style>
