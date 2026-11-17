<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Goods } from "@element-plus/icons-vue";
import { useI18n } from "vue-i18n";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import CopyText from "@/components/CopyText.vue";
import ChartPanel from "@/components/dashboard/ChartPanel.vue";
import {
  queryAnalyticsDetail,
  queryInviterCreators,
  queryProductBreakdown,
  queryShopSettlementFlows,
  type ActivityKind,
  type Board,
  type DailyAmount,
  type DetailQuery,
  type EvidenceRow,
  type InviterCreatorRow,
  type Metric,
  type ProductBreakdown,
  type ProductGroup,
  type RankRow,
  type SettlementFlowRow
} from "@/api/analytics";
import { formatAmountWithCurrency } from "@/utils/format";
import { accessOf } from "@/utils/permission";
import { useUserStore } from "@/stores/user";
import { readPageSize } from "@/utils/table-prefs";
import { shiftDate } from "./board";
import MetricCard from "./MetricCard.vue";

const props = defineProps<{
  board: Board;
  row: RankRow;
  name: string;
  filters: DetailQuery;
  dataScope?: "ALL" | "OWN";
}>();
const emit = defineEmits<{ close: [] }>();
const { t, te, locale } = useI18n();
const user = useUserStore();
const access = computed(() => accessOf(user.user));
const tab = ref<
  "overview" | "creators" | "currentTeam" | "settlements" | ActivityKind | ProductBreakdown
>("overview");
const isCreatorList = computed(() => tab.value === "creators" || tab.value === "currentTeam");
const isBreakdown = computed(() => tab.value === "SKUS" || tab.value === "CREATORS");
const page = ref(1);
const size = ref(20);
const tableId = computed(() => `analytics-detail-${shop.value ? "shop-" : ""}${tab.value}`);
const daily = ref<DailyAmount[]>([]);
const rows = ref<EvidenceRow[]>([]);
const flowRows = ref<SettlementFlowRow[]>([]);
const creatorRows = ref<InviterCreatorRow[]>([]);
const productGroups = ref<ProductGroup[]>([]);
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
let request = 0;

