<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import CopyText from "@/components/CopyText.vue";
import ChartPanel from "@/components/dashboard/ChartPanel.vue";
import {
  queryAnalyticsDetail,
  queryInviterCreators,
  queryProductBreakdown,
  type ActivityKind,
  type Board,
  type DailyAmount,
  type DetailQuery,
  type EvidenceRow,
  type InviterCreatorRow,
  type Metric,
  type ProductBreakdown,
  type ProductGroup,
  type RankRow
} from "@/api/analytics";
import { formatAmountWithCurrency } from "@/utils/format";
import { accessOf } from "@/utils/permission";
import { useUserStore } from "@/stores/user";
import { readPageSize } from "@/utils/table-prefs";
import { shiftDate } from "./board";
import MetricCard from "./MetricCard.vue";

const props = defineProps<{ board: Board; row: RankRow; name: string; filters: DetailQuery }>();
const emit = defineEmits<{ close: [] }>();
const { t, te, locale } = useI18n();
const user = useUserStore();
const access = computed(() => accessOf(user.user));
const tab = ref<"overview" | "creators" | "currentTeam" | ActivityKind | ProductBreakdown>(
  "overview"
);
const isCreatorList = computed(() => tab.value === "creators" || tab.value === "currentTeam");
const isBreakdown = computed(() => tab.value === "SKUS" || tab.value === "CREATORS");
const page = ref(1);
const size = ref(20);
const tableId = computed(() => `analytics-detail-${tab.value}`);
const daily = ref<DailyAmount[]>([]);
const rows = ref<EvidenceRow[]>([]);
const creatorRows = ref<InviterCreatorRow[]>([]);
const productGroups = ref<ProductGroup[]>([]);
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
let request = 0;

const shop = computed(() => props.board === "shop-performance");
function termHint(key: string): string {
  const path = `analytics.${props.board}.definitions.${key}`;
  return te(path) ? t(path) : "";
}
const heading = computed(() => t(`analytics.${props.board}.detailTitle`));
const identityCode = computed(() =>
  props.board === "best-sellers" ? props.row.sourceItemId : props.row.shopCode || props.row.code
);
const eventTime = computed(
  () =>
    new Intl.DateTimeFormat(locale.value, {
      timeZone: "Asia/Shanghai",
      dateStyle: "short",
      timeStyle: "medium",
      hourCycle: "h23"
    })
);
const metricKeys: Metric[] = [
  "salesAmount",
  "completedRefundAmount",
  "netSalesAmount",
  "paidOrderCount",
  "units",
  "averageOrderAmount",
  "amountRefundRate"
];
const cards = computed(() =>
  metricKeys.flatMap(key => {
    // The shop ranking exposes metrics directly on the row.
    const value = props.row.metrics?.[key] ?? props.row[key];
    if (!shop.value && key === "amountRefundRate" && value == null) {
      return [];
    }
    let formatted = "—";
    if (typeof value === "string" || typeof value === "number") {
      formatted =
        key === "units" || key === "paidOrderCount"
          ? new Intl.NumberFormat(locale.value).format(BigInt(String(value)))
          : key === "amountRefundRate"
            ? new Intl.NumberFormat(locale.value, {
                style: "percent",
                maximumFractionDigits: 2
              }).format(Number(value))
            : formatAmountWithCurrency(String(value));
    }
    const hint = termHint(key);
    return [{ key, value: formatted, hint }];
  })
);
const columns = computed<DataColumn[]>(() => [
  { key: "orderNo", label: t("analytics.drilldown.orderNo"), minWidth: 160, align: "left" },
  ...(tab.value === "REFUNDS"
    ? [{ key: "refundNo", label: t("analytics.drilldown.refundNo"), minWidth: 150 }]
    : []),
  { key: "product", label: t("analytics.drilldown.product"), minWidth: 185, align: "left" },
  { key: "sku", label: "SKU", minWidth: 104 },
  ...(tab.value === "ORDERS" ? [{ key: "units", label: t("analytics.units"), minWidth: 76 }] : []),
  {
    key: "amount",
    label: t(
      tab.value === "REFUNDS" ? "analytics.completedRefundAmount" : "analytics.drilldown.itemAmount"
    ),
    minWidth: 130,
    amount: true
  },
  {
    key: "eventTime",
    label: t(
      tab.value === "REFUNDS" ? "analytics.drilldown.refundTime" : "analytics.drilldown.payTime"
    ),
    minWidth: 168
  }
]);

