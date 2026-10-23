<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  getCreatorAnalytics,
  getFundSummary,
  type CreatorAnalyticsOverview,
  type FundSummary
} from "@/api/creator";
import { formatMoney } from "@/utils/orders";

defineOptions({ name: "HomePage" });
const { t, locale } = useI18n();
const analytics = ref<CreatorAnalyticsOverview | null>(null);
const funds = ref<FundSummary | null>(null);
const loading = ref(true);
const failed = ref(false);
const money = (value?: string | null) => formatMoney(value ?? "0", locale.value);
const chartPoints = computed(() => {
  const values = analytics.value?.daily ?? [];
  const max = Math.max(...values.map(item => Number(item.salesCny)), 1);
  return values.map((item, index) => ({
    ...item,
    x: values.length < 2 ? 50 : 8 + (index / (values.length - 1)) * 84,
    y: 82 - (Number(item.salesCny) / max) * 66
  }));
});
const salesPath = computed(() =>
  chartPoints.value.map((point, i) => `${i ? "L" : "M"}${point.x},${point.y}`).join(" ")
);
const withdrawalTotal = computed(() => {
  if (!funds.value) return "0";
  return [funds.value.withdrawnCny, funds.value.withdrawingCny, funds.value.frozenCny]
    .reduce((sum, value) => sum + Number(value), 0)
    .toFixed(2);
});

