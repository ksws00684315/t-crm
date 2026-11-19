<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Goods } from "@element-plus/icons-vue";
import { useI18n } from "vue-i18n";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import CopyText from "@/components/CopyText.vue";
import AnalyticsDetailHeader from "./AnalyticsDetailHeader.vue";
import CreatorIdentity from "./CreatorIdentity.vue";
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
  type DetailSort,
  type DetailSortQuery,
  type EvidenceRow,
  type InviterCreatorRow,
  type Metric,
  type ProductBreakdown,
  type ProductGroup,
  type RankRow,
  type Scalar,
  type ShopDetailOverview,
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
const isBreakdown = computed(
  () => tab.value === "SKUS" || tab.value === "CREATORS" || tab.value === "SHOPS"
);
const page = ref(1);
const size = ref(20);
const tabSorts = ref<Partial<Record<typeof tab.value, DetailSortQuery>>>({});
const detailSort = computed(() => tabSorts.value[tab.value]);
const salesSorts: Partial<Record<string, DetailSort>> = {
  paidOrderCount: "PAID_ORDER_COUNT",
  units: "UNITS",
  soldUnits: "UNITS",
  salesAmount: "SALES_AMOUNT",
  orderPaidAmount: "SALES_AMOUNT",
  refundAmount: "REFUND_AMOUNT",
  completedRefundAmount: "REFUND_AMOUNT",
  net: "NET_SALES_AMOUNT",
  netSalesAmount: "NET_SALES_AMOUNT",
  netTransactionAmount: "NET_SALES_AMOUNT"
};
const tableId = computed(() => `analytics-detail-${shop.value ? "shop-" : ""}${tab.value}`);
const daily = ref<DailyAmount[]>([]);
const shopOverview = ref<ShopDetailOverview | null>(null);
const rows = ref<EvidenceRow[]>([]);
const flowRows = ref<SettlementFlowRow[]>([]);
const creatorRows = ref<InviterCreatorRow[]>([]);
const productGroups = ref<ProductGroup[]>([]);
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
let request = 0;