const creatorColumns = computed<DataColumn[]>(() => [
  { key: "name", label: t("analytics.kol-ranking.identity"), minWidth: 180, align: "left" },
  { key: "enabled", label: t("analytics.status"), minWidth: 90 },
  {
    key: "paidOrderCount",
    prop: "metrics.paidOrderCount",
    label: t("analytics.paidOrderCount"),
    minWidth: 125
  },
  { key: "units", prop: "metrics.units", label: t("analytics.units"), minWidth: 90 },
  { key: "net", label: t("analytics.netSalesAmount"), amount: true, minWidth: 130 }
]);

const groupColumns = computed<DataColumn[]>(() => [
  {
    key: "name",
    label: t(tab.value === "SKUS" ? "analytics.skuCount" : "analytics.drilldown.creatorName"),
    minWidth: 180,
    align: "left"
  },
  { key: "paidOrderCount", label: t("analytics.paidOrderCount"), minWidth: 115 },
  { key: "units", label: t("analytics.units"), minWidth: 90 },
  ...(["salesAmount", "completedRefundAmount", "netSalesAmount"] as const).map(key => ({
    key,
    label: t(`analytics.${key}`),
    amount: true,
    minWidth: 145
  }))
]);

const tableColumns = computed(() =>
  isBreakdown.value
    ? groupColumns.value
    : isCreatorList.value
      ? creatorColumns.value
      : columns.value
);
const detailHeaders = computed(() =>
  tableColumns.value.flatMap(column => {
    const refund = tab.value === "REFUNDS";
    const key = column.key;
    const evidenceKey =
      key === "amount"
        ? refund
          ? null
          : "itemAmount"
        : key === "eventTime"
          ? refund
            ? "eventRefund"
            : "eventPay"
          : ["orderNo", "refundNo", "sku", "product"].includes(key)
            ? key
            : null;
    const hint = evidenceKey
      ? t(`analytics.evidenceDefinitions.${evidenceKey}`)
      : termHint(
          key === "amount" ? "completedRefundAmount" : key === "net" ? "netSalesAmount" : key
        );
    return hint ? [{ ...column, hint }] : [];
  })
);

const chart = computed(() => {
  if (!daily.value.length) return null;
  const values = new Map(daily.value.map(day => [day.day, day]));
  const days = [];
  for (let day = props.filters.startDate; day <= props.filters.endDate; day = shiftDate(day, 1)) {
    days.push(day);
  }
  return {
    legend: { top: 0 },
    tooltip: {
      trigger: "axis",
      valueFormatter: (amount: unknown) => formatAmountWithCurrency(String(amount))
    },
    xAxis: { type: "category", data: days, axisLabel: { hideOverlap: true } },
    yAxis: { type: "value" },
    series: [
      {
        name: t("analytics.salesAmount"),
        type: "line",
        showSymbol: days.length < 15,
        data: days.map(day => Number(values.get(day)?.sales ?? 0))
      },
      {
        name: t("analytics.completedRefundAmount"),
        type: "line",
        showSymbol: days.length < 15,
        data: days.map(day => Number(values.get(day)?.refunds ?? 0))
      }
    ]
  };
});

