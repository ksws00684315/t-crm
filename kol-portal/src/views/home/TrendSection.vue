<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getCreatorAnalytics, type CreatorAnalyticsOverview } from "@/api/creator";
import ChartPanel from "@/components/ChartPanel.vue";
import { formatMoney } from "@/utils/orders";
import {
  addDays,
  beijingDate,
  buildTrend,
  trendApiParams,
  trendWindow,
  type TrendMode,
  type TrendWindow,
  type TrendWindowError
} from "@/utils/sales-trend";
import { trendOption } from "./trend-option";

defineOptions({ name: "HomeTrendSection" });
const { t, locale } = useI18n();

const mode = ref<TrendMode>("last30");
const month = ref<string>(beijingDate(new Date()).slice(0, 7));
const range = ref<[string, string] | null>(null);
const daily = ref<CreatorAnalyticsOverview["daily"] | null>(null);
const shown = ref<TrendWindow | null>(null);
const loading = ref(false);
const failed = ref(false);
let requestId = 0;

const windowOrError = computed<TrendWindow | TrendWindowError>(() =>
  trendWindow(mode.value, new Date(), { month: month.value, range: range.value })
);
const windowError = computed<TrendWindowError | null>(() =>
  typeof windowOrError.value === "string" ? windowOrError.value : null
);
const errorText = computed(() => {
  if (windowError.value === "tooLong") return t("home.rangeTooLong");
  if (windowError.value === "invalid" && mode.value === "range") return t("home.rangeInvalid");
  return "";
});

const series = computed(() =>
  daily.value && shown.value ? buildTrend(daily.value, shown.value) : null
);
const empty = computed(() => (series.value ? series.value.orderTotal === 0 : false));
const option = computed(() => {
  if (!series.value || !shown.value || empty.value) return null;
  return trendOption(
    series.value.points,
    shown.value.granularity,
    {
      gmv: t("home.gmv"),
      commission: t("home.commission"),
      orders: t("home.ordersCount"),
      gmvNote: t("home.gmvNote")
    },
    locale.value
  );
});

async function load() {
  const next = windowOrError.value;
  if (typeof next === "string") {
    // A rejected range never reaches the server; the chart keeps its last good data.
    return;
  }
  const current = ++requestId;
  loading.value = true;
  failed.value = false;
  try {
    const result = await getCreatorAnalytics(trendApiParams(next));
    if (current !== requestId) return;
    daily.value = result.daily;
    shown.value = next;
  } catch {
    if (current !== requestId) return;
    failed.value = true;
    daily.value = null;
  } finally {
    if (current === requestId) loading.value = false;
  }
}

function today(): string {
  return beijingDate(new Date());
}
function localDate(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
/** Nothing after today in Beijing can have sales. */
function disableFuture(date: Date): boolean {
  return localDate(date) > today();
}
function selectMode(value: string | number | boolean | undefined) {
  mode.value = value as TrendMode;
  if (mode.value === "range" && !range.value) range.value = [addDays(today(), -29), today()];
}
function updateRange(value: [string, string] | null) {
  range.value = value;
}

watch([mode, month, range], () => void load());
onMounted(load);
defineExpose({ reload: load });
</script>

<template>
  <section class="trend" aria-labelledby="trend-title">
    <header class="trend__heading">
      <h2 id="trend-title">{{ t("home.trendTitle") }}</h2>
      <div class="trend__filters" :aria-label="t('home.trendRange')" role="group">
        <el-radio-group :model-value="mode" data-test="trend-mode" @change="selectMode">
          <el-radio-button value="last7">{{ t("home.last7") }}</el-radio-button>
          <el-radio-button value="last30">{{ t("home.last30") }}</el-radio-button>
          <el-radio-button value="month">{{ t("home.byMonth") }}</el-radio-button>
          <el-radio-button value="range">{{ t("home.dateRange") }}</el-radio-button>
        </el-radio-group>
        <el-date-picker
          v-if="mode === 'month'"
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          :clearable="false"
          :disabled-date="disableFuture"
          :placeholder="t('home.monthPlaceholder')"
          data-test="trend-month"
        />
        <el-date-picker
          v-else-if="mode === 'range'"
          :model-value="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          :clearable="false"
          :disabled-date="disableFuture"
          :start-placeholder="t('home.rangeStart')"
          :end-placeholder="t('home.rangeEnd')"
          data-test="trend-range"
          @update:model-value="updateRange"
        />
        <span class="trend__zone" data-test="trend-zone">{{ t("home.beijingTime") }}</span>
      </div>
    </header>
    <p v-if="errorText" class="trend__error" role="alert" data-test="trend-error">
      {{ errorText }}
    </p>
    <ul v-if="series && !failed" class="trend__legend" data-test="trend-legend">
      <li>
        <span class="swatch swatch--gmv" aria-hidden="true" />
        <span>{{ t("home.gmv") }}</span>
        <strong data-test="legend-gmv">{{ formatMoney(series.salesTotal, locale) }}</strong>
      </li>
      <li>
        <span class="swatch swatch--commission" aria-hidden="true" />
        <span>{{ t("home.commission") }}</span>
        <strong data-test="legend-commission">{{
          formatMoney(series.commissionTotal, locale)
        }}</strong>
      </li>
    </ul>
    <ChartPanel
      :option="option"
      :label="t('home.trendLabel')"
      :loading="loading"
      :failed="failed"
      :empty="empty"
      :empty-text="t('home.noTrend')"
      @retry="load"
    />
  </section>
</template>

<style scoped>
.trend {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-3);
  min-width: 0;
  padding: var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.trend__heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
}
.trend__heading h2 {
  margin: 0;
}
.trend__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.trend__zone {
  color: var(--tcrm-color-text-secondary);
}
.trend__error {
  margin: 0;
  color: var(--tcrm-color-danger);
}
.trend__legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2) var(--tcrm-space-5);
  margin: 0;
  padding: 0;
  list-style: none;
}
.trend__legend li {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.trend__legend strong {
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.swatch {
  width: var(--tcrm-space-3);
  height: var(--tcrm-space-3);
  border-radius: var(--tcrm-radius-tag);
}
.swatch--gmv {
  background: var(--tcrm-color-primary);
}
.swatch--commission {
  background: var(--tcrm-color-success);
}
</style>
