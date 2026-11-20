<script setup lang="ts">
import {
  ArrowRight,
  Coin,
  Goods,
  House,
  List,
  Money,
  ShoppingCart,
  User,
  Wallet
} from "@element-plus/icons-vue";
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  getWorkbench,
  getWorkbenchRanks,
  type RankBoard,
  type RankPeriod,
  type Workbench
} from "@/api/workbench";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { formatAmountWithCurrency } from "@/utils/format";

defineOptions({ name: "HomePage" });
const { t, locale } = useI18n();
const user = useUserStore();
const data = ref<Workbench>();
const loading = ref(false);
const failed = ref(false);
let request = 0;
let timer: number | undefined;
function initialRankState() {
  return {
    period: "ALL_TIME" as RankPeriod,
    expanded: false,
    result: undefined as Awaited<ReturnType<typeof getWorkbenchRanks>> | undefined,
    loading: false,
    failed: false,
    request: 0
  };
}
const rankBoards: RankBoard[] = ["SHOP", "CREATOR"];
const rankStates = reactive({ SHOP: initialRankState(), CREATOR: initialRankState() });

async function showRankPage(board: RankBoard, page = 1) {
  const state = rankStates[board];
  const id = ++state.request;
  state.loading = true;
  state.failed = false;
  try {
    const result = await getWorkbenchRanks(board, state.period, page, state.expanded ? 10 : 5);
    if (id === state.request) state.result = result;
  } catch {
    if (id === state.request) state.failed = true;
  } finally {
    if (id === state.request) state.loading = false;
  }
}

function resetRankResult(board: RankBoard) {
  const state = rankStates[board];
  state.request++;
  state.result = undefined;
  state.loading = false;
  state.failed = false;
}
function changeRankPeriod(board: RankBoard, period: RankPeriod) {
  const state = rankStates[board];
  if (state.period === period) return;
  state.period = period;
  resetRankResult(board);
  if (state.expanded || period !== "ALL_TIME") void showRankPage(board);
}
function toggleRanks(board: RankBoard) {
  const state = rankStates[board];
  state.expanded = !state.expanded;
  resetRankResult(board);
  if (state.expanded || state.period !== "ALL_TIME") void showRankPage(board);
}
function resetRanks() {
  for (const board of rankBoards) {
    resetRankResult(board);
    rankStates[board].period = "ALL_TIME";
    rankStates[board].expanded = false;
  }
}

async function load() {
  if (loading.value || !user.user || user.isPlatformAdmin) return;
  const id = ++request;
  loading.value = true;
  failed.value = false;
  try {
    const result = await getWorkbench();
    if (id === request) {
      data.value = result;
      for (const board of rankBoards) {
        const rank = rankStates[board];
        if (!rank.loading && (rank.expanded || rank.period !== "ALL_TIME")) {
          void showRankPage(board, rank.result?.page);
        }
      }
    }
  } catch {
    if (id === request) failed.value = true;
  } finally {
    if (id === request) loading.value = false;
  }
}

watch(
  () => user.user,
  () => {
    request++;
    data.value = undefined;
    resetRanks();
    loading.value = false;
    failed.value = false;
    void load();
  },
  { immediate: true }
);
onMounted(() => {
  timer = window.setInterval(() => {
    if (document.visibilityState === "visible") void load();
  }, 60_000);
});
onUnmounted(() => {
  request++;
  resetRanks();
  window.clearInterval(timer);
});

const missing = (key: string) => data.value?.unavailable.includes(key) ?? false;
const count = (value: string | number | null | undefined) =>
  value == null ? "—" : new Intl.NumberFormat(locale.value).format(BigInt(value));
const amount = (value: string | null | undefined) =>
  value == null ? "—" : formatAmountWithCurrency(value, "CNY");
const label = (key: string) => t(`workbench.${key}`);