const shop = computed(() => props.board === "shop-performance");
const product = computed(() => props.board === "best-sellers");
const productOrderCounts = ref<{ paidOrders: Scalar; refundedOrders: Scalar } | null>(null);
const productText = (key: string) => t(`analytics.productDrawer.${key}`);
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
  if (product.value) {
    const order = kind === "ORDERS";
    const label = productText(order ? "orders" : "refunds");
    const count = order
      ? productOrderCounts.value?.paidOrders
      : productOrderCounts.value?.refundedOrders;
    return count == null
      ? label
      : label + t("analytics.productDrawer.orderCountSuffix", { count: formatCount(count) });
  }
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
const identityCode = computed(() => {
  if (props.board === "best-sellers") return props.row.sourceItemId;
  if (shop.value) return props.row.shopCode || props.row.code;
  return props.row.code;
});
const eventTime = computed(
  () =>
    new Intl.DateTimeFormat(locale.value, {
      timeZone: "Asia/Shanghai",
      dateStyle: "short",
      timeStyle: "medium",
      hourCycle: "h23"
    })
);
const salesMetrics: Metric[] = ["salesAmount", "completedRefundAmount", "netSalesAmount"];
const orderMetrics: Metric[] = ["paidOrderCount", "units", "averageOrderAmount"];
const metricKeys = computed<Metric[]>(() =>
  shop.value
    ? [...salesMetrics, ...orderMetrics]
    : [...salesMetrics, ...orderMetrics, "amountRefundRate"]
);
function formatCount(value: unknown): string {
  return typeof value === "number" || typeof value === "string"
    ? new Intl.NumberFormat(locale.value).format(BigInt(String(value)))
    : "—";
}
function formatMoney(value: string | number | null | undefined): string {
  return value == null ? "—" : formatAmountWithCurrency(String(value));
}
const soldProductCounts = computed(() => ({
  spu: formatCount(props.row.spuCount),
  sku: formatCount(props.row.skuCount)
}));
function skuMoney(value: Scalar | null | undefined): string {
  if (value == null) return "—";
  const [whole, fraction = ""] = String(value).split(".");
  return formatMoney(`${whole}.${fraction.padEnd(2, "0")}`);
}
function formatRate(
  numerator: Scalar | null | undefined,
  denominator: Scalar | null | undefined
): string {
  if (numerator == null || denominator == null || Number(denominator) <= 0) return "—";
  return new Intl.NumberFormat(locale.value, {
    style: "percent",
    maximumFractionDigits: 2
  }).format(Number(numerator) / Number(denominator));
}
function unitPrice(sales: Scalar | null | undefined, units: Scalar | null | undefined): string {
  return sales != null && units != null && Number(units) > 0
    ? formatMoney((Number(sales) / Number(units)).toFixed(2))
    : "—";
}
const cards = computed(() => {
  if (product.value) {
    const m = props.row.metrics;
    const amount = (key: Metric) => formatMoney(m?.[key]);
    return [
      ...salesMetrics.map(key => ({
        key,
        label: t(`analytics.${key}`),
        value: amount(key),
        hint: termHint(key)
      })),
      {
        key: "settlementAmount",
        label: t("analytics.best-sellers.settlementAmount"),
        value: formatMoney(
          typeof props.row.settlementAmount === "string" ||
            typeof props.row.settlementAmount === "number"
            ? props.row.settlementAmount
            : null
        ),
        hint: termHint("settlementAmount")
      },
      {
        key: "amountRefundRate",
        label: t("analytics.amountRefundRate"),
        value: formatRate(m?.completedRefundAmount, m?.salesAmount),
        hint: termHint("amountRefundRate")
      },
      {
        key: "paidOrderCount",
        label: t("analytics.paidOrderCount"),
        value: formatCount(m?.paidOrderCount),
        hint: termHint("paidOrderCount")
      },
      {
        key: "units",
        label: t("analytics.units"),
        value: formatCount(m?.units),
        hint: termHint("units")
      },
      {
        key: "unitPrice",
        label: productText("unitPrice"),
        value: unitPrice(m?.salesAmount, m?.units),
        hint: productText("unitPriceHint")
      }
    ];
  }
  const metrics = metricKeys.value.flatMap(key => {
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
    const label =
      shop.value && (key === "averageOrderAmount" || key === "paidOrderCount")
        ? local(key)
        : t(`analytics.${key}`);
    return [{ key, label, value: formatted, hint }];
  });
  if (!shop.value) return metrics;
  return [
    ...metrics.slice(0, salesMetrics.length),
    {
      key: "settlementAmount",
      label: local("settlementAmount"),
      value: formatMoney(shopOverview.value?.settlementAmount),
      hint: termHint("settlementAmount")
    },
    ...metrics.slice(salesMetrics.length),
    {
      key: "soldProducts",
      label: local("soldProducts"),
      value: t("analytics.shopDrawer.soldProductCounts", soldProductCounts.value),
      hint: termHint("soldProducts")
    }
  ];
});
const productOrderColumns = computed<DataColumn[]>(() => {
  const refund = tab.value === "REFUNDS";
  return [
    {
      key: "orderNo",
      label: refund ? t("analytics.drilldown.orderNo") : local("orderPaidTime"),
      minWidth: refund ? 150 : 176,
      align: "left"
    },
    { key: "sku", label: "SKU", minWidth: 125, align: "left" },
    ...(refund ? [{ key: "eventTime", label: productText("refundTime"), minWidth: 156 }] : []),
    {
      key: "shopName",
      label: refund ? productText("sellingShop") : t("analytics.shop"),
      minWidth: 110
    },
    { key: "creatorName", label: t("analytics.creator"), minWidth: 144 },
    ...(refund ? [{ key: "spuId", label: local("spuId"), minWidth: 120 }] : []),
    { key: "transactionItemId", label: productText("transactionItemId"), minWidth: 120 },
    { key: "soldUnits", label: t("analytics.units"), minWidth: 72 },
    { key: "salesAmount", label: t("analytics.salesAmount"), minWidth: 106, amount: true }
  ];
});

