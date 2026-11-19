<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  onActivated,
  onDeactivated,
  onMounted,
  onUnmounted,
  ref,
  watch
} from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import {
  ArrowRight,
  DataBoard,
  Goods,
  UserFilled,
  User,
  Tickets,
  TrendCharts,
  Wallet,
  Warning
} from "@element-plus/icons-vue";
import { analyticsOptions, exportAnalytics, queryAnalytics } from "@/api/analytics";
import type {
  AnalyticsResult,
  DetailQuery,
  Filters,
  Metric,
  Options,
  RankRow,
  RankingQuery,
  Scalar
} from "@/api/analytics";
import CopyText from "@/components/CopyText.vue";
import CreatorIdentity from "./CreatorIdentity.vue";
import DataTable from "@/components/DataTable.vue";
import type { DataColumn } from "@/components/DataTable.vue";
import RowActions from "@/components/RowActions.vue";
import { useUserStore } from "@/stores/user";
import { saveBlob } from "@/utils/download";
import { ApiError } from "@/utils/http/error";
import { formatAmountWithCurrency } from "@/utils/format";
import { canExport } from "@/utils/permission";
import { relationStatusLabelKey, relationTagType } from "@/utils/promotion";
import {
  boards,
  columnsByBoard,
  dates,
  coverageKeys,
  money,
  pending as pendingColumns,
  rates,
  sortable,
  validDates
} from "./board";
import type { DateRange, PeriodMode } from "./board";
import DashboardFilter from "./DashboardFilter.vue";
import MetricCard from "./MetricCard.vue";
import QuerySummary from "./QuerySummary.vue";

defineOptions({ name: "AnalyticsPage" });

const { t, te, locale } = useI18n();
const AnalyticsDetail = defineAsyncComponent(() => import("./AnalyticsDetail.vue"));
const me = useUserStore();
const route = useRoute();
const board = boards[String(route.name)]!;
const shop = board === "shop-performance";
const product = board === "best-sellers";
const creator = board === "kol-ranking";
const inviter = board === "inviter-performance";
const draft = ref<Filters>(dates());
const applied = ref<Filters>({ ...draft.value });
const filterVersion = ref(0);
const initialPeriodMode = ref<PeriodMode>("sevenDays");
let lastShopShortcut: string | undefined;
const page = ref(1);
const size = ref(20);
const sortBy = ref(product ? "salesAmount" : "netSalesAmount");
const sortDirection = ref<"asc" | "desc">("desc");
const result = ref<AnalyticsResult>();
const displayedQuery = ref<RankingQuery>();
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
const displayedSort = computed(() => displayedQuery.value ?? query.value);
const rows = computed(() => result.value?.ranking.items ?? []);
const columnWidths: Record<string, DataColumn["minWidth"]> = {
  identity: product ? 180 : 184,
  rank: 90,
  actions: 84,
  coverage: { "zh-CN": 164, "en-US": 170 },
  channelStatus: { "zh-CN": 104, "en-US": 136 },
  channelCode: { "zh-CN": 120, "en-US": 148 },
  refundUnits: { "zh-CN": 108, "en-US": 150 },
  units: { "zh-CN": 96, "en-US": 126 },
  paidOrderCount: { "zh-CN": 112, "en-US": 140 },
  salesAmount: { "zh-CN": 112, "en-US": 112 },
  completedRefundAmount: { "zh-CN": 122, "en-US": 164 },
  netSalesAmount: { "zh-CN": 124, "en-US": 128 },
  averageOrderAmount: { "zh-CN": 134, "en-US": 210 },
  amountRefundRate: { "zh-CN": 134, "en-US": 192 },
  kolCount: { "zh-CN": 134, "en-US": 190 },
  creatorCounts: { "zh-CN": 172, "en-US": 194 },
  relationship: { "zh-CN": 128, "en-US": 186 },
  linkCount: { "zh-CN": 132, "en-US": 168 },
  commissionRate: { "zh-CN": 120, "en-US": 172 },
  settlementAmount: { "zh-CN": 108, "en-US": 176 },
  estimatedCommission: { "zh-CN": 108, "en-US": 202 },
  commissionExpense: { "zh-CN": 108, "en-US": 192 },
  cumulativeCommission: { "zh-CN": 108, "en-US": 210 },
  prepayExpense: { "zh-CN": 108, "en-US": 198 },
  totalEstimatedCommission: { "zh-CN": 126, "en-US": 240 },
  totalExpense: { "zh-CN": 108, "en-US": 140 },
  bestCreator: { "zh-CN": 156, "en-US": 182 }
};
const shopColumnWidths: Record<string, number> = {
  identity: 162,
  channelStatus: 112,
  paidOrderCount: 108,
  units: 74,
  coverage: 96,
  kolCount: 86,
  salesAmount: 103,
  completedRefundAmount: 108,
  netSalesAmount: 110,
  amountRefundRate: 108,
  averageOrderAmount: 108
};
const columns = computed<DataColumn[]>(() =>
  [...columnsByBoard[board], "actions"].map(key => ({
    key,
    label: label(key),
    amount: money.has(key),
    tooltip: false,
    align: ["identity", "bestCreator"].includes(key) ? "left" : "center",
    interactive: key === "identity",
    fixed:
      key === "bestCreator"
        ? "right"
        : (product || creator) && key === "identity"
          ? "left"
          : undefined,
    highlighted: product ? key === "netSalesAmount" : sortBy.value === key,
    minWidth:
      product && key === "kolCount"
        ? { "zh-CN": 178, "en-US": 214 }
        : shop && locale.value === "zh-CN" && shopColumnWidths[key]
          ? shopColumnWidths[key]
          : (columnWidths[key] ?? { "zh-CN": 108, "en-US": 136 })
  }))
);
const customHeaders = computed(() =>
  columnsByBoard[board]
    .filter(key => !shop || !["identity", "channelStatus"].includes(key))
    .map(key => ({ key, help: t(`analytics.${board}.definitions.${key}`) }))
);
const cellKeys = columnsByBoard[board].filter(
  key =>
    key !== "rank" &&
    !(product && key === "kolCount") &&
    (sortable.has(key) || pendingColumns.has(key))
);
const selectedDetail = ref<{ row: RankRow; filters: DetailQuery }>();

