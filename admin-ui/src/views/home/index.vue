<script setup lang="ts">
import { Coin, Goods, Link, Refresh, ShoppingCart } from "@element-plus/icons-vue";
import { computed, onUnmounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getWorkbench, type Workbench } from "@/api/workbench";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { formatAmountWithCurrency, formatInstant, formatInstantToMinute } from "@/utils/format";
import MetricCard from "@/views/analytics/MetricCard.vue";

defineOptions({ name: "HomePage" });
const { t, locale } = useI18n();
const user = useUserStore();
const data = ref<Workbench>();
const loading = ref(false);
const failed = ref(false);
let request = 0;

async function load() {
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
  account => {
    request++;
    data.value = undefined;
    loading.value = false;
    failed.value = false;
    if (account && !user.isPlatformAdmin) void load();
  },
  { immediate: true }
);
onUnmounted(() => request++);

const missing = (field: string) => data.value?.unavailable.includes(field) ?? false;
const count = (value: string | number | null | undefined) =>
  value == null ? "—" : new Intl.NumberFormat(locale.value).format(Number(value));
const amount = (value: string) => formatAmountWithCurrency(value, "CNY");
const label = (field: string) => t(`workbench.${field}`);

const capacity = computed(() => {
  const shop = data.value?.shops;
  if (shop?.used == null || !Number(shop.limit)) return undefined;
  return Math.round((Number(shop.used) / Number(shop.limit)) * 100);
});

const shortcuts = computed(() => {
  const overview = data.value;
  if (!overview) return [];
  return [
    {
      key: "authExceptions",
      source: "shops",
      value: overview.shops?.authExceptions,
      to: { name: "ShopManagement", query: { authException: "1" } }
    },
    {
      key: "jobs",
      source: "jobs",
      value: overview.jobs?.total,
      to: { name: "ProductTasks", query: { status: "HAS_FAILURE" } }
    },
    {
      key: "pending",
      source: "pending",
      value: overview.pending?.total,
      to: { name: "KolList", query: { reviewStatus: "PENDING" } }
    },
    {
      key: "payouts",
      source: "payouts",
      value: overview.payouts?.total,
      to: { name: "FundPayouts", query: { status: "ACTION_REQUIRED" } }
    },
    {
      key: "estimatedCommission",
      source: "estimatedCommission",
      value: overview.estimatedCommission,
      to: undefined
    }
  ].filter(item => item.value != null || missing(item.source));
});

const peopleAndShops = computed(() => {
  const overview = data.value;
  return [
    { key: "visibleCreators", value: overview?.creators?.visible },
    { key: "approvedCreators", value: overview?.creators?.approved },
    { key: "enabledShops", value: overview?.shops?.enabled },
    { key: "authorizedShops", value: overview?.shops?.authorized }
  ].filter(item => item.value != null);
});

function column(key: string, width: number, amount = false): DataColumn {
  return {
    key,
    label: label(key),
    minWidth: width,
    amount,
    tooltip: true,
    align:
      amount || key === "orders" || key === "units" ? "right" : key === "name" ? "left" : "center"
  };
}

