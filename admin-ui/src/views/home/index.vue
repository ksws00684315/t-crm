<script setup lang="ts">
import {
  ArrowRight,
  Coin,
  Goods,
  House,
  List,
  Money,
  Refresh,
  ShoppingCart,
  User,
  Wallet
} from "@element-plus/icons-vue";
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getWorkbench, getWorkbenchRanks, type RankBoard, type Workbench } from "@/api/workbench";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { formatAmountWithCurrency, formatInstant } from "@/utils/format";

defineOptions({ name: "HomePage" });
const { t, locale } = useI18n();
const user = useUserStore();
const data = ref<Workbench>();
const loading = ref(false);
const failed = ref(false);
let request = 0;
let timer: number | undefined;
let rankRequest = 0;
const expandedRanks = ref<RankBoard>();
const rankPage = ref<Awaited<ReturnType<typeof getWorkbenchRanks>>>();
const rankLoading = ref(false);
const rankFailed = ref(false);

async function showRankPage(board: RankBoard, page = 1) {
  const id = ++rankRequest;
  if (expandedRanks.value !== board) rankPage.value = undefined;
  expandedRanks.value = board;
  rankLoading.value = true;
  rankFailed.value = false;
  try {
    const result = await getWorkbenchRanks(board, page);
    if (id === rankRequest) rankPage.value = result;
  } catch {
    if (id === rankRequest) rankFailed.value = true;
  } finally {
    if (id === rankRequest) rankLoading.value = false;
  }
}

function collapseRanks() {
  rankRequest++;
  expandedRanks.value = undefined;
  rankPage.value = undefined;
  rankLoading.value = false;
  rankFailed.value = false;
}

async function load() {
  if (loading.value || !user.user || user.isPlatformAdmin) return;
  const id = ++request;
  loading.value = true;
  failed.value = false;
  try {
    const result = await getWorkbench();
    if (id === request) data.value = result;
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
    collapseRanks();
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
  collapseRanks();
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
  return Math.round((Number(shop.used) / Number(shop.limit)) * 100);
});

const operations = computed(() => {
  const overview = data.value;
  if (!overview) return [];
  return [
    {
      key: "capacity",
      visible: overview.shops || missing("shops"),
      icon: Goods,
      to: overview.shops ? { name: "ShopManagement" } : undefined,
      action: "viewShops"
    },
    {
      key: "shopStatus",
      visible: overview.shops || missing("shops"),
      icon: House,
      details: [
        { key: "enabledShops", value: overview.shops?.enabled },
        { key: "authExceptions", value: overview.shops?.authExceptions, alert: true }
      ],
      to: overview.shops ? { name: "ShopManagement", query: { authException: "1" } } : undefined,
      action: "viewExceptions"
    },
    {
      key: "productTasks",
      visible: overview.jobs || missing("jobs") || missing("processingJobs"),
      icon: List,
      details: [
        { key: "processingJobs", value: overview.processingJobs },
        { key: "failedJobs", value: overview.jobs?.total, alert: true }
      ],
      to: overview.jobs ? { name: "ProductTasks", query: { status: "HAS_FAILURE" } } : undefined,
      action: "viewFailedJobs"
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
        { key: "promotingCreators", value: overview.promoting },
        { key: "pendingCreators", value: overview.pending?.total, alert: true }
      ],
      to: overview.pending ? { name: "KolList", query: { reviewStatus: "PENDING" } } : undefined,
      action: "viewReviews"
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
          to: overview.payouts
            ? { name: "FundPayouts", query: { status: "ACTION_REQUIRED" } }
            : undefined,
          visible: overview.payouts || missing("payouts")
        },
        {
          key: "pendingPrepays",
          value: overview.prepays,
          to: overview.prepays != null ? { name: "PrepayTasks" } : undefined,
          visible: overview.prepays != null || missing("prepays")
        }
      ].filter(detail => detail.visible)
    }
  ].filter(operation => operation.visible);
});

