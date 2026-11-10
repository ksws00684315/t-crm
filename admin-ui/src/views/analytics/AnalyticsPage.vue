<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import {
  ArrowRight,
  DataBoard,
  Goods,
  Tickets,
  TrendCharts,
  Wallet,
  Warning
} from "@element-plus/icons-vue";
import { analyticsOptions, exportAnalytics, queryAnalytics } from "@/api/analytics";
import type {
  AnalyticsResult,
  Filters,
  Metric,
  Options,
  RankRow,
  RankingQuery,
  Scalar
} from "@/api/analytics";
import CopyText from "@/components/CopyText.vue";
import DataTable from "@/components/DataTable.vue";
import type { DataColumn } from "@/components/DataTable.vue";
import RowActions from "@/components/RowActions.vue";
import { useUserStore } from "@/stores/user";
import { saveBlob } from "@/utils/download";
import { formatAmountWithCurrency } from "@/utils/format";
import { canExport } from "@/utils/permission";
import {
  boards,
  columnsByBoard,
  dates,
  groupedSort,
  money,
  pending as pendingColumns,
  rates,
  sortable,
  validDates
} from "./board";
import DashboardFilter from "./DashboardFilter.vue";
import MetricCard from "./MetricCard.vue";
import QuerySummary from "./QuerySummary.vue";

defineOptions({ name: "AnalyticsPage" });

const { t, locale } = useI18n();
const me = useUserStore();
const board = boards[String(useRoute().name)]!;
const shop = board === "shop-performance";
const product = board === "best-sellers";
const creator = board === "kol-ranking";
const draft = ref<Filters>(dates());
const applied = ref<Filters>({ ...draft.value });
const page = ref(1);
const size = ref(20);
const sortBy = ref("netSalesAmount");
const sortDirection = ref<"asc" | "desc">("desc");
const result = ref<AnalyticsResult>();
const options = ref<Options>();
const loading = ref(false);
const failed = ref(false);
const optionsFailed = ref(false);
const exporting = ref(false);
let request = 0;
let active = false;
let live = false;
let timer: ReturnType<typeof setInterval> | undefined;

const query = computed<RankingQuery>(() => ({
  ...applied.value,
  page: page.value,
  size: size.value,
  sortBy: sortBy.value,
  sortDirection: sortDirection.value
}));
const metrics = computed(() => result.value?.summary.metrics);
const rows = computed(() => result.value?.ranking.items ?? []);
const columnWidths: Record<string, DataColumn["minWidth"]> = {
  identity: product ? 180 : 184,
  rank: 68,
  actions: 84,
  channelStatus: 86,
  coverage: 122,
  units: { "zh-CN": 76, "en-US": 106 },
  paidOrderCount: { "zh-CN": 88, "en-US": 112 },
  salesAmount: { "zh-CN": 102, "en-US": 112 },
  completedRefundAmount: { "zh-CN": 114, "en-US": 142 },
  netSalesAmount: { "zh-CN": 112, "en-US": 128 }
};
const columns = computed<DataColumn[]>(() =>
  [...columnsByBoard[board], "actions"].map(key => ({
    key,
    label: label(key),
    amount: money.has(key),
    tooltip: false,
    align: key === "identity" ? "left" : "center",
    interactive: key === "identity",
    fixed: product && key === "identity" ? "left" : undefined,
    highlighted: sortBy.value === key || (key === "coverage" && groupedSort.includes(sortBy.value)),
    minWidth: columnWidths[key] ?? { "zh-CN": 108, "en-US": 136 }
  }))
);
const sortKeys = columnsByBoard[board].filter(key => sortable.has(key) || key === "coverage");
const cellKeys = columnsByBoard[board].filter(
  key => key !== "rank" && (sortable.has(key) || pendingColumns.has(key))
);
const detailActions = computed(() => [
  { key: "detail", label: t("analytics.detail"), disabled: true, tooltip: t("common.comingSoon") }
]);