async function load(): Promise<void> {
  const id = ++request;
  const section = tab.value;
  const kind = section === "ORDERS" || section === "REFUNDS" ? section : undefined;
  loading.value = true;
  failed.value = false;
  try {
    if (section === "SKUS" || section === "CREATORS") {
      const result = await queryProductBreakdown({
        ...props.filters,
        group: section,
        page: page.value,
        size: size.value
      });
      if (id === request) {
        productGroups.value = result.items;
        total.value = Number(result.total);
      }
      return;
    }
    if (section === "creators" || section === "currentTeam") {
      const result = await queryInviterCreators({
        ...props.filters,
        currentTeam: section === "currentTeam",
        page: page.value,
        size: size.value
      });
      if (id === request) {
        creatorRows.value = result.items;
        total.value = Number(result.total);
      }
      return;
    }
    const result = await queryAnalyticsDetail(props.board, {
      ...props.filters,
      kind,
      page: kind ? page.value : undefined,
      size: kind ? size.value : undefined
    });
    if (id !== request) return;
    daily.value = result.daily ?? [];
    rows.value = result.activity?.items ?? [];
    total.value = Number(result.activity?.total ?? 0);
  } catch {
    if (id === request) {
      failed.value = true;
      daily.value = [];
      rows.value = [];
      total.value = 0;
    }
  } finally {
    if (id === request) loading.value = false;
  }
}

watch(
  [tab, () => props.filters],
  () => {
    page.value = 1;
    size.value = readPageSize(tableId.value, 20);
    daily.value = [];
    rows.value = [];
    creatorRows.value = [];
    productGroups.value = [];
    total.value = 0;
    void load();
  },
  { immediate: true }
);

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
</script>