function column(key: string, minWidth: number): DataColumn {
  return {
    key,
    label: key === "rank" ? t("analytics.rank") : label(key),
    minWidth,
    amount: key === "amount",
    tooltip: true,
    align: "center"
  };
}

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
      const expanded = expandedRanks.value === panel.board;
      const page = expanded ? rankPage.value : undefined;
      const offset = page ? (page.page - 1) * page.size : 0;
      return {
        ...panel,
        expanded,
        columns: [
          column("rank", 52),
          column("name", 100),
          column(panel.quantity, 80),
          column("amount", 106)
        ],
        rows: (page?.items ?? panel.rows)?.map((row, index) => ({
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
  <main class="workbench">
    <header class="workbench__header">
      <div>
        <h1>{{ t("route.home") }}</h1>
        <span v-if="data">{{ data.businessDate }} · {{ label("timezone") }}</span>
      </div>
      <div class="workbench__refresh">
        <span v-if="data">{{ t("workbench.updated", { time: formatInstant(data.asOf) }) }}</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">{{
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
        <article v-for="item in operations" :key="item.key" class="workbench__operation">
          <h3>
            <el-icon><component :is="item.icon" /></el-icon>{{ label(item.key) }}
          </h3>
          <div v-if="item.key === 'capacity' && capacity != null" class="workbench__capacity">
            <strong
              class="workbench__operation-number"
              :class="{ workbench__danger: Number(data?.shops?.remaining ?? -1) === 0 }"
            >
              {{ count(data?.shops?.used) }} / {{ count(data?.shops?.limit) }}
            </strong>
            <el-progress
              :percentage="Math.min(100, capacity)"
              :format="() => `${capacity}%`"
              :status="Number(data?.shops?.remaining ?? -1) === 0 ? 'exception' : undefined"
            />
            <span>{{ t("workbench.remaining", { number: count(data?.shops?.remaining) }) }}</span>
          </div>
          <div v-else-if="item.details?.length" class="workbench__details">
            <div
              v-for="detail in item.details"
              :key="detail.key"
              class="workbench__detail"
              :class="{
                workbench__danger: 'alert' in detail && detail.alert && Number(detail.value) > 0
              }"
            >
              <RouterLink v-if="'to' in detail && detail.to" :to="detail.to">
                {{ label(detail.key) }}
              </RouterLink>
              <span v-else>{{ label(detail.key) }}</span>
              <strong class="workbench__operation-number">{{ count(detail.value) }}</strong>
            </div>
          </div>
          <span v-else class="workbench__unavailable">{{ label("unavailable") }}</span>
          <RouterLink v-if="item.to" class="workbench__action" :to="item.to"
            >{{ label(item.action) }} <span aria-hidden="true">›</span></RouterLink
          >
        </article>
      </div>
    </section>

    <div v-if="rankings.length" class="workbench__rankings">
      <section v-for="panel in rankings" :key="panel.key" class="workbench__rank">
        <h2>
          <el-icon :class="`workbench__rank-icon--${panel.board.toLowerCase()}`">
            <component :is="panel.icon" />
          </el-icon>
          {{ label(panel.key) }}
        </h2>
        <el-alert
          v-if="panel.expanded && rankFailed"
          :title="label('loadFailed')"
          type="error"
          :closable="false"
        />
        <p v-if="missing(panel.key)">{{ label("unavailable") }}</p>
        <div v-else-if="panel.rows?.length" class="workbench__rank-table">
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
              <span>{{ row.name || "—" }}</span>
              <small v-if="row.code" class="workbench__secondary">{{ row.code }}</small>
            </template>
          </DataTable>
        </div>
        <p v-else>{{ label(`${panel.key}Empty`) }}</p>
        <div v-if="!missing(panel.key) && panel.rows?.length" class="workbench__rank-footer">
          <el-pagination
            v-if="panel.expanded && rankPage && Number(rankPage.total) > rankPage.size"
            layout="prev, pager, next"
            :pager-count="5"
            :total="Number(rankPage.total)"
            :page-size="rankPage.size"
            :current-page="rankPage.page"
            :disabled="rankLoading"
            @current-change="showRankPage(panel.board, $event)"
          />
          <el-button
            v-if="!panel.expanded || rankFailed"
            link
            type="primary"
            :loading="panel.expanded && rankLoading"
            @click="showRankPage(panel.board)"
          >
            {{ label(rankFailed && panel.expanded ? "refresh" : "seeAll") }}
            <el-icon><ArrowRight /></el-icon>
          </el-button>
          <el-button v-if="panel.expanded" link type="primary" @click="collapseRanks">
            {{ label("collapseRanking") }}
          </el-button>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.workbench {
  container-type: inline-size;
  display: grid;
  gap: var(--tcrm-space-5);
  --tcrm-font-size-title: 28px;
  --tcrm-line-height-title: 36px;
  --tcrm-font-size-section: 18px;
  --tcrm-line-height-section: 26px;
  --tcrm-font-size-table: 16px;
  --tcrm-line-height-table: 25px;
  font-size: 15px;
  line-height: 1.55;
}
.workbench__header,
.workbench__refresh {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: var(--tcrm-space-2);
}
.workbench__header h1 {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
}
.workbench__header span,
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
  font-size: 16px;
  overflow-wrap: anywhere;
}
.workbench__today strong {
  color: var(--el-color-primary);
}
.workbench__board {
  padding: clamp(16px, 2cqw, 24px);
}
.workbench__board h2 {
  margin: 0 0 var(--tcrm-space-4);
  padding-inline-start: var(--tcrm-space-2);
  border-inline-start: 3px solid var(--el-color-primary);
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
  align-items: start;
  gap: 6px;
  min-width: 0;
  padding: var(--tcrm-space-3);
  border-radius: var(--tcrm-radius-card);
  background: var(--el-fill-color-light);
}
.workbench__operation h3 {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  margin: 0;
  font-size: 18px;
}
.workbench__operation h3 .el-icon {
  color: var(--el-color-primary);
}
.workbench__capacity {
  display: grid;
  gap: var(--tcrm-space-1);
  align-self: stretch;
  overflow-wrap: anywhere;
}
.workbench__capacity .workbench__operation-number {
  font-size: 22px;
}
.workbench__capacity :deep(.el-progress__text) {
  min-width: auto;
  font-size: 15px !important;
}
.workbench__details {
  display: grid;
  align-self: stretch;
  gap: var(--tcrm-space-1);
}
.workbench__detail {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--tcrm-space-2);
}
.workbench__operation-number {
  color: var(--el-text-color-primary);
  font-size: 24px;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.workbench__danger,
.workbench__danger .workbench__operation-number {
  color: var(--tcrm-color-danger);
}
.workbench__action {
  margin-top: auto;
}
.workbench__rankings {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.workbench__rank {
  min-width: 0;
  padding: clamp(12px, 1.7cqw, 20px);
}
.workbench__rank h2 {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  margin: 0 0 var(--tcrm-space-3);
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
  overflow: hidden;
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
}
.workbench__rank .workbench__rank-table :deep(.el-table .el-table__cell) {
  padding-block: var(--tcrm-space-3);
}
.workbench__secondary {
  display: block;
  margin-top: var(--tcrm-space-1);
}
.workbench__rank-table :deep(.el-table .cell) {
  padding-inline: 6px;
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
.workbench__refresh :deep(.el-button),
.workbench__rank-footer :deep(.el-button) {
  font-size: 15px;
}
.workbench__refresh :deep(.el-button) {
  min-height: 36px;
}
.workbench a {
  color: var(--el-color-primary);
}
@container (max-width: 980px) {
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
@container (max-width: 700px) {
  .workbench__rankings {
    grid-template-columns: minmax(0, 1fr);
  }
}
@container (max-width: 420px) {
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
