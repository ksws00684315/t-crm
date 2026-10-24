<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { getKolOrders, type KolOrder, type OrderStatus } from "@/api/orders";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { readPageSize } from "@/utils/table-prefs";
import {
  formatMoney,
  localDateRangeToUtc,
  recentLocalDateRange,
  type OrderRangeDays
} from "@/utils/orders";
import OrderItems from "./OrderItems.vue";

defineOptions({ name: "OrdersPage" });

/** The filters that the last search applied; the draft fields below only take effect on search. */
interface AppliedFilters {
  range: OrderRangeDays | "custom";
  dates: [string, string];
  status: OrderStatus | "";
}

const DEFAULT_RANGE: OrderRangeDays = 30;
const { t, locale } = useI18n();
const draftRange = ref<OrderRangeDays | "custom">(DEFAULT_RANGE);
const draftDates = ref<[string, string]>(recentLocalDateRange(DEFAULT_RANGE));
const draftCustomDates = ref<[Date, Date] | null>(null);
const draftStatus = ref<OrderStatus | "">("");
const applied = reactive<AppliedFilters>({
  range: DEFAULT_RANGE,
  dates: recentLocalDateRange(DEFAULT_RANGE),
  status: ""
});
const orders = ref<KolOrder[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(readPageSize("kol-orders", 20));
const loading = ref(false);
const error = ref(false);
const expanded = ref(new Set<number>());

const statusOptions: OrderStatus[] = [
  "UNPAID",
  "PAID",
  "SHIPPED",
  "RECEIVED",
  "COMPLETED",
  "CLOSED",
  "UNKNOWN"
];
const itemLabels = computed(() => ({
  item: t("orders.item"),
  sku: t("orders.sku"),
  quantity: t("orders.quantity"),
  payAmount: t("orders.payAmount"),
  refundAmount: t("orders.refundAmount")
}));
const columns = computed<DataColumn[]>(() => [
  { key: "orderNo", label: t("orders.orderNo"), minWidth: 160 },
  { key: "payTime", label: t("orders.payTime"), minWidth: 180 },
  { key: "orderStatus", label: t("orders.status"), minWidth: 130 },
  { key: "payAmount", label: t("orders.payAmount"), minWidth: 130, amount: true },
  { key: "refundAmount", label: t("orders.refundAmount"), minWidth: 130, amount: true }
]);
const chips = computed<SearchChip[]>(() => {
  void locale.value;
  const result: SearchChip[] = [];
  if (applied.range !== DEFAULT_RANGE) {
    const text =
      applied.range === "custom"
        ? `${applied.dates[0]} ~ ${applied.dates[1]}`
        : t(`orders.last${applied.range}Days`);
    result.push({ key: "range", label: t("orders.dateRange"), text });
  }
  if (applied.status) {
    result.push({
      key: "status",
      label: t("orders.status"),
      text: t("orders.statuses." + applied.status)
    });
  }
  return result;
});

function amount(value: string): string {
  return formatMoney(value, locale.value);
}

function dateLabel(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(locale.value);
}

async function load() {
  loading.value = true;
  error.value = false;
  try {
    const [payFrom, payTo] = localDateRangeToUtc(...applied.dates);
    const result = await getKolOrders({
      payFrom,
      payTo,
      ...(applied.status ? { orderStatus: applied.status } : {}),
      page: page.value,
      size: size.value
    });
    orders.value = result.items;
    total.value = Number(result.total);
    expanded.value = new Set();
  } catch {
    error.value = true;
  } finally {
    loading.value = false;
  }
}

function chooseRange(value: OrderRangeDays | "custom") {
  draftRange.value = value;
  if (value !== "custom") {
    draftDates.value = recentLocalDateRange(value);
  }
}

function applyCustomDates(value: [Date, Date] | null) {
  draftCustomDates.value = value;
  if (!value) return;
  draftDates.value = value.map(date => {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return year + "-" + month + "-" + day;
  }) as [string, string];
}

/** Applies the draft filters and reloads from the first page. */
function search() {
  applied.range = draftRange.value;
  applied.dates = [draftDates.value[0], draftDates.value[1]];
  applied.status = draftStatus.value;
  page.value = 1;
  void load();
}

function reset() {
  draftRange.value = DEFAULT_RANGE;
  draftDates.value = recentLocalDateRange(DEFAULT_RANGE);
  draftCustomDates.value = null;
  draftStatus.value = "";
  search();
}

function removeChip(key: string) {
  if (key === "status") draftStatus.value = "";
  if (key === "range") {
    draftRange.value = DEFAULT_RANGE;
    draftDates.value = recentLocalDateRange(DEFAULT_RANGE);
    draftCustomDates.value = null;
  }
  search();
}

function changePage(value: number) {
  page.value = value;
  void load();
}

function changeSize(value: number) {
  size.value = value;
}

function toggle(index: number) {
  const next = new Set(expanded.value);
  if (next.has(index)) next.delete(index);
  else next.add(index);
  expanded.value = next;
}

onMounted(load);
</script>

<template>
  <section class="orders-page">
    <ListSearch :chips="chips" @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('orders.dateRange')">
          <el-select
            :model-value="draftRange"
            :aria-label="t('orders.dateRange')"
            data-test="orders-range"
            @change="chooseRange"
          >
            <el-option :label="t('orders.last7Days')" :value="7" />
            <el-option :label="t('orders.last30Days')" :value="30" />
            <el-option :label="t('orders.last90Days')" :value="90" />
            <el-option :label="t('orders.customRange')" value="custom" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="draftRange === 'custom'" :label="t('orders.customRange')">
          <el-date-picker
            :model-value="draftCustomDates"
            type="daterange"
            format="YYYY-MM-DD"
            :start-placeholder="t('orders.fromDate')"
            :end-placeholder="t('orders.toDate')"
            @update:model-value="applyCustomDates"
          />
        </el-form-item>
        <el-form-item :label="t('orders.status')">
          <el-select
            v-model="draftStatus"
            clearable
            :placeholder="t('orders.allStatuses')"
            :aria-label="t('orders.status')"
            data-test="orders-status"
          >
            <el-option
              v-for="value in statusOptions"
              :key="value"
              :label="t('orders.statuses.' + value)"
              :value="value"
            />
          </el-select>
        </el-form-item>
      </template>
    </ListSearch>

    <el-alert v-if="error" type="error" :title="t('orders.loadFailed')" :closable="false" />

    <DataTable
      table-id="kol-orders"
      class="orders-table"
      :rows="orders"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="page"
      :size="size"
      expandable
      @update:page="changePage"
      @update:size="changeSize"
      @refresh="load"
    >
      <template #cell-orderNo="{ row }">
        <CopyText :value="row.orderNo" />
      </template>
      <template #cell-payTime="{ row }">{{ dateLabel(row.payTime) }}</template>
      <template #cell-orderStatus="{ row }">
        <el-tag>{{ t("orders.statuses." + row.orderStatus) }}</el-tag>
      </template>
      <template #cell-payAmount="{ row }">{{ amount(row.payAmount) }}</template>
      <template #cell-refundAmount="{ row }">{{ amount(row.refundAmount) }}</template>
      <template #expand="{ row }">
        <OrderItems :items="row.items" :amount="amount" :labels="itemLabels" />
      </template>
      <template #empty>
        <div class="empty-state" data-test="orders-empty">{{ t("orders.empty") }}</div>
      </template>
    </DataTable>

    <!-- Phones show the same rows as cards; the table and its pager are desktop-only. -->
    <div class="order-cards">
      <article v-for="(order, index) in orders" :key="index" class="order-card">
        <button
          class="order-card__summary"
          type="button"
          :aria-expanded="expanded.has(index)"
          @click="toggle(index)"
        >
          <span class="order-card__number">{{ order.orderNo }}</span>
          <span>{{ dateLabel(order.payTime) }}</span>
          <span
            ><el-tag size="small">{{ t("orders.statuses." + order.orderStatus) }}</el-tag></span
          >
          <span>{{ t("orders.payAmount") }}: {{ amount(order.payAmount) }}</span>
          <span>{{ t("orders.refundAmount") }}: {{ amount(order.refundAmount) }}</span>
        </button>
        <div v-if="expanded.has(index)" class="order-card__details">
          <OrderItems :items="order.items" :amount="amount" :labels="itemLabels" />
        </div>
      </article>
    </div>
    <el-pagination
      class="orders-pager"
      :current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next"
      @current-change="changePage"
    />
  </section>
</template>

<style scoped>
.orders-page {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
.empty-state {
  padding: var(--tcrm-space-6) var(--tcrm-space-4);
  color: var(--el-text-color-secondary);
  text-align: center;
}
.order-cards,
.orders-pager {
  display: none;
}
.order-card__summary {
  display: grid;
  gap: var(--tcrm-space-2);
  width: 100%;
  padding: var(--tcrm-space-4);
  text-align: left;
  border: 0;
  background: transparent;
  cursor: pointer;
}
.order-card__number {
  font-weight: 600;
}
.order-card__details {
  padding: 0 var(--tcrm-space-4) var(--tcrm-space-4);
}
@media (max-width: 767px) {
  .orders-page .orders-table {
    display: none;
  }
  .order-cards {
    display: grid;
    gap: var(--tcrm-space-3);
  }
  .order-card {
    border: 1px solid var(--tcrm-color-border);
    border-radius: var(--tcrm-radius-card);
    overflow: hidden;
  }
  .orders-pager {
    display: flex;
    justify-content: center;
  }
}
@media (max-width: 390px) {
  .orders-page :deep(.list-search__form .el-form-item) {
    width: 100%;
  }
}
</style>