<template>
  <el-drawer
    :model-value="true"
    :title="heading"
    size="min(1180px, 100vw)"
    class="analytics-detail"
    @close="emit('close')"
  >
    <div class="analytics-detail__body">
      <section class="analytics-detail__identity">
        <img v-if="row.imageUrl" :src="row.imageUrl" alt="" class="analytics-detail__image" />
        <div>
          <h2>{{ name }}</h2>
          <div class="analytics-detail__metadata">
            <CopyText v-if="identityCode" :value="identityCode" />
            <span v-if="row.related">{{ row.related }}</span>
          </div>
        </div>
        <time class="analytics-detail__period"
          >{{ filters.startDate }} – {{ filters.endDate }} ·
          {{ t("analytics.drilldown.beijingTime") }}</time
        >
      </section>

      <el-tabs v-model="tab">
        <el-tab-pane :label="t('analytics.drilldown.overview')" name="overview" />
        <el-tab-pane
          v-if="board === 'best-sellers'"
          :label="t('analytics.drilldown.skus')"
          name="SKUS"
        />
        <el-tab-pane
          v-if="board === 'best-sellers'"
          :label="t('analytics.drilldown.contribution')"
          name="CREATORS"
        />
        <el-tab-pane
          v-if="board === 'inviter-performance' && row.id !== '0' && access.can('kol:view')"
          :label="t('analytics.drilldown.creators')"
          name="creators"
        />
        <el-tab-pane
          v-if="board === 'inviter-performance' && row.id !== '0' && access.can('kol:view')"
          :label="t('analytics.drilldown.currentTeam')"
          name="currentTeam"
        />
        <el-tab-pane
          v-if="access.can('order:view')"
          :label="t('analytics.drilldown.orders')"
          name="ORDERS"
        />
        <el-tab-pane
          v-if="access.can('order:view')"
          :label="t('analytics.drilldown.refunds')"
          name="REFUNDS"
        />
      </el-tabs>
      <template v-if="tab === 'overview'">
        <div class="analytics-detail__cards">
          <MetricCard
            v-for="card in cards"
            :key="card.key"
            :label="t(`analytics.${card.key}`)"
            :value="card.value"
            :hint="card.hint"
          />
        </div>
        <section class="analytics-detail__trend">
          <h3>{{ t("analytics.drilldown.daily") }}</h3>
          <p>
            <template
              v-for="(key, index) in ['salesAmount', 'completedRefundAmount'] as const"
              :key="key"
            >
              <span v-if="index" aria-hidden="true"> · </span>
              <el-tooltip
                :content="termHint(key)"
                :trigger="['hover', 'focus']"
                popper-class="analytics-metric-definition"
              >
                <span tabindex="0" class="analytics-detail__term">{{ t(`analytics.${key}`) }}</span>
              </el-tooltip>
            </template>
          </p>
          <ChartPanel
            :option="chart"
            :label="t('analytics.drilldown.daily')"
            :loading="loading"
            :failed="failed"
            @retry="load"
          />
        </section>
      </template>
      <DataTable
        v-else
        :key="tab"
        :table-id="tableId"
        :rows="isBreakdown ? productGroups : isCreatorList ? creatorRows : rows"
        :columns="tableColumns"
        :row-key="isBreakdown ? 'code' : 'id'"
        :loading="loading"
        :failed="failed"
        :page="page"
        :size="size"
        :page-sizes="[20, 50, 100]"
        :total="total"
        scroll-with-page
        plain
        @retry="load"
        @update:page="changePage"
        @update:size="changeSize"
      >
        <template v-for="column in detailHeaders" :key="column.key" #[`header-${column.key}`]>
          <el-tooltip
            :content="column.hint"
            :trigger="['hover', 'focus']"
            popper-class="analytics-metric-definition"
          >
            <span tabindex="0" class="analytics-detail__term">{{ column.label }}</span>
          </el-tooltip>
        </template>
        <template #cell-name="{ row: entry }">
          <div>
            {{
              entry.name || (isCreatorList && entry.id === "0" ? t("analytics.unassigned") : "—")
            }}
          </div>
          <CopyText v-if="entry.code && tab !== 'CREATORS'" :value="entry.code" />
          <div v-if="tab === 'creators' && (entry.currentInviter || entry.transferred)">
            {{
              entry.currentInviter
                ? t("analytics.drilldown.currentInviter", { name: entry.currentInviter })
                : t("analytics.drilldown.transferred")
            }}
          </div>
        </template>
        <template #cell-enabled="{ row: entry }">
          <span v-if="entry.enabled == null">—</span>
          <el-tag v-else :type="entry.enabled ? 'success' : 'info'" size="small">
            {{ t(entry.enabled ? "analytics.enabled" : "analytics.disabled") }}
          </el-tag>
        </template>
        <template #cell-paidOrderCount="{ row: entry }">{{
          entry.metrics?.paidOrderCount ?? entry.paidOrderCount
        }}</template>
        <template #cell-units="{ row: entry }">{{ entry.metrics?.units ?? entry.units }}</template>
        <template #cell-salesAmount="{ row: entry }">{{
          formatAmountWithCurrency(String(entry.salesAmount))
        }}</template>
        <template #cell-completedRefundAmount="{ row: entry }">{{
          formatAmountWithCurrency(String(entry.completedRefundAmount))
        }}</template>
        <template #cell-netSalesAmount="{ row: entry }">{{
          formatAmountWithCurrency(String(entry.netSalesAmount))
        }}</template>
        <template #cell-net="{ row: entry }">
          {{ formatAmountWithCurrency(String(entry.metrics.netSalesAmount)) }}
        </template>
        <template #cell-orderNo="{ row: entry }"><CopyText :value="entry.orderNo" /></template>
        <template #cell-refundNo="{ row: entry }"><CopyText :value="entry.refundNo" /></template>
        <template #cell-amount="{ row: entry }">{{
          formatAmountWithCurrency(String(entry.amount))
        }}</template>
        <template #cell-eventTime="{ row: entry }">{{
          eventTime.format(new Date(entry.eventTime))
        }}</template>
      </DataTable>
    </div>
  </el-drawer>
</template>

<style scoped>
.analytics-detail__term {
  cursor: help;
}
.analytics-detail__body {
  display: grid;
  gap: var(--tcrm-space-4);
}
.analytics-detail__identity,
.analytics-detail__trend {
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  padding: var(--tcrm-space-4);
}
.analytics-detail__identity {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-3);
}
.analytics-detail__identity h2,
.analytics-detail__trend h3 {
  margin: 0;
  overflow-wrap: anywhere;
}
.analytics-detail__metadata {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.analytics-detail__period,
.analytics-detail__trend p {
  color: var(--tcrm-color-text-secondary);
}
.analytics-detail__period {
  margin-inline-start: auto;
}
.analytics-detail__image {
  width: var(--tcrm-control-height-large);
  height: var(--tcrm-control-height-large);
  border-radius: var(--tcrm-radius-control);
  object-fit: cover;
}
.analytics-detail__cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-3);
}
@media (max-width: 860px) {
  .analytics-detail__cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 480px) {
  .analytics-detail__cards {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