function identity(row: RankRow): string {
  return (shop ? row.shopName : row.name) || (row.id === "0" ? t("analytics.unassigned") : "—");
}

function label(key: string): string {
  if (
    key === "identity" ||
    key === "netCommission" ||
    (product && key === "periodRefundedOrderRate")
  ) {
    return t(`analytics.${board}.${key}`);
  }
  if (key === "kolCount" && (product || shop)) return t("analytics.productCreatorCount");
  if (key === "netSalesAmount" && product) return t("analytics.best-sellers.net");
  return t(`analytics.${key}`);
}

function display(key: string, value: Scalar | null | undefined): string {
  if (value == null) return "—";
  if (money.has(key)) return formatAmountWithCurrency(String(value));
  if (rates.has(key)) return percent(value);
  if (key === "rankChange" && Number(value) > 0) return `+${value}`;
  return new Intl.NumberFormat(locale.value).format(BigInt(String(value)));
}

function percent(value: Scalar | null | undefined): string {
  return value == null
    ? "—"
    : new Intl.NumberFormat(locale.value, {
        style: "percent",
        maximumFractionDigits: 2
      }).format(Number(value));
}

function valueOf(row: RankRow, key: string): Scalar | null {
  const value = row.metrics?.[key as Metric] ?? row[key];
  return typeof value === "string" || typeof value === "number" ? value : null;
}

function comparison(key: Metric): string {
  return t("analytics.comparison", { value: percent(result.value?.change[key]) });
}

const cards = computed(() => {
  const summary = result.value?.summary;
  const data = metrics.value;
  const selling = shop ? summary?.sellingShopCount : summary?.sellingCount;
  const total = shop ? summary?.shopCount : summary?.activeCount;
  const first = {
    tone: "neutral" as const,
    icon: DataBoard,
    label: t(`analytics.${board}.selling`),
    value: display("", selling),
    detail: product ? undefined : t(`analytics.${board}.active`, { value: display("", total) })
  };
  const orders = {
    tone: "blue" as const,
    icon: Tickets,
    label: label("paidOrderCount"),
    value: display("", data?.paidOrderCount),
    detail:
      shop || product ? undefined : t("analytics.unitsValue", { value: display("", data?.units) }),
    comparison: comparison("paidOrderCount")
  };
  const net = {
    tone: "green" as const,
    icon: TrendCharts,
    label: t(product ? "analytics.best-sellers.net" : "analytics.netSalesAmount"),
    value: display("netSalesAmount", data?.netSalesAmount),
    comparison: comparison("netSalesAmount"),
    hint: t("analytics.netHint")
  };
  const last = product
    ? {
        tone: "amber" as const,
        icon: Goods,
        label: label("units"),
        value: display("", data?.units),
        comparison: comparison("units")
      }
    : {
        tone: "amber" as const,
        icon: Wallet,
        label: label(shop ? "settlement" : "netCommission"),
        value: t("analytics.pending"),
        unavailable: true
      };
  return product ? [first, orders, last, net] : [first, orders, net, last];
});

const conversion = computed(() => [
  { key: "invitedCount", value: result.value?.summary.invitedCount },
  { key: "effectiveCount", value: result.value?.summary.effectiveCount },
  { key: "kolCount", value: result.value?.summary.sellingCreatorCount }
]);
function ratio(part?: Scalar, whole?: Scalar): string {
  return part == null || whole == null || Number(whole) === 0
    ? "—"
    : percent(Number(part) / Number(whole));
}

async function load(): Promise<void> {
  if (live) {
    const today = dates(1);
    if (applied.value.endDate !== today.endDate) {
      applied.value = { ...applied.value, ...today };
      page.value = 1;
    }
  }
  const id = ++request;
  loading.value = true;
  failed.value = false;
  try {
    const next = await queryAnalytics(board, query.value);
    if (id === request) result.value = next;
  } catch {
    if (id === request) {
      result.value = undefined;
      failed.value = true;
    }
  } finally {
    if (id === request) loading.value = false;
  }
}