const columns = computed<DataColumn[]>(() => [
  { key: "orderNo", label: t("analytics.drilldown.orderNo"), minWidth: 160, align: "left" },
  ...(tab.value === "REFUNDS"
    ? [{ key: "refundNo", label: t("analytics.drilldown.refundNo"), minWidth: 150 }]
    : []),
  { key: "product", label: t("analytics.drilldown.product"), minWidth: 185, align: "left" },
  { key: "sku", label: "SKU", minWidth: 104 },
  ...(props.board === "inviter-performance"
    ? [{ key: "creatorName", label: t("analytics.creator"), minWidth: 144 }]
    : []),
  ...(tab.value !== "REFUNDS" ? [{ key: "units", label: t("analytics.units"), minWidth: 76 }] : []),
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
      product,
      { key: "orderNo", label: t("analytics.drilldown.orderNo"), minWidth: 150, align: "left" },
      { key: "flow", label: local("flowNo"), minWidth: 130, align: "left" },
      { key: "eventTime", label: local("settlementTime"), minWidth: 176 },
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
    { key: "creatorName", label: t("analytics.creator"), minWidth: 144, align: "left" },
    { key: "spuId", label: local("spuId"), minWidth: 122, align: "left" },
    { key: "transactionItemId", label: local("transactionItemId"), minWidth: 145 },
    ...(refund
      ? [
          { key: "refundUnits", label: t("analytics.refundUnits"), minWidth: 90 },
          {
            key: "amount",
            label: t("analytics.completedRefundAmount"),
            minWidth: 116,
            amount: true
          }
        ]
      : [
          { key: "units", label: t("analytics.units"), minWidth: 72 },
          ...["orderPaidAmount", "refundAmount", "netTransactionAmount"].map(key => ({
            key,
            label: local(key),
            minWidth: 112,
            amount: true
          }))
        ])
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
    label:
      tab.value === "SKUS"
        ? productText("soldSku")
        : t(tab.value === "SHOPS" ? "analytics.shop" : "analytics.drilldown.creatorName"),
    minWidth: 180,
    align: "left"
  },
  ...(tab.value === "CREATORS"
    ? [{ key: "inviterName", label: productText("inviter"), minWidth: 130 }]
    : []),
  {
    key: "paidOrderCount",
    label: t(
      tab.value === "SKUS" ? "analytics.best-sellers.paidOrderCount" : "analytics.paidOrderCount"
    ),
    minWidth: 115
  },
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
        : product.value
          ? productOrderColumns.value
          : columns.value
);
const detailHeaders = computed(() =>
  tableColumns.value.flatMap(column => {
    const refund = tab.value === "REFUNDS";
    const key = column.key;
    const localKey =
      tab.value === "settlements" && key === "eventTime"
        ? "settlementTime"
        : tab.value === "settlements" && key === "amount"
          ? "settlement"
          : key === "orderNo" &&
              ((shop.value && tab.value !== "settlements") || (product.value && !refund))
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
      product.value && tab.value === "REFUNDS" && ["soldUnits", "salesAmount"].includes(key)
        ? productText("originalSaleHint")
        : (shop.value ||
              (product.value && key === "orderNo" && !refund) ||
              key === "refundAmount") &&
            te(`analytics.shopDrawer.definitions.${localKey}`)
          ? t(`analytics.shopDrawer.definitions.${localKey}`)
          : evidenceKey
            ? t(`analytics.evidenceDefinitions.${evidenceKey}`)
            : termHint(
                key === "amount" ? "completedRefundAmount" : key === "net" ? "netSalesAmount" : key
              );
    const sortBy =
      key === "amount" && tab.value === "ORDERS"
        ? "SALES_AMOUNT"
        : key === "amount" && refund
          ? "REFUND_AMOUNT"
          : salesSorts[key];
    const direction =
      sortBy && detailSort.value?.sortBy === sortBy ? detailSort.value.sortDirection : undefined;
    return hint || sortBy ? [{ ...column, hint, sortBy, direction }] : [];
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
    if (section === "SKUS" || section === "CREATORS" || section === "SHOPS") {
      const result = await queryProductBreakdown({
        ...props.filters,
        group: section,
        page: page.value,
        size: size.value,
        ...detailSort.value
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
        size: size.value,
        ...detailSort.value
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
      size: kind ? size.value : undefined,
      ...(kind ? detailSort.value : undefined)
    });
    if (id !== request) return;
    daily.value = result.daily ?? [];
    if (product.value && section === "overview") {
      productOrderCounts.value = result.productOrderCounts ?? null;
    }
    shopOverview.value = result.shopOverview ?? null;
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
  () => props.filters,
  () => {
    if (!product.value) return;
    productOrderCounts.value = null;
    tab.value = "overview";
  }
);

watch(
  [tab, () => props.filters],
  () => {
    page.value = 1;
    size.value = readPageSize(tableId.value, 20);
    daily.value = [];
    shopOverview.value = null;
    rows.value = [];
    flowRows.value = [];
    creatorRows.value = [];
    productGroups.value = [];
    total.value = 0;
    void load();
  },
  { immediate: true }
);

function sortDetail(sortBy: DetailSort): void {
  const next =
    detailSort.value?.sortBy === sortBy && detailSort.value.sortDirection === "desc"
      ? "asc"
      : "desc";
  tabSorts.value[tab.value] = { sortBy, sortDirection: next };
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
</script>

<template>
  <el-drawer
    :model-value="true"
    :title="heading"
    size="min(1180px, 100vw)"
    class="analytics-detail"
    @close="emit('close')"
  >
    <div
      class="analytics-detail__body"
      :class="{ 'analytics-detail__body--fixed': shop && tab !== 'overview' }"
    >
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
          </div>
          <small v-if="row.shopUpdatedAt"
            >{{ local("updated") }} {{ eventTime.format(new Date(row.shopUpdatedAt)) }}</small
          >
        </div>
        <div class="analytics-detail__shop-actions">
          <RouterLink
            v-if="row.shopId"
            :to="shopShortcut"
            class="el-button el-button--primary analytics-detail__shop-link"
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
      <AnalyticsDetailHeader v-else :board="board" :row="row" :name="name" :period="filters" />

      <el-tabs v-model="tab">
        <el-tab-pane
          :label="product ? productText('overview') : t('analytics.drilldown.overview')"
          name="overview"
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
          v-if="board === 'best-sellers'"
          :label="local('shopContribution')"
          name="SHOPS"
        />
        <el-tab-pane v-if="product" :label="productText('skuPerformance')" name="SKUS" />
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
            :data-drawer-metric="card.key"
            :label="card.label"
            :value="card.value"
            :hint="card.hint"
          >
            <template v-if="card.key === 'soldProducts'" #value>
              <i18n-t
                keypath="analytics.shopDrawer.soldProductCounts"
                tag="span"
                scope="global"
                class="analytics-detail__coverage"
              >
                <template #spu
                  ><span>{{ soldProductCounts.spu }}</span></template
                >
                <template #sku
                  ><span>{{ soldProductCounts.sku }}</span></template
                >
              </i18n-t>
            </template>
            <template v-else-if="shop && card.value.includes('¥')" #value>
              <span v-for="(part, index) in card.value.split('¥')" :key="index">
                <span v-if="index" class="analytics-detail__currency">¥</span>{{ part }}
              </span>
            </template>
          </MetricCard>
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
        :class="{ 'analytics-detail__orders': shop && tab === 'ORDERS' }"
        :page-sizes="[20, 50, 100]"
        :total="total"
        :scroll-with-page="!shop"
        :scroll-rows="shop"
        plain
        @retry="load"
        @update:page="changePage"
        @update:size="changeSize"
      >
        <template v-for="column in detailHeaders" :key="column.key" #[`header-${column.key}`]>
          <el-tooltip
            :disabled="!column.hint"
            :content="column.hint"
            :trigger="['hover', 'focus']"
            popper-class="analytics-metric-definition"
          >
            <button
              v-if="column.sortBy"
              type="button"
              class="analytics-detail__sort"
              :class="{ 'is-sorted': column.direction }"
              :aria-label="
                t('analytics.sortLabel', {
                  label: column.label,
                  direction: t(`analytics.${column.direction ?? 'unsorted'}`)
                })
              "
              @click="sortDetail(column.sortBy)"
            >
              {{ column.label }}
              <span aria-hidden="true">{{
                column.direction === "asc" ? "↑" : column.direction === "desc" ? "↓" : "↕"
              }}</span>
            </button>
            <span v-else tabindex="0" class="analytics-detail__term">{{ column.label }}</span>
          </el-tooltip>
        </template>
        <template #cell-name="{ row: entry }">
          <div v-if="tab === 'SKUS'" class="analytics-detail__product">
            <img v-if="entry.imageUrl" :src="entry.imageUrl" alt="" loading="lazy" />
            <span v-else class="analytics-detail__product-placeholder" aria-hidden="true"
              ><el-icon><Goods /></el-icon
            ></span>
            <div class="analytics-detail__stack">
              <strong :title="entry.name">{{ entry.name || "—" }}</strong>
              <small v-if="entry.skuId">SKU <CopyText :value="entry.skuId" /></small>
            </div>
          </div>
          <CreatorIdentity
            v-else-if="tab === 'CREATORS'"
            :name="entry.name"
            :code="entry.creatorCode || entry.code"
          />
          <CreatorIdentity
            v-else-if="isCreatorList"
            :name="entry.name || (entry.id === '0' ? t('analytics.unassigned') : undefined)"
            :code="entry.id === '0' ? undefined : entry.code || entry.id"
          />
          <template v-else>
            <div>{{ entry.name || "—" }}</div>
            <CopyText v-if="entry.code" :value="entry.code" />
          </template>
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
        <template #cell-refundUnits>—</template>
        <template #cell-salesAmount="{ row: entry }">{{
          tab === "SKUS" ? skuMoney(entry.salesAmount) : formatMoney(entry.salesAmount)
        }}</template>
        <template #cell-completedRefundAmount="{ row: entry }">
          <span :class="{ 'analytics-detail__amount--deducted': tab === 'SKUS' }">{{
            tab === "SKUS"
              ? skuMoney(entry.completedRefundAmount)
              : formatMoney(entry.completedRefundAmount)
          }}</span>
        </template>
        <template #cell-netSalesAmount="{ row: entry }">
          <span :class="{ 'analytics-detail__sku-net': tab === 'SKUS' }">{{
            tab === "SKUS" ? skuMoney(entry.netSalesAmount) : formatMoney(entry.netSalesAmount)
          }}</span>
        </template>
        <template #cell-inviterName="{ row: entry }">{{ entry.inviterName || "—" }}</template>
        <template #cell-shopName="{ row: entry }">{{ entry.shopName || "—" }}</template>
        <template #cell-soldUnits="{ row: entry }">{{ formatCount(entry.soldUnits) }}</template>
        <template #cell-sku="{ row: entry }">
          {{ entry.sku || entry.skuId || "—" }}
        </template>
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
              <small v-if="entry.sku || entry.skuId" :title="entry.sku || entry.skuId">
                {{ entry.sku || entry.skuId }}
              </small>
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
            <small v-if="(shop && tab !== 'settlements') || (product && tab === 'ORDERS')">{{
              eventTime.format(new Date(entry.eventTime))
            }}</small>
          </div>
        </template>
        <template #cell-creatorName="{ row: entry }">
          <CreatorIdentity :name="entry.creatorName" :code="entry.creatorCode" />
        </template>
        <template #cell-spuId="{ row: entry }">
          <CopyText v-if="entry.spuId" :value="entry.spuId" />
          <span v-else>—</span>
        </template>
        <template #cell-transactionItemId="{ row: entry }">
          <CopyText v-if="entry.transactionItemId" :value="entry.transactionItemId" />
          <span v-else>—</span>
        </template>
        <template #cell-flow="{ row: entry }">
          <CopyText :value="entry.id" />
        </template>
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
        <template #cell-orderPaidAmount="{ row: entry }">{{ formatMoney(entry.amount) }}</template>
        <template #cell-refundAmount="{ row: entry }">
          <span :class="{ 'analytics-detail__amount--deducted': Number(entry.refundAmount) > 0 }">
            {{ formatMoney(entry.refundAmount) }}
          </span>
        </template>
        <template #cell-netTransactionAmount="{ row: entry }">
          <strong>{{ formatMoney(entry.netTransactionAmount) }}</strong>
        </template>
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
.analytics-detail__sku-net {
  color: var(--tcrm-color-primary);
  font-weight: 600;
}
.analytics-detail__term {
  cursor: help;
}
.analytics-detail__sort {
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
  padding: 0;
  white-space: nowrap;
}
.analytics-detail__sort.is-sorted,
.analytics-detail__sort:hover {
  color: var(--tcrm-color-primary-text);
}
.analytics-detail__body {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--tcrm-space-4);
}
.analytics-detail__body--fixed {
  height: 100%;
  grid-template-rows: auto auto minmax(0, 1fr);
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
  gap: var(--tcrm-space-2);
}
.analytics-detail__shop-link {
  min-height: var(--tcrm-control-height-large);
  padding-inline: var(--tcrm-space-5);
  text-decoration: none;
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
.analytics-detail__coverage {
  font-size: var(--tcrm-font-size-section);
}
.analytics-detail__coverage > span {
  font-size: var(--tcrm-font-size-dashboard-metric);
}
.analytics-detail__cards {
  --analytics-card-padding: var(--tcrm-space-3) var(--tcrm-space-4);
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-3);
}
.analytics-detail__currency {
  font-size: 0.8em;
}
.analytics-detail__cards :deep(.metric-card) {
  border-inline-start: 3px solid var(--drawer-metric-accent, var(--tcrm-color-primary));
}
.analytics-detail__cards :deep([data-drawer-metric="salesAmount"]),
.analytics-detail__cards :deep([data-drawer-metric="units"]) {
  --drawer-metric-accent: #6945d7;
}
.analytics-detail__cards :deep([data-drawer-metric="completedRefundAmount"]),
.analytics-detail__cards :deep([data-drawer-metric="amountRefundRate"]) {
  --drawer-metric-accent: var(--tcrm-color-danger);
}
.analytics-detail__cards :deep([data-drawer-metric="netSalesAmount"]),
.analytics-detail__cards :deep([data-drawer-metric="settlementAmount"]) {
  --drawer-metric-accent: var(--tcrm-color-success);
}
.analytics-detail__orders :deep(.el-table__cell.tcrm-amount) {
  text-align: right;
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