const shop = computed(() => props.board === "shop-performance");
const settlementAllowed = computed(() => {
  const { kolId, creatorKeyword, inviterId, inviterKeyword, productKeyword } = props.filters;
  return (
    props.dataScope === "ALL" &&
    !kolId &&
    !creatorKeyword &&
    !inviterId &&
    !inviterKeyword &&
    !productKeyword
  );
});
const local = (key: string) => t(`analytics.shopDrawer.${key}`);
const shopShortcut = computed(() => ({
  name: "AnalyticsBestSellers",
  query: {
    shopId: props.row.shopId,
    startDate: props.filters.startDate,
    endDate: props.filters.endDate
  }
}));
function tabTitle(kind: ActivityKind): string {
  const label = t(`analytics.drilldown.${kind === "ORDERS" ? "orders" : "refunds"}`);
  if (!shop.value) return label;
  const count = kind === "ORDERS" ? props.row.paidOrderCount : props.row.refundedOrderCount;
  return t("analytics.shopDrawer.countedTab", {
    label,
    count: new Intl.NumberFormat(locale.value).format(BigInt(String(count ?? 0)))
  });
}
function termHint(key: string): string {
  const drawerPath = `analytics.shopDrawer.definitions.${key}`;
  if (shop.value && te(drawerPath)) return t(drawerPath);
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
const cards = computed(() => {
  const metrics = metricKeys.flatMap(key => {
    const integer = key === "units" || key === "paidOrderCount";
    const value = props.row.metrics?.[key] ?? props.row[key];
    if (!shop.value && key === "amountRefundRate" && value == null) {
      return [];
    }
    let formatted = "—";
    if (typeof value === "string" || typeof value === "number") {
      formatted = integer
        ? new Intl.NumberFormat(locale.value).format(BigInt(String(value)))
        : key === "amountRefundRate"
          ? new Intl.NumberFormat(locale.value, {
              style: "percent",
              maximumFractionDigits: 2
            }).format(Number(value))
          : formatAmountWithCurrency(String(value));
    }
    const hint = termHint(key);
    const label = shop.value && key === "averageOrderAmount" ? local(key) : t(`analytics.${key}`);
    return [{ key, label, value: formatted, hint }];
  });
  if (!shop.value) return metrics;
  const format = (value: unknown) =>
    typeof value === "number" || typeof value === "string"
      ? new Intl.NumberFormat(locale.value).format(BigInt(String(value)))
      : "—";
  return [
    ...metrics,
    {
      key: "soldProducts",
      label: local("soldProducts"),
      value: t("analytics.shopDrawer.soldProductCounts", {
        spu: format(props.row.spuCount),
        sku: format(props.row.skuCount)
      }),
      hint: termHint("soldProducts")
    }
  ];
});
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

const shopColumns = computed<DataColumn[]>(() => {
  const product = {
    key: "product",
    label: t("analytics.drilldown.product"),
    minWidth: 198,
    align: "left"
  } as const;
  if (tab.value === "settlements")
    return [
      { key: "flow", label: local("flowTime"), minWidth: 172, align: "left" },
      { ...product, label: t("analytics.product") },
      { key: "orderNo", label: t("analytics.drilldown.orderNo"), minWidth: 150, align: "left" },
      { key: "amountType", label: local("amountType"), minWidth: 128 },
      { key: "direction", label: local("direction"), minWidth: 80 },
      { key: "amount", label: local("amount"), minWidth: 112, amount: true }
    ];
  const refund = tab.value === "REFUNDS";
  return [
    product,
    {
      key: "orderNo",
      label: local(refund ? "orderRefundTime" : "orderPaidTime"),
      minWidth: 188,
      align: "left"
    },
    { key: "shopName", label: t("analytics.shop"), minWidth: 98, align: "left" },
    { key: "creatorName", label: t("analytics.creator"), minWidth: 120, align: "left" },
    { key: "channelSpu", label: local("channelSpu"), minWidth: 122, align: "left" },
    refund
      ? { key: "amount", label: t("analytics.completedRefundAmount"), minWidth: 116, amount: true }
      : { key: "units", label: local("quantity"), minWidth: 72 }
  ];
});

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
      : shop.value
        ? shopColumns.value
        : columns.value
);
const detailHeaders = computed(() =>
  tableColumns.value.flatMap(column => {
    const refund = tab.value === "REFUNDS";
    const key = column.key;
    const localKey =
      tab.value === "settlements" && key === "amount"
        ? "settlement"
        : shop.value && key === "orderNo" && tab.value !== "settlements"
          ? refund
            ? "orderRefundTime"
            : "orderPaidTime"
          : key;
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
    const hint =
      shop.value && te(`analytics.shopDrawer.definitions.${localKey}`)
        ? termHint(localKey)
        : evidenceKey
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
    if (section === "settlements") {
      if (!settlementAllowed.value) return;
      const result = await queryShopSettlementFlows({
        ...props.filters,
        page: page.value,
        size: size.value
      });
      if (id === request) {
        flowRows.value = result.items;
        total.value = Number(result.total);
      }
      return;
    }
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
      flowRows.value = [];
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
    flowRows.value = [];
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
      <section v-if="shop" class="analytics-detail__identity analytics-detail__identity--shop">
        <span class="analytics-detail__shop-avatar" aria-hidden="true">{{
          Array.from(name)[0]
        }}</span>
        <div class="analytics-detail__shop-info">
          <div class="analytics-detail__shop-name">
            <h2>{{ name }}</h2>
            <el-tag size="small" type="info" effect="light">{{ t("analytics.weidian") }}</el-tag>
            <el-tag
              v-if="row.status != null"
              size="small"
              :type="row.status === 1 ? 'success' : 'info'"
              effect="light"
            >
              {{ t(row.status === 1 ? "common.enabled" : "common.disabled") }}
            </el-tag>
          </div>
          <div class="analytics-detail__metadata">
            <CopyText v-if="identityCode" :value="identityCode" />
            <span v-if="identityCode && row.weidianShopId" aria-hidden="true">·</span>
            <CopyText v-if="row.weidianShopId" :value="row.weidianShopId" />
          </div>
          <small v-if="row.shopUpdatedAt"
            >{{ local("updated") }} {{ eventTime.format(new Date(row.shopUpdatedAt)) }}</small
          >
        </div>
        <div class="analytics-detail__shop-actions">
          <RouterLink
            v-if="row.shopId"
            :to="shopShortcut"
            class="el-button el-button--primary"
            @click="emit('close')"
          >
            {{ local("viewBestSellers") }}
          </RouterLink>
          <small class="analytics-detail__period"
            >{{ filters.startDate }} – {{ filters.endDate }} ·
            {{ t("analytics.drilldown.beijingTime") }}</small
          >
        </div>
      </section>
      <section v-else class="analytics-detail__identity">
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
        <el-tab-pane v-if="access.can('order:view')" :label="tabTitle('ORDERS')" name="ORDERS" />
        <el-tab-pane v-if="access.can('order:view')" :label="tabTitle('REFUNDS')" name="REFUNDS" />
        <el-tab-pane
          v-if="shop && access.can('order:view')"
          :label="local('settlement')"
          name="settlements"
        />
      </el-tabs>
      <template v-if="tab === 'overview'">
        <div class="analytics-detail__cards">
          <MetricCard
            v-for="card in cards"
            :key="card.key"
            :class="{ 'analytics-detail__coverage': card.key === 'soldProducts' }"
            :label="card.label"
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
      <el-empty
        v-else-if="tab === 'settlements' && !settlementAllowed"
        :description="local('settlementScope')"
      />
      <DataTable
        v-else
        :key="tab"
        :table-id="tableId"
        :rows="
          tab === 'settlements'
            ? flowRows
            : isBreakdown
              ? productGroups
              : isCreatorList
                ? creatorRows
                : rows
        "
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
        <template v-if="shop" #cell-product="{ row: entry }">
          <div v-if="entry.product" class="analytics-detail__product">
            <img v-if="entry.imageUrl" :src="entry.imageUrl" alt="" loading="lazy" />
            <span v-else class="analytics-detail__product-placeholder" aria-hidden="true"
              ><el-icon><Goods /></el-icon
            ></span>
            <div class="analytics-detail__stack">
              <strong :title="entry.product">{{ entry.product }}</strong>
              <small v-if="entry.sku" :title="entry.sku">{{ entry.sku }}</small>
            </div>
          </div>
          <span v-else>{{
            entry.itemCount > 1
              ? t("analytics.shopDrawer.multipleItems", { count: entry.itemCount })
              : "—"
          }}</span>
        </template>
        <template #cell-orderNo="{ row: entry }">
          <div class="analytics-detail__stack">
            <CopyText :value="entry.orderNo" />
            <small v-if="shop && tab !== 'settlements'">{{
              eventTime.format(new Date(entry.eventTime))
            }}</small>
            <small v-if="shop && tab === 'REFUNDS' && entry.refundNo">
              {{ t("analytics.drilldown.refundNo") }} <CopyText :value="entry.refundNo" />
            </small>
          </div>
        </template>
        <template #cell-shopName>{{ name }}</template>
        <template #cell-creatorName="{ row: entry }">{{ entry.creatorName || "—" }}</template>
        <template #cell-channelSpu="{ row: entry }">
          <div class="analytics-detail__stack">
            <el-tag size="small" type="info" effect="light">{{ t("analytics.weidian") }}</el-tag>
            <CopyText v-if="entry.spuId" :value="entry.spuId" />
            <small v-else>—</small>
          </div>
        </template>
        <template #cell-flow="{ row: entry }">
          <div class="analytics-detail__stack">
            <CopyText :value="entry.id" />
            <small>{{ eventTime.format(new Date(entry.eventTime)) }}</small>
          </div>
        </template>
        <template #cell-amountType="{ row: entry }">{{
          local(entry.amountType === "REFUND" ? "deductedType" : "addedType")
        }}</template>
        <template #cell-direction="{ row: entry }">
          <el-tag
            size="small"
            :type="entry.amountType === 'REFUND' ? 'danger' : 'success'"
            effect="light"
          >
            {{ local(entry.amountType === "REFUND" ? "deducted" : "added") }}
          </el-tag>
        </template>
        <template #cell-refundNo="{ row: entry }"><CopyText :value="entry.refundNo" /></template>
        <template #cell-amount="{ row: entry }">
          <span
            :class="{
              'analytics-detail__amount--added':
                tab === 'settlements' && entry.amountType === 'INCREASE',
              'analytics-detail__amount--deducted':
                shop && (tab === 'REFUNDS' || entry.amountType === 'REFUND')
            }"
            >{{ entry.amount == null ? "—" : formatAmountWithCurrency(String(entry.amount)) }}</span
          >
        </template>
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
.analytics-detail__identity--shop {
  gap: var(--tcrm-space-4);
}
.analytics-detail__shop-avatar {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  border-radius: var(--tcrm-radius-card);
  background: linear-gradient(135deg, #6555d8, #236ecd);
  color: white;
  font-size: var(--tcrm-font-size-title);
  font-weight: 600;
  flex: none;
}
.analytics-detail__shop-info,
.analytics-detail__shop-actions,
.analytics-detail__stack {
  min-width: 0;
  display: grid;
  justify-items: start;
  gap: var(--tcrm-space-1);
}
.analytics-detail__shop-name {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.analytics-detail__shop-info small,
.analytics-detail__shop-actions small,
.analytics-detail__stack small {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
}
.analytics-detail__shop-actions {
  margin-inline-start: auto;
  justify-items: end;
}
.analytics-detail__product {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.analytics-detail__product img,
.analytics-detail__product-placeholder {
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  object-fit: cover;
  border-radius: var(--tcrm-radius-control);
  border: 1px solid var(--tcrm-color-border);
}
.analytics-detail__product-placeholder {
  display: grid;
  place-items: center;
  background: var(--tcrm-color-option-selected-bg);
  color: var(--tcrm-color-primary-text);
}
.analytics-detail__product .analytics-detail__stack > * {
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.analytics-detail__amount--added {
  color: var(--tcrm-color-success);
  font-weight: 600;
}
.analytics-detail__amount--deducted {
  color: var(--tcrm-color-danger);
  font-weight: 600;
}
.analytics-detail__coverage :deep(strong) {
  font-size: var(--tcrm-font-size-section);
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
  .analytics-detail__shop-actions {
    width: 100%;
    margin-inline-start: 0;
    justify-items: start;
  }
  .analytics-detail__shop-actions .analytics-detail__period {
    margin-inline-start: 0;
  }
  .analytics-detail__cards {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