function detailActions(row: RankRow) {
  return [{ key: "detail", label: t("analytics.detail"), onClick: () => openDetail(row) }];
}

function openDetail(row: RankRow): void {
  if (result.value) selectedDetail.value = detailSelection(row, result.value);
}

function detailSelection(row: RankRow, displayed: AnalyticsResult) {
  const { meta } = displayed;
  return {
    row,
    filters: {
      ...meta.period,
      ...meta.filters,
      shopId: shop ? row.shopId : meta.filters.shopId,
      productCode: product ? row.code : undefined,
      keyword: product && !meta.filters.productKeyword ? displayed.keyword : undefined,
      entityId: creator || inviter ? row.id : undefined
    }
  };
}

function identity(row: RankRow): string {
  if (creator && row.name && row.primaryPlatform) {
    return t("analytics.kol-ranking.nameWithPlatform", {
      name: row.name,
      platform: row.primaryPlatform
    });
  }
  return (shop ? row.shopName : row.name) || (row.id === "0" ? t("analytics.unassigned") : "—");
}

function shopTerm(key: string, value?: string) {
  return {
    label: t(`analytics.shop-performance.${key}`),
    hint: t(`analytics.shop-performance.definitions.${key}`),
    value
  };
}

const settlementState = computed(() => {
  const scoped = result.value?.unavailable.some(field => field.reason === "SHOP_SETTLEMENT_SCOPE");
  return t(`analytics.shop-performance.${scoped ? "settlementScoped" : "settlementEmpty"}`);
});

const shopSalesTerms = computed(() => [
  shopTerm("salesAmount", display("salesAmount", metrics.value?.salesAmount)),
  shopTerm("cohortRefundAmount", display("cohortRefundAmount", metrics.value?.cohortRefundAmount))
]);

const settlementTerms = computed(() => {
  const settlement = result.value?.summary.settlement;
  return [
    shopTerm("increase", display("settlement", settlement?.increase)),
    shopTerm("decrease", display("settlement", settlement?.decrease))
  ];
});

function label(key: string): string {
  if (shop && ["coverage", "kolCount"].includes(key)) return shopTerm(key).label;
  if (key === "identity" || key === "netCommission") {
    return t(`analytics.${board}.${key}`);
  }
  if (
    product &&
    [
      "identity",
      "channelCode",
      "shop",
      "kolCount",
      "paidOrderCount",
      "netSalesAmount",
      "settlementAmount"
    ].includes(key)
  ) {
    return t(`analytics.best-sellers.${key}`);
  }

  if (
    creator &&
    [
      "relationship",
      "paidOrderCount",
      "linkCount",
      "commissionRate",
      "estimatedCommission",
      "commissionExpense",
      "cumulativeCommission"
    ].includes(key)
  ) {
    return t(`analytics.kol-ranking.${key}`);
  }

  if (
    inviter &&
    [
      "creatorCounts",
      "prepayExpense",
      "totalEstimatedCommission",
      "totalExpense",
      "bestCreator"
    ].includes(key)
  ) {
    return t(`analytics.inviter-performance.${key}`);
  }
  return t(`analytics.${key}`);
}

function pair(left: Scalar | null | undefined, right: Scalar | null | undefined): string {
  return `${display("", left)} / ${display("", right)}`;
}