const metrics = computed(() => {
  const { sales, creators, commission } = data.value ?? {};
  return [
    {
      key: "sales",
      visible: sales || missing("sales"),
      total: amount(sales?.totalAmount),
      today: amount(sales?.todayAmount),
      icon: Coin,
      tone: "blue"
    },
    {
      key: "orderCount",
      visible: sales || missing("sales"),
      total: count(sales?.totalOrders),
      today: count(sales?.todayOrders),
      icon: ShoppingCart,
      tone: "green"
    },
    {
      key: "creatorCount",
      visible: creators || missing("creators"),
      total: count(creators?.visible),
      today: count(creators?.today),
      icon: User,
      tone: "orange"
    },
    {
      key: "creatorCommission",
      visible: commission || missing("commission"),
      total: amount(commission?.totalAmount),
      today: amount(commission?.todayAmount),
      icon: Money,
      tone: "purple"
    }
  ].filter(metric => metric.visible);
});

const capacity = computed(() => {
  const shop = data.value?.shops;
  if (shop?.used == null || !Number(shop.limit)) return undefined;
  return (Number(shop.used) / Number(shop.limit)) * 100;
});

const operations = computed(() => {
  const overview = data.value;
  if (!overview) return [];
  return [
    {
      key: "capacity",
      visible: overview.shops || missing("shops"),
      icon: Goods,
      to: overview.shops ? { name: "ShopManagement", query: { enabledOnly: "1" } } : undefined
    },
    {
      key: "shopStatus",
      visible: overview.shops || missing("shops"),
      icon: House,
      details: [
        {
          key: "enabledShops",
          value: overview.shops?.enabled,
          to: { name: "ShopManagement", query: { enabledOnly: "1" } }
        },
        {
          key: "authExceptions",
          value: overview.shops?.authExceptions,
          tone: "exception",
          to: { name: "ShopManagement", query: { authException: "1" } }
        }
      ]
    },
    {
      key: "productTasks",
      visible: overview.jobs || missing("jobs") || missing("processingJobs"),
      icon: List,
      details: [
        {
          key: "processingJobs",
          value: overview.processingJobs,
          to: { name: "ProductTasks", query: { status: "processing" } }
        },
        {
          key: "failedJobs",
          value: overview.jobs?.total,
          tone: "exception",
          to: { name: "ProductTasks", query: { status: "HAS_FAILURE" } }
        }
      ]
    },
    {
      key: "creatorReview",
      visible:
        overview.pending ||
        overview.promoting != null ||
        missing("pending") ||
        missing("promoting"),
      icon: User,
      details: [
        {
          key: "promotingCreators",
          value: overview.promoting,
          to: { name: "KolList", query: { relationStatus: "PROMOTING" } }
        },
        {
          key: "pendingCreators",
          value: overview.pending?.total,
          tone: "pending",
          to: { name: "KolList", query: { reviewStatus: "PENDING" } }
        }
      ]
    },
    {
      key: "financialSettlement",
      visible:
        overview.payouts || overview.prepays != null || missing("payouts") || missing("prepays"),
      icon: Wallet,
      details: [
        {
          key: "payouts",
          value: overview.payouts?.total,
          tone: "pending",
          to: overview.payouts
            ? { name: "FundPayouts", query: { status: "ACTION_REQUIRED" } }
            : undefined,
          visible: overview.payouts || missing("payouts")
        },
        {
          key: "pendingPrepays",
          value: overview.prepays,
          tone: "pending",
          to:
            overview.prepays != null
              ? { name: "PrepayTasks", query: { status: "ACTION_REQUIRED" } }
              : undefined,
          visible: overview.prepays != null || missing("prepays")
        }
      ].filter(detail => detail.visible)
    }
  ].filter(operation => operation.visible);
});