const panels = computed(() => {
  const overview = data.value;
  if (!overview) return [];
  const query = {
    startDate: overview.businessDate,
    endDate: overview.businessDate,
    sortBy: "salesAmount"
  };
  const rankRows = (rows: Workbench["topShops"]) =>
    rows?.map(row => ({
      id: row.code ?? row.name,
      name: row.name,
      code: row.code,
      quantity: count(row.quantity),
      amount: amount(row.amount)
    }));
  return [
    {
      key: "topShops",
      columns: [column("name", 105), column("orders", 60), column("amount", 110, true)],
      rows: rankRows(overview.topShops),
      to: { name: "AnalyticsShopPerformance", query }
    },
    {
      key: "topCreators",
      columns: [column("name", 105), column("units", 90), column("amount", 110, true)],
      rows: rankRows(overview.topCreators),
      to: { name: "AnalyticsKolRanking", query }
    },
    {
      key: "peopleAndShops",
      columns: [],
      rows: peopleAndShops.value.length || missing("creators") || missing("shops") ? [] : undefined
    },
    {
      key: "pending",
      columns: [column("name", 100), column("inviter", 85), column("createdAt", 148)],
      rows: overview.pending?.rows.map(row => ({
        id: row.code,
        name: row.name,
        code: row.code,
        inviter: row.inviter ?? "—",
        createdAt: formatInstantToMinute(row.createdAt)
      }))
    },
    {
      key: "payouts",
      columns: [column("applicationNo", 120), column("amount", 110, true), column("status", 110)],
      rows: overview.payouts?.rows.map(row => ({
        id: row.applicationNo,
        applicationNo: row.applicationNo,
        amount: amount(row.amount),
        status: t(`finance.${row.status}`) + (row.paused ? ` · ${t("finance.paused")}` : "")
      }))
    },
    {
      key: "jobs",
      columns: [
        column("jobCode", 115),
        column("creator", 80),
        column("progress", 65),
        column("status", 80)
      ],
      rows: overview.jobs?.rows.map(row => ({
        id: row.jobCode,
        jobCode: row.jobCode,
        creator: row.kolName ?? "—",
        progress: `${row.successCount}/${row.totalCount}`,
        status: label(row.status === "partial_failed" ? "partialFailure" : "failedJob")
      }))
    }
  ].filter(panel => panel.rows || missing(panel.key));
});

const hasContent = computed(() =>
  Boolean(
    data.value?.sales ||
    data.value?.shops ||
    data.value?.activeLinks != null ||
    data.value?.activeTokens != null ||
    missing("activeLinks") ||
    missing("activeTokens") ||
    shortcuts.value.length ||
    panels.value.length ||
    missing("sales")
  )
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

    <template v-if="data && hasContent">
      <div class="workbench__metrics">
        <template v-if="data.sales || missing('sales')">
          <MetricCard
            :label="label('sales')"
            :value="data.sales ? amount(data.sales.amount) : label('unavailable')"
            :icon="Coin"
            tone="blue"
            :hint="label('salesHint')"
            :unavailable="!data.sales"
          />
          <MetricCard
            :label="label('ordersToday')"
            :value="data.sales ? count(data.sales.orders) : label('unavailable')"
            :icon="ShoppingCart"
            tone="green"
            :hint="label('ordersHint')"
            :unavailable="!data.sales"
          />
        </template>
        <MetricCard
          v-if="
            data.activeLinks != null ||
            data.activeTokens != null ||
            missing('activeLinks') ||
            missing('activeTokens')
          "
          :label="label('links')"
          :value="count(data.activeLinks)"
          :icon="Link"
          tone="amber"
        >
          <template #value>
            <RouterLink
              v-if="data.activeLinks != null"
              :to="{ name: 'KolItemList', query: { status: 'ENABLED' } }"
            >
              {{ count(data.activeLinks) }}
            </RouterLink>
            <span v-else>{{ label("unavailable") }}</span>
            <RouterLink
              v-if="data.activeTokens != null"
              class="workbench__secondary"
              :to="{ name: 'CopyTokenList', query: { status: 'ACTIVE' } }"
            >
              {{ label("tokens") }} {{ count(data.activeTokens) }}
            </RouterLink>
            <small v-else-if="missing('activeTokens')" class="workbench__secondary"
              >{{ label("tokens") }} {{ label("unavailable") }}</small
            >
          </template>
        </MetricCard>
        <MetricCard
          v-if="data.shops || missing('shops')"
          :label="label('capacity')"
          :value="
            capacity == null
              ? label('capacityUnavailable')
              : `${count(data.shops?.used)} / ${count(data.shops?.limit)}`
          "
          :icon="Goods"
          tone="purple"
          :unavailable="capacity == null"
          :detail="
            capacity == null
              ? undefined
              : t('workbench.remaining', { number: count(data.shops?.remaining) })
          "
        >
          <template v-if="capacity != null" #value>
            {{ count(data.shops?.used) }} / {{ count(data.shops?.limit) }}
            <el-progress :percentage="Math.min(100, capacity)" :format="() => `${capacity}%`" />
          </template>
        </MetricCard>
      </div>

      <div v-if="shortcuts.length" class="workbench__shortcuts">
        <div v-for="item in shortcuts" :key="item.key">
          <RouterLink v-if="item.to && item.value != null" :to="item.to">
            {{ label(item.key) }} <strong>{{ count(item.value) }}</strong>
          </RouterLink>
          <span
            v-else
            :title="item.key === 'estimatedCommission' ? label('commissionHint') : undefined"
          >
            {{ label(item.key) }}
            <strong>{{
              item.value == null ? label("unavailable") : amount(String(item.value))
            }}</strong>
          </span>
        </div>
      </div>

      <div class="workbench__panels">
        <section v-for="panel in panels" :key="panel.key" class="workbench__panel">
          <h2>{{ label(panel.key) }}</h2>
          <template v-if="panel.key === 'peopleAndShops'">
            <dl v-if="peopleAndShops.length" class="workbench__counts">
              <div v-for="item in peopleAndShops" :key="item.key">
                <dt>{{ label(item.key) }}</dt>
                <dd>{{ count(item.value) }}</dd>
              </div>
            </dl>
            <p v-if="missing('creators') || missing('shops')">{{ label("partialUnavailable") }}</p>
          </template>
          <p v-else-if="missing(panel.key)">{{ label("unavailable") }}</p>
          <DataTable
            v-else-if="panel.rows?.length"
            :table-id="`workbench-${panel.key}`"
            :rows="panel.rows"
            :columns="panel.columns"
            :total="panel.rows.length"
            :paged="false"
            plain
          >
            <template #cell-name="{ row }">
              <span>{{ row.name || "—" }}</span>
              <small v-if="row.code" class="workbench__secondary">{{ row.code }}</small>
            </template>
            <template v-if="panel.key === 'topShops'" #cell-orders="{ row }">{{
              row.quantity
            }}</template>
            <template v-if="panel.key === 'topCreators'" #cell-units="{ row }">{{
              row.quantity
            }}</template>
          </DataTable>
          <p v-else>{{ label(`${panel.key}Empty`) }}</p>
          <RouterLink
            v-if="panel.to && !missing(panel.key)"
            class="workbench__more"
            :to="panel.to"
            >{{ label("seeAll") }}</RouterLink
          >
        </section>
      </div>
    </template>
  </main>