function display(key: string, value: Scalar | null | undefined): string {
  if (value == null) return "—";
  if (money.has(key)) return formatAmountWithCurrency(String(value));
  if (rates.has(key)) return percent(value);

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

function isCommissionForbidden(key: string): boolean {
  return (
    result.value?.unavailable.some(
      field => field.field === key && field.reason === "COMMISSION_FORBIDDEN"
    ) ?? false
  );
}

function cellDisplay(row: RankRow, key: string): string {
  if (isCommissionForbidden(key)) return t("analytics.commissionForbidden");
  const value = valueOf(row, key);
  const unavailable = result.value?.unavailable.some(field => field.field === key);
  return value == null && unavailable && !["refundUnits", "totalEstimatedCommission"].includes(key)
    ? t("analytics.pending")
    : display(key, value);
}

const cards = computed(() => {
  const summary = result.value?.summary;
  const data = metrics.value;
  if (creator) {
    const extras = summary?.creatorCards;
    const translate = (key: string) => t(`analytics.creatorCards.${key}`);
    const term = (key: string, value?: string, hintKey = `${key}Hint`) => ({
      label: translate(key),
      hint: translate(hintKey),
      value
    });
    const formatMoney = (value: Scalar | null | undefined) => {
      if (value == null) return "—";
      const [whole, fraction = ""] = String(value).split(".");
      return formatAmountWithCurrency(`${whole}.${fraction.padEnd(2, "0")}`);
    };
    const forbidden = isCommissionForbidden("commissionRate");
    const selling = term("selling");
    const effective = term(
      "effective",
      undefined,
      extras?.periodEffectiveCount == null && result.value
        ? "effectiveUnavailableHint"
        : "effectiveHint"
    );
    const orderCount = term("orders");
    const units = term("units");
    const net = term("net");
    const commission = term(
      "totalCommission",
      undefined,
      forbidden ? "commissionForbiddenHint" : "totalCommissionHint"
    );
    return [
      {
        tone: "purple" as const,
        icon: User,
        label: `${selling.label} / ${effective.label}`,
        value: pair(summary?.sellingCount, extras?.periodEffectiveCount),
        labelTerms: [selling, effective],
        detailTerms: [
          term(
            "relationship",
            extras?.relationshipCount == null
              ? "—"
              : t("analytics.creatorCards.peopleCount", {
                  value: display("", extras.relationshipCount)
                })
          )
        ]
      },
      {
        tone: "blue" as const,
        icon: Tickets,
        label: `${orderCount.label} / ${units.label}`,
        value: pair(data?.paidOrderCount, data?.units),
        labelTerms: [orderCount, units],
        detailTerms: [
          { label: translate("spu"), value: display("", extras?.spuCount) },
          term("averageOrder", formatMoney(data?.averageOrderAmount))
        ]
      },
      {
        tone: "green" as const,
        icon: TrendCharts,
        label: net.label,
        value: formatMoney(data?.netSalesAmount),
        labelTerms: [net],
        detailTerms: [
          { label: t("analytics.salesAmount"), value: formatMoney(data?.salesAmount) },
          { label: translate("cohortRefund"), value: formatMoney(data?.cohortRefundAmount) }
        ]
      },
      {
        tone: "amber" as const,
        icon: Wallet,
        label: commission.label,
        value: forbidden
          ? t("analytics.commissionForbidden")
          : formatMoney(extras?.totalCommission),
        labelTerms: [commission],
        unavailable: forbidden || extras?.totalCommission == null,
        detailTerms: forbidden
          ? undefined
          : [
              {
                label: translate("commissionBeforeClawback"),
                value: formatMoney(extras?.commissionBeforeClawback)
              },
              term("clawback", formatMoney(extras?.commissionClawback))
            ]
      }
    ];
  }
  if (shop) {
    const selling = shopTerm("selling");
    const total = shopTerm("total");
    const orders = shopTerm("paidOrderCount");
    const net = shopTerm("netSalesAmount");
    const settlement = shopTerm("settlement");
    return [
      {
        tone: "purple" as const,
        icon: DataBoard,
        label: `${selling.label} / ${total.label}`,
        value: `${display("", summary?.sellingShopCount)} / ${display("", summary?.shopCount)}`,
        labelTerms: [selling, total],
        detail: t("analytics.shop-performance.totalIncludesDisabled")
      },
      {
        tone: "blue" as const,
        icon: Tickets,
        label: orders.label,
        value: display("", data?.paidOrderCount),
        labelTerms: [orders],
        detailTerms: [
          shopTerm("units", display("", data?.units)),
          shopTerm("spuCount", display("", summary?.spuCount))
        ]
      },
      {
        tone: "green" as const,
        icon: TrendCharts,
        label: net.label,
        value: display("netSalesAmount", data?.netSalesAmount),
        labelTerms: [net],
        detailTerms: shopSalesTerms.value
      },
      {
        tone: "amber" as const,
        icon: Wallet,
        label: settlement.label,
        value: summary?.settlement
          ? display("settlement", summary.settlement.net)
          : settlementState.value,
        unavailable: !summary?.settlement && !!result.value,
        labelTerms: [settlement],
        detailTerms: summary?.settlement ? settlementTerms.value : undefined
      }
    ];
  }
  if (inviter) {
    const extras = summary?.inviterCards;
    const term = inviterTerm;
    const active = term("activeInviters");
    const total = term("totalInviters");
    const orders = term("paidOrderCount");
    const units = term("units");
    const net = term("netSalesAmount");
    const commission = term("totalEstimatedCommission");
    const forbidden = isCommissionForbidden("totalEstimatedCommission");
    return [
      {
        tone: "purple" as const,
        icon: User,
        label: `${active.label} / ${total.label}`,
        value: `${display("", summary?.activeCount)} / ${display("", extras?.inviterCount)}`,
        labelTerms: [active, total],
        detailTerms: [term("newInvitations", display("", extras?.newInvitations))]
      },
      {
        tone: "blue" as const,
        icon: Tickets,
        label: `${orders.label} / ${units.label}`,
        value: `${display("", data?.paidOrderCount)} / ${display("", data?.units)}`,
        labelTerms: [orders, units],
        detailTerms: [
          term("averageOrderAmount", display("averageOrderAmount", data?.averageOrderAmount))
        ]
      },
      {
        tone: "green" as const,
        icon: TrendCharts,
        label: net.label,
        value: display("netSalesAmount", data?.netSalesAmount),
        labelTerms: [net],
        detailTerms: [
          term("salesAmount", display("salesAmount", data?.salesAmount)),
          term("cohortRefundAmount", display("cohortRefundAmount", data?.cohortRefundAmount))
        ]
      },
      {
        tone: "amber" as const,
        icon: Wallet,
        label: commission.label,
        value: forbidden
          ? t("analytics.commissionForbidden")
          : display("totalEstimatedCommission", extras?.totalCommission),
        labelTerms: [commission],
        unavailable: forbidden,
        detailTerms: forbidden
          ? undefined
          : [
              term(
                "estimatedPart",
                display("totalEstimatedCommission", extras?.estimatedCommission)
              ),
              term("actualPart", display("totalEstimatedCommission", extras?.actualCommission))
            ],
        detail: forbidden ? t("analytics.inviter-performance.commissionAccess") : undefined
      }
    ];
  }
  const hint = (key: string) => t(`analytics.best-sellers.definitions.${key}`);
  return [
    {
      tone: "neutral" as const,
      icon: DataBoard,
      label: t("analytics.best-sellers.selling"),
      value: pair(summary?.sellingCount, summary?.totalSpuCount),
      hint: hint("selling")
    },
    {
      tone: "blue" as const,
      icon: Tickets,
      label: t("analytics.paidOrderCount"),
      value: display("", data?.paidOrderCount),
      hint: hint("paidOrderCount")
    },
    {
      tone: "amber" as const,
      icon: Goods,
      label: label("units"),
      value: display("", data?.units),
      hint: hint("units")
    },
    {
      tone: "green" as const,
      icon: TrendCharts,
      label: t("analytics.best-sellers.net"),
      value: display("netSalesAmount", data?.netSalesAmount),
      hint: hint("netSalesAmount")
    }
  ];
});

function inviterTerm(key: string, value?: string) {
  const labels = `analytics.inviter-performance.terms.${key}`;
  return {
    label: te(labels) ? t(labels) : label(key),
    hint: t(`analytics.inviter-performance.definitions.${key}`),
    value
  };
}

const conversion = computed(() => {
  const summary = result.value?.summary;
  const cards = summary?.inviterCards;
  return [
    {
      ...inviterTerm("invitedCount", display("", summary?.invitedCount)),
      note: inviterTerm("newInvitations", display("", cards?.newInvitations)),
      transition: inviterTerm(
        "effectiveTransition",
        ratio(summary?.effectiveCount, summary?.invitedCount)
      )
    },
    {
      ...inviterTerm("effectiveCount", display("", summary?.effectiveCount)),
      note: inviterTerm("pendingCreators", display("", cards?.pendingCreators)),
      transition: inviterTerm(
        "salesTransition",
        ratio(summary?.sellingCreatorCount, summary?.effectiveCount)
      )
    },
    {
      ...inviterTerm("kolCount", display("", summary?.sellingCreatorCount)),
      note: inviterTerm("paidOrderCount", display("", metrics.value?.paidOrderCount)),
      transition: undefined
    }
  ];
});

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
      draft.value = { ...draft.value, ...today };
      page.value = 1;
    }
  }
  const id = ++request;
  const requested = query.value;
  loading.value = true;
  failed.value = false;
  try {
    const next = await queryAnalytics(board, requested);
    if (id === request) {
      result.value = next;
      displayedQuery.value = requested;
      const detail = selectedDetail.value;
      if (
        detail &&
        (detail.filters.startDate !== next.meta.period.startDate ||
          detail.filters.endDate !== next.meta.period.endDate)
      ) {
        const previous = detail.row;
        const row = next.ranking.items.find(item =>
          shop ? item.shopId === previous.shopId : item.id === previous.id
        );
        selectedDetail.value = row ? detailSelection(row, next) : undefined;
      }
    }
  } catch (error) {
    if (id === request) {
      if (error instanceof ApiError && [401, 403].includes(error.status)) {
        result.value = undefined;
        displayedQuery.value = undefined;
        selectedDetail.value = undefined;
      }
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

function applyPeriod({ startDate, endDate }: DateRange, useLive: boolean): void {
  const range = { startDate, endDate };
  if (!validDates(range)) {
    ElMessage.warning(t("analytics.invalidDates"));
    return;
  }
  const changed = startDate !== applied.value.startDate || endDate !== applied.value.endDate;
  const startedLive = useLive && !live;
  live = useLive;
  if (!changed && !startedLive) return;
  applied.value = { ...applied.value, ...range };
  page.value = 1;
  void load();
}

function search(useLive = false): void {
  if (!validDates(draft.value)) {
    ElMessage.warning(t("analytics.invalidDates"));
    return;
  }
  live = useLive;
  const { shopId, channel, creatorKeyword, inviterKeyword, productKeyword, startDate, endDate } =
    draft.value;
  applied.value = {
    startDate,
    endDate,
    shopId: shopId || undefined,
    channel: channel || undefined,
    creatorKeyword: creatorKeyword?.trim() || undefined,
    productKeyword: product || inviter ? productKeyword?.trim() || undefined : undefined,
    inviterKeyword: creator ? inviterKeyword?.trim() || undefined : undefined
  };
  page.value = 1;
  void load();
}

function reset(): void {
  draft.value = dates();
  sortBy.value = product ? "salesAmount" : "netSalesAmount";
  sortDirection.value = "desc";
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
  if (exporting.value || loading.value || !displayedQuery.value || !result.value) return;
  exporting.value = true;
  try {
    const file = await exportAnalytics(board, displayedQuery.value);
    saveBlob(file.blob, file.fileName ?? `${board}.xlsx`);
  } catch {
    ElMessage.error(t("analytics.exportFailed"));
  } finally {
    exporting.value = false;
  }
}

function applyShopShortcut(): boolean {
  if (!product || route.name !== "AnalyticsBestSellers") {
    lastShopShortcut = undefined;
    return false;
  }
  const { shopId, startDate, endDate } = route.query;
  if (
    route.fullPath === lastShopShortcut ||
    typeof shopId !== "string" ||
    !/^[1-9][0-9]{0,18}$/.test(shopId)
  )
    return false;
  lastShopShortcut = route.fullPath;
  const range =
    typeof startDate === "string" &&
    typeof endDate === "string" &&
    /^\d{4}-\d{2}-\d{2}$/.test(startDate) &&
    /^\d{4}-\d{2}-\d{2}$/.test(endDate) &&
    validDates({ startDate, endDate })
      ? { startDate, endDate }
      : undefined;
  draft.value = { ...(range ?? dates()), shopId };
  applied.value = { ...draft.value };
  initialPeriodMode.value = range
    ? range.startDate === range.endDate
      ? "day"
      : "custom"
    : "sevenDays";
  filterVersion.value++;
  page.value = 1;
  live = false;
  return true;
}

watch(
  () => route.fullPath,
  () => {
    if (applyShopShortcut() && active) void load();
  }
);

function start(): void {
  if (active) return;
  active = true;
  applyShopShortcut();
  void load();
  if (!options.value) void loadOptions();
  timer = setInterval(() => {
    if (!document.hidden && !loading.value) void load();
  }, 300_000);
}
function stop(): void {
  active = false;
  request++;
  loading.value = false;
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
      :key="filterVersion"
      v-model="draft"
      :initial-mode="initialPeriodMode"
      :show-inviter="creator"
      :show-product="product || inviter"
      :loading="loading"
      :options="options"
      :options-failed="optionsFailed"
      @period-change="applyPeriod"
      @search="search"
      @reset="reset"
      @refresh="refresh"
    />
    <QuerySummary v-if="result" :result="result" :options="options" />
    <el-alert
      v-if="result && (loading || failed)"
      :title="t(loading ? 'analytics.refreshingResults' : 'analytics.staleResults')"
      :type="loading ? 'info' : 'warning'"
      :closable="false"
      show-icon
    />

    <div
      class="analytics-cards"
      :class="{
        'analytics-cards--combined-note': creator || inviter,
        'analytics-cards--inviter': inviter
      }"
      :aria-busy="loading"
    >
      <MetricCard v-for="card in cards" :key="card.label" v-bind="card" />
    </div>

    <section v-if="shop" :aria-label="t('analytics.transactionTitle')">
      <div class="ledger-comparison">
        <div class="ledger-comparison__panel ledger-comparison__transaction">
          <h3>{{ t("analytics.transactionLedger") }}</h3>
          <div class="ledger-comparison__hero">
            <el-tooltip
              :content="shopTerm('netSalesAmount').hint"
              :trigger="['hover', 'focus']"
              popper-class="analytics-metric-definition"
            >
              <strong
                tabindex="0"
                class="ledger-comparison__term"
                :aria-label="`${label('netSalesAmount')} ${display('netSalesAmount', metrics?.netSalesAmount)}`"
                >{{ display("netSalesAmount", metrics?.netSalesAmount) }}</strong
              >
            </el-tooltip>
          </div>
          <p class="ledger-comparison__detail">
            <template v-for="(term, index) in shopSalesTerms" :key="term.label">
              <span v-if="index" aria-hidden="true"> − </span>
              <el-tooltip
                :content="term.hint"
                :trigger="['hover', 'focus']"
                popper-class="analytics-metric-definition"
              >
                <span tabindex="0" class="ledger-comparison__term"
                  >{{ term.label }} {{ term.value }}</span
                >
              </el-tooltip>
            </template>
          </p>
        </div>
        <div class="ledger-comparison__bridge">
          <el-icon aria-hidden="true"><ArrowRight /></el-icon>
          <span>{{ t("analytics.ledgerBridge") }}</span>
        </div>
        <div class="ledger-comparison__panel ledger-comparison__settlement">
          <h3>{{ t("analytics.settlementLedger") }}</h3>
          <div class="ledger-comparison__hero">
            <el-tooltip
              :content="shopTerm('settlement').hint"
              :trigger="['hover', 'focus']"
              popper-class="analytics-metric-definition"
            >
              <span tabindex="0" class="ledger-comparison__term">
                <strong v-if="result?.summary.settlement">{{
                  display("settlement", result.summary.settlement.net)
                }}</strong>
                <el-tag v-else-if="result" type="info" effect="light">{{ settlementState }}</el-tag>
                <strong v-else>—</strong>
              </span>
            </el-tooltip>
          </div>
          <p v-if="result?.summary.settlement" class="ledger-comparison__detail">
            <template v-for="(term, index) in settlementTerms" :key="term.label">
              <span v-if="index" aria-hidden="true"> − </span>
              <el-tooltip
                :content="term.hint"
                :trigger="['hover', 'focus']"
                popper-class="analytics-metric-definition"
              >
                <span tabindex="0" class="ledger-comparison__term"
                  >{{ term.label }} {{ term.value }}</span
                >
              </el-tooltip>
            </template>
          </p>
          <p v-else class="ledger-comparison__detail">
            {{ t("analytics.shop-performance.settlementFormula") }}
          </p>
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
          :hint="t('analytics.kol-ranking.definitions.amountRefundRate')"
          :detail="`${display('completedRefundAmount', metrics?.completedRefundAmount)} / ${display('salesAmount', metrics?.salesAmount)}`"
        />
      </div>
    </section>
    <section
      v-else-if="inviter"
      class="conversion-chain"
      :aria-label="t('analytics.inviter-performance.conversionTitle')"
    >
      <div class="conversion-chain__intro">
        <el-icon aria-hidden="true"><UserFilled /></el-icon>
        <div>
          <h2>{{ t("analytics.inviter-performance.conversionTitle") }}</h2>
          <p>{{ t("analytics.inviter-performance.conversionSubtitle") }}</p>
        </div>
      </div>
      <template v-for="(entry, index) in conversion" :key="entry.label">
        <div
          class="conversion-chain__step"
          :class="{ 'conversion-chain__step--sales': index === 2 }"
        >
          <el-tooltip
            :content="entry.hint"
            :trigger="['hover', 'focus']"
            popper-class="analytics-metric-definition"
          >
            <span tabindex="0" class="conversion-chain__term">{{ entry.label }}</span>
          </el-tooltip>
          <strong>{{ entry.value }}</strong>
          <el-tooltip
            :content="entry.note.hint"
            :trigger="['hover', 'focus']"
            popper-class="analytics-metric-definition"
          >
            <small tabindex="0" class="conversion-chain__term"
              >{{ entry.note.label }} {{ entry.note.value }}</small
            >
          </el-tooltip>
        </div>
        <div v-if="entry.transition" class="conversion-chain__transition">
          <el-icon aria-hidden="true"><ArrowRight /></el-icon>
          <el-tooltip
            :content="entry.transition.hint"
            :trigger="['hover', 'focus']"
            popper-class="analytics-metric-definition"
          >
            <span
              tabindex="0"
              class="conversion-chain__term"
              :aria-label="`${entry.transition.label} ${entry.transition.value}`"
              >{{ entry.transition.value }}</span
            >
          </el-tooltip>
        </div>
      </template>
    </section>

    <DataTable
      class="analytics-ranking"
      scroll-with-page
      :table-id="`analytics-${board}${shop ? '' : '-v2'}`"
      :columns="columns"
      :rows="rows"
      :row-key="shop ? 'shopId' : 'id'"
      :loading="loading"
      :failed="failed && !result"
      :page="displayedQuery?.page ?? page"
      :size="displayedQuery?.size ?? size"
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
        <div>
          <h2 class="analytics-ranking__title">{{ t(`analytics.${board}.ranking`) }}</h2>
          <p v-if="inviter" class="analytics-ranking__hint">
            {{ t("analytics.inviter-performance.rankingHint") }}
          </p>
        </div>
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
      <template v-for="{ key, help } in customHeaders" :key="key" #[`header-${key}`]>
        <div class="sort-controls" :class="{ 'sort-controls--left': key === 'identity' }">
          <el-tooltip
            :disabled="!help"
            :content="help"
            :trigger="['hover', 'focus']"
            popper-class="analytics-metric-definition"
            placement="top"
          >
            <component
              :is="sortable.has(key) ? 'button' : 'span'"
              :type="sortable.has(key) ? 'button' : undefined"
              :tabindex="sortable.has(key) ? undefined : 0"
              :aria-label="
                sortable.has(key)
                  ? t('analytics.sortLabel', {
                      label: label(key),
                      direction:
                        displayedSort.sortBy === key
                          ? t(`analytics.${displayedSort.sortDirection}`)
                          : t('analytics.unsorted')
                    })
                  : undefined
              "
              class="analytics-column-heading"
              :class="{ selected: !product && displayedSort.sortBy === key }"
              @click="sortable.has(key) && sort(key)"
            >
              {{ label(key) }}
              <span v-if="sortable.has(key)" aria-hidden="true">{{
                displayedSort.sortBy === key
                  ? displayedSort.sortDirection === "asc"
                    ? "↑"
                    : "↓"
                  : "↕"
              }}</span>
            </component>
          </el-tooltip>
        </div>
      </template>
      <template #cell-rank="{ row }">
        <span class="analytics-rank-badge" :data-rank="valueOf(row, 'rank')">
          {{ display("rank", valueOf(row, "rank")) }}
        </span>
      </template>
      <template #cell-identity="{ row }">
        <div v-if="creator" class="analytics-identity__text">
          <CreatorIdentity :name="identity(row)" :code="row.code" />
          <small v-if="row.social?.length">{{ row.social.join(" · ") }}</small>
        </div>
        <div v-else class="analytics-identity">
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
        <CopyText v-if="row.sourceItemId" :value="row.sourceItemId" />
        <span v-else>—</span>
      </template>
      <template #cell-shop="{ row }">{{ display("", row.onSaleShopCount) }}</template>
      <template v-if="product" #cell-kolCount="{ row }">{{
        pair(valueOf(row, "kolCount"), row.linkedCreatorCount)
      }}</template>
      <template #cell-relationship="{ row }">
        <div>{{ row.related || t("analytics.unassigned") }}</div>
        <el-tag v-if="row.relationStatus" size="small" :type="relationTagType(row.relationStatus)">
          {{ t(relationStatusLabelKey(row.relationStatus)) }}
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
          <template v-for="(key, index) in coverageKeys" :key="key">
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
              'analytics-number--unavailable':
                (pendingColumns.has(key) && valueOf(row, key) == null) || isCommissionForbidden(key)
            }"
            >{{ cellDisplay(row, key) }}</span
          >
        </div>
      </template>

      <template #cell-bestCreator="{ row }">
        <div v-if="row.bestCreator" class="analytics-identity__text">
          <CreatorIdentity
            :name="row.bestCreator.name"
            :code="row.bestCreator.code || row.bestCreator.id"
          />
          <el-tooltip
            :content="inviterTerm('netSalesAmount').hint"
            :trigger="['hover', 'focus']"
            popper-class="analytics-metric-definition"
          >
            <small tabindex="0" class="conversion-chain__term"
              >{{ label("netSalesAmount") }}
              {{ display("netSalesAmount", row.bestCreator.netSalesAmount) }}</small
            >
          </el-tooltip>
        </div>
        <span v-else>—</span>
      </template>
      <template #cell-creatorCounts="{ row }">
        {{ display("effectiveCount", valueOf(row, "effectiveCount")) }}
        <span class="coverage-values__separator" aria-hidden="true">/</span>
        {{ display("invitedCount", valueOf(row, "invitedCount")) }}
      </template>
      <template #actions="{ row }"><RowActions :actions="detailActions(row)" /></template>
    </DataTable>
    <AnalyticsDetail
      v-if="selectedDetail"
      :board="board"
      :data-scope="result?.meta.dataScope"
      :row="selectedDetail.row"
      :name="identity(selectedDetail.row)"
      :filters="selectedDetail.filters"
      @close="selectedDetail = undefined"
    />
  </div>