const rankWidths = reactive({ SHOP: 0, CREATOR: 0 });
const rankTables = new Map<RankBoard, HTMLElement>();
const rankTableObserver =
  typeof ResizeObserver === "undefined"
    ? undefined
    : new ResizeObserver(() => {
        for (const [board, table] of rankTables) rankWidths[board] = table.clientWidth;
      });

function setRankTable(board: RankBoard, element: unknown) {
  const previous = rankTables.get(board);
  if (previous === element) return;
  if (previous) rankTableObserver?.unobserve(previous);
  if (element instanceof HTMLElement) {
    rankTables.set(board, element);
    rankWidths[board] = element.clientWidth;
    rankTableObserver?.observe(element);
  } else {
    rankTables.delete(board);
  }
}
onUnmounted(() => rankTableObserver?.disconnect());

function rankColumns(board: RankBoard, quantity: string): DataColumn[] {
  const tableWidth = rankWidths[board];
  const wide = tableWidth >= 560;
  const compact = tableWidth > 0 && tableWidth < 452;
  const widths = {
    rank: compact ? Math.round(tableWidth * 0.14) : 72,
    quantity: wide ? Math.round(tableWidth * 0.22) : compact ? Math.round(tableWidth * 0.23) : 110,
    amount: wide ? Math.round(tableWidth * 0.45) : compact ? Math.round(tableWidth * 0.38) : 150
  };
  return ["rank", "name", quantity, "amount"].map(key => ({
    key,
    label: key === "rank" ? t("analytics.rank") : label(key),
    ...(key === "name"
      ? { minWidth: compact ? 1 : 120 }
      : {
          width: key === "rank" ? widths.rank : key === "amount" ? widths.amount : widths.quantity,
          stretch: false
        }),
    amount: key === "amount",
    tooltip: key !== "rank",
    align: key === "rank" ? "center" : key === "name" ? "left" : "right"
  }));
}

const beijingDateTime = new Intl.DateTimeFormat("sv-SE", {
  timeZone: "Asia/Shanghai",
  dateStyle: "short",
  timeStyle: "medium"
});
function formatUpdatedAt(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : beijingDateTime.format(date).replaceAll("-", "/");
}

const rankPeriods = computed(() => [
  { value: "TODAY", label: label("rankToday") },
  { value: "LAST_7_DAYS", label: label("rankLast7Days") },
  { value: "LAST_30_DAYS", label: label("rankLast30Days") },
  { value: "ALL_TIME", label: label("rankAllTime") }
]);
const medals = ["🥇", "🥈", "🥉"];
const rankings = computed(() => {
  const overview = data.value;
  if (!overview) return [];
  return [
    {
      key: "topShops",
      board: "SHOP" as const,
      icon: ShoppingCart,
      rows: overview.topShops,
      quantity: "orders"
    },
    {
      key: "topCreators",
      board: "CREATOR" as const,
      icon: User,
      rows: overview.topCreators,
      quantity: "units"
    }
  ]
    .filter(panel => panel.rows || missing(panel.key))
    .map(panel => {
      const state = rankStates[panel.board];
      const page = state.result;
      const rows = page?.items ?? (state.period === "ALL_TIME" ? panel.rows : undefined);
      const offset = page ? (page.page - 1) * page.size : 0;
      return {
        ...panel,
        state,
        columns: rankColumns(panel.board, panel.quantity),
        rows: rows?.map((row, index) => ({
          id: row.id,
          rank: offset + index + 1,
          name: row.name,
          code: row.code,
          [panel.quantity]: count(row.quantity),
          amount: amount(row.amount)
        }))
      };
    });
});

const hasContent = computed(
  () => metrics.value.length || operations.value.length || rankings.value.length
);
</script>