async function loadOptions(): Promise<void> {
  try {
    options.value = await analyticsOptions();
    optionsFailed.value = false;
  } catch {
    optionsFailed.value = true;
  }
}

function refresh(): void {
  if (optionsFailed.value) void loadOptions();
  void load();
}

function search(useLive = false): void {
  if (!validDates(draft.value)) {
    ElMessage.warning(t("analytics.invalidDates"));
    return;
  }
  live = useLive;
  const { shopId, kolId, inviterId, channel, keyword, startDate, endDate } = draft.value;
  applied.value = {
    startDate,
    endDate,
    shopId: shopId || undefined,
    kolId: kolId || undefined,
    inviterId: inviterId || undefined,
    channel: channel || undefined,
    keyword: keyword?.trim() || undefined
  };
  page.value = 1;
  result.value = undefined;
  void load();
}

function reset(): void {
  draft.value = dates();
  search();
}
function sort(key: string): void {
  sortDirection.value = sortBy.value === key && sortDirection.value === "desc" ? "asc" : "desc";
  sortBy.value = key;
  page.value = 1;
  void load();
}
function changePage(next: number): void {
  if (page.value === next) return;
  page.value = next;
  void load();
}
function changeSize(next: number): void {
  if (size.value === next) return;
  size.value = next;
  page.value = 1;
  void load();
}
async function download(): Promise<void> {
  if (exporting.value || loading.value || !result.value) return;
  exporting.value = true;
  try {
    const file = await exportAnalytics(board, query.value);
    saveBlob(file.blob, file.fileName ?? `${board}.xlsx`);
  } catch {
    ElMessage.error(t("analytics.exportFailed"));
  } finally {
    exporting.value = false;
  }
}

function start(): void {
  if (active) return;
  active = true;
  void load();
  if (!options.value) void loadOptions();
  timer = setInterval(() => {
    if (!document.hidden && !loading.value) void load();
  }, 300_000);
}
function stop(): void {
  active = false;
  request++;
  clearInterval(timer);
}
onMounted(start);
onActivated(start);
onDeactivated(stop);
onUnmounted(stop);
</script>