</template>

<style scoped>
.workbench {
  container-type: inline-size;
  display: grid;
  gap: var(--tcrm-space-4);
}
.workbench__header,
.workbench__refresh {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-2);
}
.workbench__header h1 {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
}
.workbench__header span,
.workbench__secondary,
.workbench__counts dt {
  color: var(--tcrm-color-text-secondary);
}
.workbench__metrics,
.workbench__panels,
.workbench__counts {
  display: grid;
  gap: var(--tcrm-space-4);
}
.workbench__metrics {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.workbench__panels {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.workbench__shortcuts,
.workbench__panel {
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.workbench__shortcuts {
  display: flex;
  flex-wrap: wrap;
}
.workbench__shortcuts > div {
  flex: 1 1 170px;
  padding: var(--tcrm-space-3);
  text-align: center;
}
.workbench__shortcuts strong {
  margin-inline-start: var(--tcrm-space-1);
}
.workbench__panel {
  min-width: 0;
  padding: var(--tcrm-space-3);
}
.workbench__panel h2 {
  margin: 0 0 var(--tcrm-space-3);
  font-size: var(--tcrm-font-size-body);
}
.workbench__counts {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: 0;
}
.workbench__counts > div {
  padding: var(--tcrm-space-3);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
}
.workbench__counts dd {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
  font-weight: 600;
}
.workbench__secondary,
.workbench__more {
  display: block;
  font-size: var(--tcrm-font-size-body);
  font-weight: 400;
}
.workbench__more {
  margin-top: var(--tcrm-space-3);
  text-align: end;
}
.workbench a {
  color: var(--el-color-primary);
}
.workbench a:hover {
  text-decoration: underline;
}
@container (max-width: 1079px) {
  .workbench__panels {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@container (max-width: 704px) {
  .workbench__panels {
    grid-template-columns: minmax(0, 1fr);
  }
}
@container (max-width: 655px) {
  .workbench__metrics {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