<template>
  <el-scrollbar class="workbench-scroll" always :tabindex="0">
    <main class="workbench" :aria-label="t('route.home')">
      <header class="workbench__toolbar">
        <time v-if="data" :datetime="data.businessDate">
          {{ data.businessDate }} · {{ label("timezone") }}
        </time>
        <div class="workbench__refresh">
          <time v-if="data" :datetime="data.asOf">
            {{ t("workbench.updated", { time: formatUpdatedAt(data.asOf) }) }}
          </time>
          <el-button size="small" :loading="loading" @click="load">{{
            label("refresh")
          }}</el-button>
        </div>
      </header>

      <el-alert v-if="failed" :title="label('loadFailed')" type="error" :closable="false" />
      <el-skeleton v-if="loading && !data" :rows="4" animated />
      <p v-else-if="data && !hasContent">{{ t("home.body") }}</p>

      <section v-if="metrics.length" class="workbench__metrics" :aria-label="label('overview')">
        <div v-for="metric in metrics" :key="metric.key" class="workbench__metric">
          <el-icon class="workbench__metric-icon" :class="`workbench__metric-icon--${metric.tone}`">
            <component :is="metric.icon" />
          </el-icon>
          <div class="workbench__metric-copy">
            <h2 :title="label(`${metric.key}Hint`)">{{ label(metric.key) }}</h2>
            <strong class="workbench__total">{{ metric.total }}</strong>
            <div class="workbench__today">
              {{ label(`${metric.key}Today`) }} <strong>{{ metric.today }}</strong>
            </div>
          </div>
        </div>
      </section>

      <section v-if="operations.length" class="workbench__board">
        <h2>{{ label("operations") }}</h2>
        <div class="workbench__operations">
          <component
            :is="item.to ? RouterLink : 'article'"
            v-for="item in operations"
            :key="item.key"
            :to="item.to"
            class="workbench__operation"
            :class="{ 'workbench__operation--link': item.to }"
          >
            <h3>
              <el-icon><component :is="item.icon" /></el-icon>{{ label(item.key) }}
              <el-icon v-if="item.to" class="workbench__detail-arrow" aria-hidden="true">
                <ArrowRight />
              </el-icon>
            </h3>
            <div
              v-if="item.key === 'capacity' && capacity != null"
              class="workbench__capacity"
              :class="{ 'workbench__capacity--warning': capacity >= 80 }"
            >
              <strong class="workbench__operation-number">
                {{ count(data?.shops?.used) }} / {{ count(data?.shops?.limit) }}
              </strong>
              <el-progress
                :percentage="Math.min(100, capacity)"
                :stroke-width="6"
                :show-text="false"
                color="var(--workbench-capacity-color)"
                :aria-label="label('capacity')"
              />
              <span>{{ t("workbench.remaining", { number: count(data?.shops?.remaining) }) }}</span>
            </div>
            <div v-else-if="item.details?.length" class="workbench__details">
              <component
                :is="detail.to && detail.value != null ? RouterLink : 'div'"
                v-for="detail in item.details"
                :key="detail.key"
                :to="detail.value != null ? detail.to : undefined"
                class="workbench__detail"
                :class="{ 'workbench__detail--link': detail.to && detail.value != null }"
              >
                <span>{{ label(detail.key) }}</span>
                <strong
                  class="workbench__operation-number"
                  :class="
                    'tone' in detail && detail.tone && Number(detail.value) > 0
                      ? `workbench__number--${detail.tone}`
                      : undefined
                  "
                  >{{ count(detail.value) }}</strong
                >
                <el-icon
                  v-if="detail.to && detail.value != null"
                  class="workbench__detail-arrow"
                  aria-hidden="true"
                >
                  <ArrowRight />
                </el-icon>
              </component>
            </div>
            <span v-else class="workbench__unavailable">{{ label("unavailable") }}</span>
          </component>
        </div>
      </section>

      <div v-if="rankings.length" class="workbench__rankings">
        <section v-for="panel in rankings" :key="panel.key" class="workbench__rank">
          <div class="workbench__rank-header">
            <h2>
              <el-icon :class="`workbench__rank-icon--${panel.board.toLowerCase()}`">
                <component :is="panel.icon" />
              </el-icon>
              {{ label(panel.key) }}
            </h2>
            <div class="workbench__rank-controls">
              <el-segmented
                :model-value="panel.state.period"
                :options="rankPeriods"
                :aria-label="`${label(panel.key)} · ${label('rankPeriod')}`"
                size="small"
                @change="changeRankPeriod(panel.board, $event as RankPeriod)"
              />
              <el-button link type="primary" @click="toggleRanks(panel.board)">
                {{ label(panel.state.expanded ? "collapseRanking" : "seeAll") }}
                <el-icon v-if="!panel.state.expanded"><ArrowRight /></el-icon>
              </el-button>
            </div>
          </div>
          <el-alert
            v-if="panel.state.failed"
            :title="label('loadFailed')"
            type="error"
            :closable="false"
          />
          <el-skeleton v-if="panel.state.loading && !panel.rows?.length" :rows="3" animated />
          <p v-else-if="missing(panel.key) && !panel.state.result">{{ label("unavailable") }}</p>
          <div
            v-else-if="panel.rows?.length"
            :ref="element => setRankTable(panel.board, element)"
            class="workbench__rank-table"
            :aria-busy="panel.state.loading"
          >
            <DataTable
              :table-id="`workbench-${panel.key}`"
              :rows="panel.rows"
              :columns="panel.columns"
              :total="panel.rows.length"
              :paged="false"
              plain
            >
              <template #cell-rank="{ row }">
                <span
                  v-if="medals[Number(row.rank) - 1]"
                  class="workbench__rank-medal"
                  role="img"
                  :aria-label="count(Number(row.rank))"
                  >{{ medals[Number(row.rank) - 1] }}</span
                >
                <span v-else>{{ count(Number(row.rank)) }}</span>
              </template>
              <template #cell-name="{ row }">
                <span class="workbench__rank-name">{{ row.name || "—" }}</span>
                <small v-if="row.code" class="workbench__secondary">{{ row.code }}</small>
              </template>
            </DataTable>
          </div>
          <p v-else-if="!panel.state.failed">{{ label(`${panel.key}Empty`) }}</p>
          <el-pagination
            v-if="
              panel.state.expanded &&
              panel.state.result &&
              Number(panel.state.result.total) > panel.state.result.size
            "
            class="workbench__rank-footer"
            layout="prev, pager, next"
            :pager-count="5"
            :total="Number(panel.state.result.total)"
            :page-size="panel.state.result.size"
            :current-page="panel.state.result.page"
            :disabled="panel.state.loading"
            @current-change="showRankPage(panel.board, $event)"
          />
        </section>
      </div>
    </main>
  </el-scrollbar>