<template>
  <div class="analytics-page">
    <DashboardFilter
      v-model="draft"
      :board="board"
      :loading="loading"
      :options="options"
      :options-failed="optionsFailed"
      @search="search"
      @reset="reset"
      @refresh="refresh"
    />
    <QuerySummary v-if="result" :result="result" :options="options" />

    <div class="analytics-cards" :aria-busy="loading">
      <MetricCard v-for="card in cards" :key="card.label" v-bind="card" />
    </div>

    <section v-if="shop" class="analytics-topic">
      <h2>{{ t("analytics.transactionTitle") }}</h2>
      <div class="ledger-comparison">
        <div class="ledger-comparison__panel ledger-comparison__transaction">
          <h3>{{ t("analytics.transactionLedger") }}</h3>
          <div class="ledger-comparison__hero">
            <strong>{{ display("netSalesAmount", metrics?.netSalesAmount) }}</strong>
            <span>{{ label("netSalesAmount") }}</span>
          </div>
          <div class="ledger-comparison__values">
            <div>
              <span>{{ label("salesAmount") }}</span
              ><strong>{{ display("salesAmount", metrics?.salesAmount) }}</strong>
            </div>
            <span class="ledger-comparison__operator" aria-hidden="true">−</span>
            <div class="ledger-comparison__refund">
              <span>{{ label("cohortRefundAmount") }}</span
              ><strong>{{ display("cohortRefundAmount", metrics?.cohortRefundAmount) }}</strong>
            </div>
          </div>
        </div>
        <div class="ledger-comparison__bridge">
          <el-icon aria-hidden="true"><ArrowRight /></el-icon>
          <span>{{ t("analytics.ledgerBridge") }}</span>
        </div>
        <div class="ledger-comparison__panel ledger-comparison__settlement">
          <h3>{{ t("analytics.settlementLedger") }}</h3>
          <div class="ledger-comparison__hero">
            <span>{{ label("settlement") }}</span>
            <el-tag type="info" effect="light">{{ t("analytics.pending") }}</el-tag>
          </div>
        </div>
        <div class="ledger-comparison__panel ledger-comparison__note">
          <h3>{{ t("analytics.ledgerDifference") }}</h3>
          <p>{{ t("analytics.ledgerDifferenceHint") }}</p>
        </div>
      </div>
    </section>
    <section v-else-if="creator" class="analytics-topic">
      <h2>{{ t("analytics.riskTitle") }}</h2>
      <div class="topic-grid topic-grid--two">
        <MetricCard
          :label="label('amountRefundRate')"
          :icon="Warning"
          tone="amber"
          :value="percent(metrics?.amountRefundRate)"
          :detail="`${display('completedRefundAmount', metrics?.completedRefundAmount)} / ${display('salesAmount', metrics?.salesAmount)}`"
        />
        <MetricCard
          :label="label('periodRefundedOrderRate')"
          :icon="Warning"
          tone="red"
          :value="percent(metrics?.periodRefundedOrderRate)"
          :detail="`${display('', metrics?.refundedOrderCount)} / ${display('', metrics?.paidOrderCount)}`"
        />
      </div>
    </section>
    <section v-else-if="!product" class="analytics-topic">
      <h2>{{ t("analytics.conversion") }}</h2>
      <div class="topic-grid topic-grid--three">
        <MetricCard
          v-for="(entry, index) in conversion"
          :key="entry.key"
          :label="label(entry.key)"
          :icon="[Tickets, DataBoard, TrendCharts][index]"
          :tone="(['neutral', 'blue', 'green'] as const)[index]"
          :value="display('', entry.value)"
          :detail="
            index > 0
              ? t(index === 1 ? 'analytics.effectiveRate' : 'analytics.activationRate', {
                  value: ratio(entry.value, conversion[index - 1]?.value)
                })
              : undefined
          "
        />
      </div>
    </section>

    <p v-if="product" class="analytics-note">{{ t("analytics.productRefundHint") }}</p>
    <DataTable
      class="analytics-ranking"
      scroll-with-page
      :table-id="`analytics-${board}`"
      :columns="columns"
      :rows="rows"
      :row-key="shop ? 'shopId' : 'id'"
      :loading="loading"
      :failed="failed"
      :page="page"
      :size="size"
      :page-sizes="[20, 50, 100]"
      :total="Number(result?.ranking.total ?? 0)"
      :exportable="canExport(me.user)"
      :exporting="exporting"
      @export="download"
      @refresh="refresh"
      @retry="refresh"
      @update:page="changePage"
      @update:size="changeSize"
    >
      <template #title>
        <h2 class="analytics-ranking__title">{{ t(`analytics.${board}.ranking`) }}</h2>
      </template>
      <template #toolbar>
        <el-tag v-if="result" effect="light" type="primary">
          {{
            t(
              `analytics.${board}.rankingCount`,
              { count: display("", result.ranking.total) },
              Number(result.ranking.total)
            )
          }}
        </el-tag>
      </template>
      <template v-for="key in sortKeys" :key="key" #[`header-${key}`]>
        <div class="sort-controls">
          <button
            v-for="field in key === 'coverage' ? groupedSort : [key]"
            :key="field"
            type="button"
            :aria-label="
              t('analytics.sortLabel', {
                label: label(field),
                direction:
                  sortBy === field ? t(`analytics.${sortDirection}`) : t('analytics.unsorted')
              })
            "
            :class="{ selected: sortBy === field }"
            @click="sort(field)"
          >
            {{ label(field) }}
            <span aria-hidden="true">{{
              sortBy === field ? (sortDirection === "asc" ? "↑" : "↓") : "↕"
            }}</span>
          </button>
        </div>
      </template>
      <template #cell-rank="{ row }">
        <span class="analytics-rank-badge" :data-rank="valueOf(row, 'rank')">
          {{ display("rank", valueOf(row, "rank")) }}
        </span>
      </template>
      <template #cell-identity="{ row }">
        <div class="analytics-identity">
          <img
            v-if="product && row.imageUrl"
            class="product-thumb"
            :src="row.imageUrl"
            alt=""
            loading="lazy"
          />
          <span
            v-else
            class="analytics-identity__avatar"
            :class="{ 'is-product': product }"
            aria-hidden="true"
          >
            <el-icon v-if="product"><Goods /></el-icon>
            <template v-else>{{
              Array.from(identity(row))[0]?.toLocaleUpperCase(locale)
            }}</template>
          </span>
          <div class="analytics-identity__text">
            <strong>{{ identity(row) }}</strong>
            <CopyText
              v-if="shop ? row.shopCode : !product && row.code"
              :value="shop ? row.shopCode : row.code"
            />
            <small v-if="row.social?.length">{{ row.social.join(" · ") }}</small>
          </div>
        </div>
      </template>
      <template #cell-channelStatus="{ row }">
        <div class="analytics-badges">
          <el-tag size="small" type="info">{{ t("analytics.weidian") }}</el-tag>
          <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
            {{ t(row.status === 1 ? "common.enabled" : "common.disabled") }}
          </el-tag>
        </div>
      </template>
      <template #cell-channelCode="{ row }">
        <div>
          <el-tag size="small" type="info">{{ t("analytics.weidian") }}</el-tag>
        </div>
        <CopyText :value="row.code" />
      </template>
      <template #cell-shop="{ row }">{{ row.related || "—" }}</template>
      <template #cell-relationship="{ row }">
        <div>{{ row.related || t("analytics.unassigned") }}</div>
        <el-tag v-if="row.enabled != null" size="small" :type="row.enabled ? 'success' : 'info'">
          {{ t(row.enabled ? "analytics.enabled" : "analytics.disabled") }}
        </el-tag>
      </template>
      <template #cell-status="{ row }">
        <el-tag v-if="row.enabled != null" size="small" :type="row.enabled ? 'success' : 'info'">
          {{ t(row.enabled ? "analytics.enabled" : "analytics.disabled") }}
        </el-tag>
        <span v-else>—</span>
      </template>
      <template #cell-coverage="{ row }">
        <div class="coverage-values">
          <template v-for="(key, index) in groupedSort" :key="key">
            <span v-if="index" class="coverage-values__separator" aria-hidden="true">/</span>
            <span>{{ display(key, valueOf(row, key)) }}</span>
          </template>
        </div>
      </template>
      <template v-for="key in cellKeys" :key="key" #[`cell-${key}`]="{ row }">
        <div class="analytics-cell">
          <span
            class="analytics-number"
            :class="{
              'analytics-number--net': key === 'netSalesAmount',
              'analytics-number--loss':
                (key === 'completedRefundAmount' && Number(valueOf(row, key)) > 0) ||
                (key === 'netSalesAmount' && Number(valueOf(row, key)) < 0),
              'analytics-number--unavailable': pendingColumns.has(key)
            }"
            >{{
              pendingColumns.has(key) ? t("analytics.pending") : display(key, valueOf(row, key))
            }}</span
          >
          <el-tag
            v-if="product && key === 'periodRefundedOrderRate' && Number(valueOf(row, key)) > 0.2"
            type="warning"
            size="small"
            >{{ t("analytics.highRefund") }}</el-tag
          >
        </div>
      </template>
      <template #cell-category="{ row }">{{ row.category || "—" }}</template>
      <template #cell-trend="{ row }">
        <el-tooltip v-if="row.trend?.length" :trigger="['hover', 'focus']" placement="top">
          <template #content>
            <div v-for="point in row.trend" :key="point.day">
              {{ point.day }} · {{ display("netSalesAmount", point.netSalesAmount) }}
            </div>
          </template>
          <span tabindex="0" class="analytics-number"
            >{{ display("netSalesAmount", row.trend[0]?.netSalesAmount) }}
            <template v-if="row.trend.length > 1">
              → {{ display("netSalesAmount", row.trend.at(-1)?.netSalesAmount) }}</template
            >
          </span>
        </el-tooltip>
        <span v-else>—</span>
      </template>
      <template #cell-topProducts="{ row }">{{ row.highlights?.join(" · ") || "—" }}</template>
      <template #cell-bestCreator="{ row }">{{ row.highlights?.[0] || "—" }}</template>
      <template #actions><RowActions :actions="detailActions" /></template>
    </DataTable>
  </div>