</template>

<style scoped>
.analytics-page {
  --analytics-card-padding: var(--tcrm-space-4);
  container-type: inline-size;
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-auto-rows: max-content;
  align-content: start;
  gap: var(--tcrm-space-4);
}
.analytics-cards {
  --metric-card-alignment: center;
  --metric-icon-box: calc(var(--tcrm-control-height-large) + var(--tcrm-space-2));
  --metric-icon-size: var(--tcrm-font-size-title);
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
}
.analytics-cards--inviter {
  --metric-note-indent: 0px;
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
.conversion-chain {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr) auto minmax(0, 1.2fr) auto minmax(
      0,
      1.2fr
    );
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  overflow: hidden;
}
.conversion-chain__intro,
.conversion-chain__step {
  display: grid;
  align-content: center;
  gap: var(--tcrm-space-1);
  padding: var(--tcrm-space-4) var(--analytics-card-padding);
  min-width: 0;
}
.conversion-chain__intro {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-3);
  background: linear-gradient(110deg, #246bc6, #5548ce);
  color: #fff;
}
.conversion-chain__intro .el-icon {
  font-size: var(--tcrm-font-size-title);
  flex-shrink: 0;
}
.conversion-chain__intro h2,
.conversion-chain__intro p,
.analytics-ranking__hint {
  margin: 0;
}
.conversion-chain__intro h2 {
  font-size: var(--tcrm-font-size-section);
}
.conversion-chain__intro p,
.analytics-ranking__hint {
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-body);
}
.conversion-chain__intro p {
  margin-top: var(--tcrm-space-1);
}
.analytics-ranking__hint {
  color: var(--tcrm-color-text-secondary);
}
.conversion-chain__step {
  background: linear-gradient(110deg, var(--tcrm-color-muted), var(--tcrm-color-card));
  color: var(--tcrm-color-text-secondary);
}
.conversion-chain__step strong {
  color: var(--tcrm-color-text);
  font-size: var(--tcrm-font-size-title);
  font-variant-numeric: tabular-nums;
}
.conversion-chain__step--sales {
  background: linear-gradient(110deg, var(--tcrm-color-success-bg), var(--tcrm-color-card));
}
.conversion-chain__step--sales strong {
  color: var(--tcrm-color-success);
}
.conversion-chain__term {
  cursor: help;
}
.conversion-chain__transition {
  display: grid;
  align-content: center;
  justify-items: center;
  gap: var(--tcrm-space-1);
  padding: var(--tcrm-space-2);
  background: var(--tcrm-color-option-selected-bg);
  color: var(--tcrm-color-primary-text);
  font-size: var(--tcrm-font-size-help);
}
@container (max-width: 720px) {
  .conversion-chain {
    grid-template-columns: minmax(0, 1fr);
  }
  .conversion-chain__transition {
    grid-auto-flow: column;
    justify-content: start;
    padding-inline: var(--analytics-card-padding);
  }
  .conversion-chain__transition .el-icon {
    transform: rotate(90deg);
  }
}
.ledger-comparison {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) auto minmax(0, 1.4fr) minmax(0, 1fr);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  overflow: hidden;
}
.ledger-comparison__panel {
  min-width: 0;
  padding: var(--tcrm-space-4) var(--analytics-card-padding);
}
.ledger-comparison__note {
  border-inline-start: 1px dashed var(--tcrm-color-border);
}
.ledger-comparison__panel h3 {
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  font-weight: 600;
  color: var(--tcrm-color-text-secondary);
  margin: 0;
}
.ledger-comparison__bridge {
  display: grid;
  place-content: center;
  justify-items: center;
  gap: var(--tcrm-space-1);
  padding: var(--tcrm-space-2) var(--tcrm-space-3);
  color: var(--tcrm-color-primary-text);
  background: color-mix(in srgb, var(--tcrm-color-chain-finance) 5%, var(--tcrm-color-card));
}
.ledger-comparison__bridge span {
  max-width: 12ch;
  text-align: center;
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
.ledger-comparison__transaction {
  background: linear-gradient(110deg, var(--tcrm-color-option-selected-bg), var(--tcrm-color-card));
  color: var(--tcrm-color-primary-text);
}
.ledger-comparison__term {
  cursor: help;
}
.ledger-comparison__settlement {
  background: linear-gradient(110deg, var(--tcrm-color-success-bg), var(--tcrm-color-card));
  color: var(--tcrm-color-success);
}
.ledger-comparison__note {
  display: grid;
  align-content: center;
  gap: var(--tcrm-space-1);
  background: color-mix(in srgb, var(--tcrm-color-warning-bg) 42%, var(--tcrm-color-card));
}
.ledger-comparison__note p,
.ledger-comparison__detail {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  margin: 0;
}
.ledger-comparison__hero {
  display: flex;
  align-items: center;
  min-height: var(--tcrm-line-height-amount-primary);
  margin-block: var(--tcrm-space-1);
}
.ledger-comparison__hero strong {
  font-size: var(--tcrm-font-size-amount-primary);
  line-height: var(--tcrm-line-height-amount-primary);
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.ledger-comparison__detail {
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.analytics-number--net {
  color: var(--tcrm-color-table-text);
  font-weight: 600;
}
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
.analytics-ranking :deep(.el-table__body td.el-table__cell:not(.data-table__highlighted)) {
  background-color: var(--tcrm-color-card);
}
.analytics-ranking
  :deep(.el-table__body tr:hover > td.el-table__cell:not(.data-table__highlighted)) {
  background-color: var(--tcrm-color-muted);
}
.analytics-ranking :deep(.el-table__header th .cell) {
  white-space: nowrap;
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
.sort-controls--left {
  justify-content: flex-start;
}
.analytics-column-heading {
  display: inline-flex;
  align-items: center;
  gap: var(--tcrm-space-1);
  white-space: nowrap;
}
:global(.analytics-metric-definition) {
  max-width: min(360px, calc(100vw - var(--tcrm-space-5)));
  white-space: normal;
  overflow-wrap: anywhere;
}
.analytics-column-heading:focus-visible {
  outline: var(--tcrm-focus-width) solid var(--tcrm-color-primary);
  outline-offset: var(--tcrm-focus-width);
}
@media (min-width: 1440px) and (min-height: 900px) {
  .analytics-page {
    --analytics-card-padding: var(--tcrm-space-5);
  }
}
@media (max-width: 767px) {
  .analytics-cards {
    --metric-icon-box: var(--tcrm-control-height-mobile);
  }
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
@container (max-width: 1120px) {
  .analytics-cards--combined-note {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@container (max-width: 1420px) {
  .analytics-cards--combined-note:where(:lang(en)) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@container (max-width: 600px) {
  .analytics-cards--combined-note {
    grid-template-columns: minmax(0, 1fr);
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