</template>

<style scoped>
.workbench-scroll {
  height: 100%;
  min-height: 0;
  --el-scrollbar-bg-color: #64748b;
  --el-scrollbar-opacity: 0.65;
  --el-scrollbar-hover-opacity: 0.9;
}
.workbench-scroll :deep(> .el-scrollbar__wrap) {
  overflow-x: hidden;
}
.workbench-scroll :deep(> .el-scrollbar__bar.is-vertical) {
  width: 10px;
}
.workbench-scroll :deep(> .el-scrollbar__wrap > .el-scrollbar__view) {
  padding-inline-end: 14px;
}
.workbench {
  container-type: inline-size;
  display: grid;
  gap: var(--tcrm-space-5);
  --tcrm-font-size-section: 18px;
  --tcrm-line-height-section: 26px;
  --tcrm-font-size-table: 16px;
  --tcrm-line-height-table: 25px;
  font-size: 15px;
  line-height: 1.55;
}
.workbench__toolbar,
.workbench__refresh {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.workbench__toolbar {
  justify-content: space-between;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}
.workbench__refresh {
  margin-inline-start: auto;
  justify-content: flex-end;
}
.workbench__toolbar time,
.workbench__secondary,
.workbench__today,
.workbench__details,
.workbench__capacity span,
.workbench__unavailable {
  color: var(--tcrm-color-text-secondary);
}
.workbench__metrics,
.workbench__board,
.workbench__rank {
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.workbench__metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(0, 1fr));
  padding-block: var(--tcrm-space-5);
}
.workbench__metric {
  display: flex;
  gap: var(--tcrm-space-3);
  padding: var(--tcrm-space-3) clamp(16px, 1.4cqw, 22px);
  border-inline-start: 1px solid var(--tcrm-color-border);
}
.workbench__metric:first-child {
  border-inline-start: 0;
}
.workbench__metric-icon {
  flex: 0 0 52px;
  width: 52px;
  height: 52px;
  border-radius: var(--tcrm-radius-control);
  font-size: 30px;
}
.workbench__metric-icon--blue {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.workbench__metric-icon--green {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}
.workbench__metric-icon--orange {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
}
.workbench__metric-icon--purple {
  color: #6854c8;
  background: #efebfb;
}
.workbench__metric-copy {
  min-width: 0;
}
.workbench__metric h2 {
  margin: 0;
  --tcrm-font-size-section: 19px;
  --tcrm-line-height-section: 28px;
  font-weight: 600;
}
.workbench__total {
  display: block;
  margin-block: 6px;
  font-size: clamp(25px, 2.7cqw, 35px);
  line-height: 1.2;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}
.workbench__today {
  font-size: 19px;
  overflow-wrap: anywhere;
}
.workbench__today strong {
  white-space: nowrap;
  color: var(--el-color-primary);
}
.workbench__board {
  padding: clamp(16px, 2cqw, 24px);
  background: #fff;
  border-color: #e2e8f0;
  box-shadow: 0 2px 8px rgb(15 23 42 / 4%);
}
.workbench__board h2 {
  margin: 0 0 var(--tcrm-space-4);
  padding-inline-start: var(--tcrm-space-2);
  border-inline-start: 3px solid #2563eb;
  --tcrm-font-size-section: 20px;
}
.workbench__operations,
.workbench__rankings {
  display: grid;
  gap: var(--tcrm-space-4);
}
.workbench__operations {
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 1fr));
}
.workbench__operation {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  padding: clamp(10px, 1.35cqw, 18px);
  border-radius: 10px;
  background: #f8fafc;
}
.workbench .workbench__operation--link {
  color: var(--el-text-color-primary);
  text-decoration: none;
}
.workbench__operation--link:hover,
.workbench__operation--link:focus-visible {
  background: #f1f5f9;
}
.workbench__operation h3 {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  margin: 0;
  font-size: 18px;
  font-weight: 700;
}
.workbench__operation h3 .el-icon:not(.workbench__detail-arrow) {
  color: #2563eb;
}
.workbench__operation h3 .workbench__detail-arrow {
  margin-inline-start: auto;
}
.workbench__capacity {
  --workbench-capacity-color: #2563eb;
  display: grid;
  gap: var(--tcrm-space-2);
  overflow-wrap: anywhere;
  font-size: 13px;
}
.workbench__capacity--warning {
  --workbench-capacity-color: #b45309;
}
.workbench__capacity :deep(.el-progress-bar__outer) {
  background: #e2e8f0;
}
.workbench__details {
  display: grid;
}
.workbench__detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 12px;
  align-items: center;
  min-height: 40px;
  padding: 4px 5px;
  font-size: 18px;
  overflow-wrap: anywhere;
  gap: 5px;
}
.workbench__detail + .workbench__detail {
  border-top: 1px solid rgb(0 0 0 / 5%);
}
.workbench .workbench__detail--link {
  color: var(--tcrm-color-text-secondary);
  text-decoration: none;
}
.workbench__detail--link:hover,
.workbench__detail--link:focus-visible {
  background: #fff;
}
.workbench__detail-arrow {
  flex: none;
  color: #94a3b8;
  font-size: 12px;
}
.workbench__operation-number {
  color: var(--el-text-color-primary);
  font-size: 23px;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.workbench__number--exception {
  color: #dc2626;
}
.workbench__number--pending {
  color: #b45309;
}
.workbench__rankings {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.workbench__rank {
  min-width: 0;
  padding: clamp(12px, 1.7cqw, 20px);
}
.workbench__rank-header,
.workbench__rank-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.workbench__rank-header {
  justify-content: space-between;
  margin-bottom: var(--tcrm-space-3);
}
.workbench__rank-controls {
  margin-inline-start: auto;
  justify-content: flex-end;
}
.workbench__rank h2 {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  margin: 0;
  padding: var(--tcrm-space-1);
  font-size: 18px;
}
.workbench__rank h2 .el-icon {
  font-size: 17px;
}
.workbench__rank-icon--shop {
  color: var(--el-color-primary);
}
.workbench__rank-icon--creator {
  color: var(--el-color-success);
}
.workbench__rank-table {
  container-type: inline-size;
  overflow: hidden;
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
}
.workbench__rank .workbench__rank-table :deep(.el-table .el-table__cell) {
  padding-block: var(--tcrm-space-3);
}
.workbench__rank-name,
.workbench__secondary {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workbench__secondary {
  margin-top: var(--tcrm-space-1);
}
.workbench__rank-table :deep(.el-table .cell) {
  padding-inline: 6px;
}
.workbench__rank-table :deep(.el-table) {
  width: 100%;
}
.workbench__rank-table :deep(.el-table :is(.el-table__header, .el-table__body)) {
  table-layout: fixed;
}
.workbench__rank-table :deep(.el-table .is-right .cell) {
  /* Element Plus applies an inline width to body cells with overflow tooltips. */
  width: 100% !important;
  padding-inline: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.workbench__rank-table :deep(.el-table .tcrm-amount .cell) {
  padding-inline-end: 24px;
}
@container (min-width: 560px) {
  .workbench__rank-table :deep(.el-table .tcrm-amount .cell) {
    padding-inline-end: 15cqi;
  }
}
@container (width < 452px) {
  .workbench__rank-table :deep(.el-table .cell) {
    padding-inline: 4px;
  }
  .workbench__rank-table :deep(.el-table .is-right .cell) {
    padding-inline: 5px;
    font-size: 13px;
  }
  .workbench__rank-table :deep(.el-table th.is-right .cell) {
    white-space: normal;
  }
}
.workbench__rank-table :deep(.el-table .cell .workbench__rank-medal) {
  font-family: "Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji", sans-serif;
  --tcrm-font-size-table: 24px;
  --tcrm-line-height-table: 1;
}
.workbench__rank-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--tcrm-space-2);
  margin-top: var(--tcrm-space-3);
}
.workbench a {
  color: var(--el-color-primary);
}
@container (max-width: 1280px) {
  .workbench__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    padding-block: 0;
  }
  .workbench__metric {
    padding-block: var(--tcrm-space-3);
  }
  .workbench__metric:nth-child(odd) {
    border-inline-start: 0;
  }
  .workbench__metric:nth-child(n + 3) {
    border-block-start: 1px solid var(--tcrm-color-border);
  }
}
@container (max-width: 980px) {
  .workbench__rankings {
    grid-template-columns: minmax(0, 1fr);
  }
}
@container (max-width: 420px) {
  .workbench__rank {
    padding-inline: 8px;
  }
  .workbench__rank-controls {
    max-width: 100%;
  }
  .workbench__metrics {
    grid-template-columns: minmax(0, 1fr);
  }
  .workbench__metric:nth-child(n) {
    border-inline-start: 0;
  }
  .workbench__metric + .workbench__metric {
    border-block-start: 1px solid var(--tcrm-color-border);
  }
}
</style>