</template>

<style scoped>
.analytics-page {
  --analytics-card-padding: var(--tcrm-space-4);
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-auto-rows: max-content;
  align-content: start;
  gap: var(--tcrm-space-4);
}
.analytics-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
}
.analytics-note {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
}
.analytics-topic {
  min-width: 0;
  display: grid;
  gap: var(--tcrm-space-3);
}
.analytics-topic h2 {
  color: var(--tcrm-color-text);
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  font-weight: 600;
  margin: 0;
}
.topic-grid {
  display: grid;
  gap: var(--tcrm-space-4);
}
.topic-grid--two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.topic-grid--three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.ledger-comparison {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) auto minmax(0, 1.35fr) minmax(0, 1fr);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  overflow: hidden;
}
.ledger-comparison__panel {
  min-width: 0;
  padding: var(--tcrm-space-3) var(--analytics-card-padding);
}
.ledger-comparison__bridge,
.ledger-comparison__settlement,
.ledger-comparison__note {
  border-inline-start: 1px solid var(--tcrm-color-border);
}
.ledger-comparison__panel h3 {
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  font-weight: 600;
  margin: 0 0 var(--tcrm-space-1);
}
.ledger-comparison__bridge {
  display: grid;
  place-content: center;
  justify-items: center;
  gap: var(--tcrm-space-1);
  padding: var(--tcrm-space-2);
  color: var(--tcrm-color-primary-active);
  background: var(--tcrm-color-muted);
}
.ledger-comparison__bridge span {
  max-width: 10ch;
  text-align: center;
}
.ledger-comparison__transaction {
  background: var(--tcrm-color-option-selected-bg);
  color: var(--tcrm-color-primary-text);
}
.ledger-comparison__settlement {
  background: var(--tcrm-color-success-bg);
  color: var(--tcrm-color-success);
}
.ledger-comparison__note {
  display: grid;
  align-content: center;
  background: var(--tcrm-color-warning-bg);
  color: var(--tcrm-color-warning);
}
.ledger-comparison__note p,
.ledger-comparison span {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  margin: 0;
}
.ledger-comparison__hero {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-1) var(--tcrm-space-2);
}
.ledger-comparison__values > div {
  display: grid;
}
.ledger-comparison__hero strong {
  font-size: var(--tcrm-font-size-dashboard-metric);
  line-height: var(--tcrm-line-height-dashboard-metric);
}
.ledger-comparison__values {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: var(--tcrm-space-1) var(--tcrm-space-3);
  margin-top: var(--tcrm-space-1);
}
.ledger-comparison__operator {
  align-self: flex-end;
  padding-block-end: var(--tcrm-space-1);
}
.ledger-comparison__values strong {
  font-size: var(--tcrm-font-size-dashboard-comparison);
  line-height: var(--tcrm-line-height-dashboard-comparison);
}
.ledger-comparison strong {
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.analytics-number--net {
  color: var(--tcrm-color-table-text);
  font-weight: 600;
}
.ledger-comparison__refund strong,
.analytics-number--loss {
  color: var(--tcrm-color-danger);
}
.analytics-number--unavailable {
  color: var(--tcrm-color-table-secondary);
}
.analytics-ranking.is-page-scroll {
  max-height: none;
}
.analytics-ranking.is-page-scroll :deep(> .data-table__body) {
  flex: 0 0 auto;
}
.analytics-ranking :deep(.el-table__header th.data-table__highlighted),
.analytics-ranking :deep(.el-table__body td.data-table__highlighted) {
  background: var(--tcrm-color-option-selected-bg);
}
.analytics-ranking :deep(.data-table__toolbar) {
  justify-content: flex-start;
}
.analytics-ranking :deep(.data-table__page-title) {
  margin-inline-end: auto;
}
.analytics-ranking__title {
  margin: 0;
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
}
.analytics-ranking.density-default :deep(.el-table td.el-table__cell) {
  height: calc(var(--tcrm-line-height-table) + 2 * var(--tcrm-space-4));
  padding-block: var(--tcrm-space-2);
}
.analytics-badges {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--tcrm-space-1);
}
.analytics-identity {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.analytics-identity__text {
  display: grid;
  justify-items: start;
  min-width: 0;
  white-space: normal;
  overflow-wrap: anywhere;
}
.analytics-identity__text small,
.analytics-identity__text :deep(.copy-text) {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
.analytics-identity__avatar,
.product-thumb {
  flex: 0 0 var(--tcrm-control-height);
  width: var(--tcrm-control-height);
  height: var(--tcrm-control-height);
  border-radius: var(--tcrm-radius-control);
}
.analytics-identity__avatar {
  display: grid;
  place-items: center;
  background: var(--tcrm-color-option-selected-bg);
  color: var(--tcrm-color-primary-text);
  font-weight: 600;
}
.analytics-identity__avatar.is-product {
  background: var(--tcrm-color-order-band);
  color: var(--tcrm-color-primary-active);
}
.product-thumb {
  object-fit: cover;
}
.analytics-rank-badge {
  display: inline-grid;
  place-items: center;
  min-width: var(--tcrm-tag-min-height);
  min-height: var(--tcrm-tag-min-height);
  padding-inline: var(--tcrm-space-1);
  border-radius: 50%;
  background: var(--tcrm-color-muted);
  color: var(--tcrm-color-text-secondary);
  font-weight: 600;
}
.analytics-rank-badge[data-rank="1"] {
  background: var(--tcrm-color-option-selected-bg);
  color: var(--tcrm-color-primary-text);
}
.analytics-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-1);
}
.analytics-number {
  font-variant-numeric: tabular-nums;
}
.sort-controls,
.coverage-values {
  display: flex;
  justify-content: space-evenly;
  gap: var(--tcrm-space-1);
}
.coverage-values__separator {
  color: var(--tcrm-color-text-secondary);
}
.sort-controls button {
  background: transparent;
  border: 0;
  cursor: pointer;
  color: inherit;
  font: inherit;
  padding: 0;
  min-height: var(--tcrm-control-height);
}
.sort-controls button.selected {
  color: var(--el-color-primary);
}
.sort-controls button:focus-visible {
  outline: var(--tcrm-focus-width) solid var(--tcrm-color-primary);
  outline-offset: var(--tcrm-focus-width);
}
@media (min-width: 1440px) and (min-height: 900px) {
  .analytics-page {
    --analytics-card-padding: var(--tcrm-space-5);
  }
}
@media (max-width: 767px) {
  .analytics-page {
    --analytics-card-padding: var(--tcrm-space-3);
  }
  .sort-controls button {
    min-height: var(--tcrm-control-height-mobile);
  }
}
@media (max-width: 1100px) {
  .analytics-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .analytics-cards,
  .topic-grid,
  .ledger-comparison {
    grid-template-columns: minmax(0, 1fr);
  }
  .ledger-comparison__bridge {
    display: none;
  }
  .ledger-comparison__settlement,
  .ledger-comparison__note {
    border-inline-start: 0;
    border-top: 1px solid var(--tcrm-color-border);
  }
}
</style>