async function load() {
  loading.value = true;
  failed.value = false;
  const today = new Date();
  const from = new Date(today);
  from.setDate(today.getDate() - 6);
  const fmt = (date: Date) =>
    `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
  try {
    const [stats, summary] = await Promise.all([
      getCreatorAnalytics({
        from: `${fmt(from)}T00:00:00+08:00`,
        to: `${fmt(today)}T00:00:00+08:00`
      }),
      getFundSummary()
    ]);
    analytics.value = stats;
    funds.value = summary.fundSummary;
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<template>
  <section class="dashboard" aria-labelledby="dashboard-title">
    <header class="page-heading">
      <div>
        <h1 id="dashboard-title">{{ t("home.title") }}</h1>
        <p>{{ t("home.rangeHint") }}</p>
      </div>
      <el-button :loading="loading" @click="load">{{ t("home.refresh") }}</el-button>
    </header>
    <el-alert
      v-if="failed"
      type="error"
      :closable="false"
      :title="t('home.loadFailed')"
      show-icon
    />
    <el-skeleton v-if="loading && !analytics" :rows="5" animated />
    <template v-else-if="analytics && funds">
      <div class="metric-grid">
        <el-card shadow="never"
          ><span>{{ t("home.sales") }}</span
          ><strong>{{ money(analytics.salesCny) }}</strong
          ><small>{{ t("home.last7Days") }}</small></el-card
        >
        <el-card shadow="never"
          ><span>{{ t("home.orders") }}</span
          ><strong>{{ analytics.paidOrderCount }}</strong
          ><small>{{ t("home.paidOrders") }}</small></el-card
        >
        <el-card shadow="never"
          ><span>{{ t("home.commission") }}</span
          ><strong>{{ money(analytics.commissionCny) }}</strong
          ><small>{{ t("home.last7Days") }}</small></el-card
        >
        <el-card shadow="never"
          ><span>{{ t("home.available") }}</span
          ><strong>{{ money(funds.availableCny) }}</strong
          ><small>{{ t("home.withdrawable") }}</small></el-card
        >
      </div>
      <div class="chart-grid">
        <el-card shadow="never" class="chart-card">
          <template #header
            ><div class="card-heading">
              <strong>{{ t("home.salesTrend") }}</strong
              ><span>{{ t("home.last7Days") }}</span>
            </div></template
          >
          <div v-if="!chartPoints.length" class="empty-chart">{{ t("home.noTrend") }}</div>
          <svg
            v-else
            class="sales-chart"
            viewBox="0 0 100 100"
            role="img"
            :aria-label="t('home.salesTrend')"
            preserveAspectRatio="none"
          >
            <path d="M8 82 H92 M8 49 H92 M8 16 H92" class="chart-gridline" />
            <path :d="salesPath" class="chart-line" />
            <circle
              v-for="point in chartPoints"
              :key="point.date"
              :cx="point.x"
              :cy="point.y"
              r="1.6"
              class="chart-dot"
            >
              <title>{{ point.date }} · {{ money(point.salesCny) }}</title>
            </circle>
          </svg>
          <div v-if="chartPoints.length" class="chart-labels">
            <span>{{ chartPoints[0]?.date }}</span
            ><span>{{ chartPoints.at(-1)?.date }}</span>
          </div>
        </el-card>
        <el-card shadow="never" class="chart-card payout-card">
          <template #header
            ><strong>{{ t("home.withdrawalOverview") }}</strong></template
          >
          <div class="payout-total">
            <span>{{ t("home.commissionTotal") }}</span
            ><strong>{{ money(withdrawalTotal) }}</strong>
          </div>
          <div class="payout-rows">
            <div>
              <i class="dot withdrawn" /><span>{{ t("home.withdrawn") }}</span
              ><strong>{{ money(funds.withdrawnCny) }}</strong>
            </div>
            <div>
              <i class="dot withdrawing" /><span>{{ t("home.withdrawing") }}</span
              ><strong>{{ money(funds.withdrawingCny) }}</strong>
            </div>
            <div>
              <i class="dot frozen" /><span>{{ t("home.frozen") }}</span
              ><strong>{{ money(funds.frozenCny) }}</strong>
            </div>
            <div>
              <i class="dot available" /><span>{{ t("home.available") }}</span
              ><strong>{{ money(funds.availableCny) }}</strong>
            </div>
          </div>
          <RouterLink to="/funds" class="fund-link">{{ t("home.viewFunds") }} →</RouterLink>
        </el-card>
      </div>
      <p class="as-of">
        {{ t("home.updatedAt") }}: {{ new Date(analytics.asOf).toLocaleString(locale) }}
      </p>
    </template>
  </section>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
.page-heading,
.card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
}
h1 {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-weight: 600;
}
.page-heading p {
  margin: var(--tcrm-space-1) 0 0;
  color: var(--tcrm-color-text-secondary);
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
}
.metric-grid .el-card :deep(.el-card__body) {
  display: grid;
  gap: var(--tcrm-space-2);
}
.metric-grid span,
.metric-grid small,
.card-heading span,
.as-of {
  color: var(--tcrm-color-text-secondary);
}
.metric-grid strong {
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-variant-numeric: tabular-nums;
}
.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(300px, 1fr);
  gap: var(--tcrm-space-4);
}
.chart-card {
  min-width: 0;
}
.card-heading strong {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
}
.sales-chart {
  display: block;
  width: 100%;
  height: 250px;
  overflow: visible;
}
.chart-gridline {
  fill: none;
  stroke: var(--tcrm-color-border);
  stroke-dasharray: 1 1;
  stroke-width: 0.45;
}
.chart-line {
  fill: none;
  stroke: var(--tcrm-color-primary);
  stroke-width: 1.5;
  vector-effect: non-scaling-stroke;
}
.chart-dot {
  fill: var(--tcrm-color-primary);
}
.chart-labels {
  display: flex;
  justify-content: space-between;
  color: var(--tcrm-color-text-secondary);
}
.empty-chart {
  display: grid;
  place-items: center;
  min-height: 250px;
  color: var(--tcrm-color-text-secondary);
}
.payout-total {
  display: grid;
  gap: var(--tcrm-space-2);
  padding: var(--tcrm-space-3);
  background: var(--tcrm-color-muted);
  border-radius: var(--tcrm-radius-control);
}
.payout-total span,
.payout-rows span {
  color: var(--tcrm-color-text-secondary);
}
.payout-total strong {
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
}
.payout-rows {
  display: grid;
  gap: var(--tcrm-space-3);
  margin-top: var(--tcrm-space-4);
}
.payout-rows div {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) auto;
  gap: var(--tcrm-space-2);
  align-items: center;
}
.payout-rows strong {
  font-variant-numeric: tabular-nums;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--tcrm-color-primary);
}
.withdrawn {
  background: var(--tcrm-color-success);
}
.withdrawing {
  background: #3478c8;
}
.frozen {
  background: var(--tcrm-color-warning);
}
.available {
  background: #7251c7;
}
.fund-link {
  display: inline-block;
  margin-top: var(--tcrm-space-4);
  color: var(--tcrm-color-primary);
  text-decoration: none;
}
.as-of {
  margin: 0;
}
@media (max-width: 1023px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 767px) {
  .metric-grid {
    grid-template-columns: 1fr 1fr;
    gap: var(--tcrm-space-3);
  }
  .metric-grid strong {
    font-size: var(--tcrm-font-size-section);
    line-height: var(--tcrm-line-height-section);
  }
  .page-heading {
    align-items: flex-start;
  }
  .sales-chart {
    height: 200px;
  }
}
</style>
