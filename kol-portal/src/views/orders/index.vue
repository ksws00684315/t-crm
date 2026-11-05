<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { formatInstant } from "@/utils/format";
import {
  getCreatorOrders,
  getCreatorOrderSummary,
  type CreatorOrder,
  type CreatorOrderSummary,
  type SettlementStatus
} from "@/api/creator";
import AmountText from "@/components/AmountText.vue";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import { formatMoney, sumDecimals } from "@/utils/money";
import { localDateRangeToUtc, recentLocalDateRange, type OrderRangeDays } from "@/utils/orders";

defineOptions({ name: "OrdersPage" });
const { t, locale } = useI18n();
const range = ref<OrderRangeDays | "custom">(30);
const dates = ref<[string, string]>(recentLocalDateRange(30));
const customDates = ref<[Date, Date] | null>(null);
const orderNo = ref("");
const productName = ref("");
const status = ref<SettlementStatus | "">("");
const orders = ref<CreatorOrder[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const loading = ref(false);
const error = ref(false);
const expanded = ref(new Set<number>());
const summary = ref<CreatorOrderSummary | null>(null);
const summaryLoading = ref(false);
const summaryFailed = ref(false);
let summaryRequest = 0;
const statusOptions: SettlementStatus[] = [
  "WITHDRAWN",
  "FROZEN",
  "INVALID",
  "NO_COMMISSION",
  "WITHDRAWING",
  "WITHDRAWABLE"
];
const columns = computed<DataColumn[]>(() => [
  { key: "orderNo", align: "left" as const, label: t("orders.orderNo"), minWidth: 150 },
  { key: "product", align: "left" as const, label: t("orders.productSku"), minWidth: 150 },
  { key: "payAmount", label: t("orders.orderAmount"), minWidth: 120, amount: true },
  { key: "payTime", label: t("orders.payTime"), minWidth: locale.value === "zh-CN" ? 180 : 200 },
  { key: "rate", label: t("orders.commissionRate"), minWidth: 110 },
  // Room for the after-sales tag next to the amount (task 113).
  { key: "commission", label: t("orders.commissionAmount"), minWidth: 190, amount: true },
  {
    key: "settlementStatus",
    label: t("orders.settlementStatus"),
    minWidth: locale.value === "zh-CN" ? 130 : 165
  }
]);
function money(value: string) {
  return formatMoney(value, "CNY");
}
function dateLabel(value: string) {
  const text = formatInstant(value);
  return text === "-" ? "—" : text;
}
function productLabel(row: CreatorOrder) {
  return (
    row.items
      .map(item => `${item.itemName}${item.skuName ? ` · ${item.skuName}` : ""}`)
      .join(" / ") || "—"
  );
}
function commission(row: CreatorOrder) {
  return sumDecimals(row.items.map(item => item.commissionAmount));
}
function rate(row: CreatorOrder) {
  const values = [
    ...new Set(
      row.items.map(item => item.rateSnapshot).filter((value): value is string => value != null)
    )
  ];
  return (
    values
      .map(value => {
        if (value.includes("%")) return value;
        const number = Number(value);
        return Number.isFinite(number) ? `${(number * 100).toFixed(2)}%` : value;
      })
      .join(" / ") || "—"
  );
}
function statusType(value?: SettlementStatus | null): "success" | "warning" | "info" | "primary" {
  return (
    {
      WITHDRAWN: "success",
      FROZEN: "warning",
      INVALID: "info",
      NO_COMMISSION: "info",
      WITHDRAWING: "primary",
      WITHDRAWABLE: "success"
    } as const
  )[value ?? "INVALID"];
}
function hasSplitSettlement(row: CreatorOrder) {
  return row.items.some(
    item => item.settlementStatus && item.settlementStatus !== row.settlementStatus
  );
}
function toggle(index: number) {
  const next = new Set(expanded.value);
  if (next.has(index)) next.delete(index);
  else next.add(index);
  expanded.value = next;
}
/** The filters shared by the list and the summary, so both always cover the same orders. */
function currentFilter() {
  const [payFrom, payTo] = localDateRangeToUtc(...dates.value);
  return {
    payFrom,
    payTo,
    ...(orderNo.value.trim() ? { orderNo: orderNo.value.trim() } : {}),
    ...(productName.value.trim() ? { productName: productName.value.trim() } : {}),
    ...(status.value ? { settlementStatus: status.value } : {})
  };
}
const summaryCards = computed(() => [
  { key: "total", label: "orders.summaryTotal", amount: summary.value?.salesCny },
  { key: "settled", label: "orders.summarySettled", amount: summary.value?.settledCny },
  { key: "pending", label: "orders.summaryPending", amount: summary.value?.pendingCny },
  { key: "refunded", label: "orders.summaryRefunded", amount: summary.value?.refundedCny }
]);
async function loadSummary() {
  const current = ++summaryRequest;
  summaryLoading.value = true;
  summaryFailed.value = false;
  try {
    const result = await getCreatorOrderSummary(currentFilter());
    if (current !== summaryRequest) return;
    summary.value = result;
  } catch {
    if (current !== summaryRequest) return;
    // Never show zeros for figures that did not load.
    summary.value = null;
    summaryFailed.value = true;
  } finally {
    if (current === summaryRequest) summaryLoading.value = false;
  }
}
async function load() {
  loading.value = true;
  error.value = false;
  try {
    const result = await getCreatorOrders({
      ...currentFilter(),
      page: page.value,
      size: size.value
    });
    orders.value = result.items;
    total.value = Number(result.total);
  } catch {
    error.value = true;
  } finally {
    loading.value = false;
  }
}
function updateRange(value: OrderRangeDays | "custom") {
  range.value = value;
  if (value !== "custom") dates.value = recentLocalDateRange(value);
}
function updateCustom(value: [Date, Date] | null) {
  customDates.value = value;
  if (!value) return;
  dates.value = value.map(
    date =>
      `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`
  ) as [string, string];
}
function search() {
  page.value = 1;
  void load();
  void loadSummary();
}
function refresh() {
  void load();
  void loadSummary();
}
function reset() {
  range.value = 30;
  dates.value = recentLocalDateRange(30);
  customDates.value = null;
  orderNo.value = "";
  productName.value = "";
  status.value = "";
  search();
}
function pageChanged(value: number) {
  page.value = value;
  void load();
}
function changeSize(value: number) {
  size.value = value;
}
onMounted(() => {
  void load();
  void loadSummary();
});
</script>

<template>
  <section class="orders-page">
    <ListSearch @search="search" @reset="reset">
      <template #primary>
        <el-form-item :label="t('orders.dateRange')"
          ><el-select :model-value="range" @change="updateRange"
            ><el-option :label="t('orders.last7Days')" :value="7" /><el-option
              :label="t('orders.last30Days')"
              :value="30" /><el-option :label="t('orders.last90Days')" :value="90" /><el-option
              :label="t('orders.customRange')"
              value="custom" /></el-select
        ></el-form-item>
        <el-form-item v-if="range === 'custom'" :label="t('orders.customRange')"
          ><el-date-picker
            :model-value="customDates"
            type="daterange"
            format="YYYY-MM-DD"
            @update:model-value="updateCustom"
        /></el-form-item>
        <el-form-item :label="t('orders.orderNo')"
          ><el-input
            v-model="orderNo"
            data-test="orders-order-no"
            clearable
            :placeholder="t('orders.orderNo')"
            @keyup.enter="search"
        /></el-form-item>
        <el-form-item :label="t('orders.productName')"
          ><el-input
            v-model="productName"
            data-test="orders-product-name"
            clearable
            :placeholder="t('orders.productName')"
            @keyup.enter="search"
        /></el-form-item>
        <el-form-item :label="t('orders.settlementStatus')"
          ><el-select v-model="status" clearable :placeholder="t('orders.allStatuses')"
            ><el-option
              v-for="value in statusOptions"
              :key="value"
              :label="t(`orders.settlement.${value}`)"
              :value="value" /></el-select
        ></el-form-item>
      </template>
    </ListSearch>
    <section class="summary" :aria-busy="summaryLoading" data-test="orders-summary">
      <div v-if="summaryFailed" class="summary__failed" role="alert" data-test="summary-failed">
        <span>{{ t("orders.summaryFailed") }}</span>
        <el-button data-test="summary-retry" @click="loadSummary">{{
          t("orders.summaryRetry")
        }}</el-button>
      </div>
      <div v-else class="summary__cards">
        <div
          v-for="card in summaryCards"
          :key="card.key"
          class="summary-card"
          :class="`summary-card--${card.key}`"
          :data-test="`summary-${card.key}`"
        >
          <span>{{ t(card.label) }}</span>
          <strong class="tcrm-role-section" :data-test="`summary-${card.key}-amount`"
            ><AmountText v-if="card.amount != null" :value="card.amount" /><template v-else
              >—</template
            ></strong
          >
        </div>
      </div>
      <p class="summary__note" data-test="summary-note">{{ t("orders.summaryNote") }}</p>
    </section>
    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="t('orders.loadFailed')"
    />
    <DataTable
      table-id="kol-orders"
      class="orders-table"
      :rows="orders"
      :columns="columns"
      :loading="loading"
      :total="total"
      :paged="total > 0 && !error"
      :page="page"
      :size="size"
      @update:page="pageChanged"
      @update:size="changeSize"
      @refresh="refresh"
    >
      <!-- A comment-only slot falls back to DataTable's "0 in total". Keep a real element. -->
      <template #toolbar>
        <span v-if="!error" class="orders-total">{{ t("table.total", { total }) }}</span>
        <span v-else />
      </template>
      <template #cell-orderNo="{ row }"><CopyText :value="row.orderNo" /></template>
      <template #cell-product="{ row }"
        ><span class="product-cell">{{ productLabel(row) }}</span></template
      >
      <template #cell-payAmount="{ row }">{{ money(row.payAmount) }}</template>
      <template #cell-payTime="{ row }"
        ><span class="pay-time">{{ dateLabel(row.payTime) }}</span></template
      >
      <template #cell-rate="{ row }"
        ><span data-test="order-rates">{{ rate(row) }}</span></template
      >
      <template #cell-commission="{ row }"
        >{{ commission(row) == null ? "—" : money(commission(row)!)
        }}<el-tooltip v-if="row.afterSales" :content="t('orders.afterSalesHint')"
          ><el-tag type="warning" class="after-sales-tag" data-test="order-after-sales">{{
            t("orders.afterSales")
          }}</el-tag></el-tooltip
        ></template
      >
      <template #cell-settlementStatus="{ row }"
        ><el-tag v-if="row.settlementStatus" :type="statusType(row.settlementStatus)">{{
          t(`orders.settlement.${row.settlementStatus}`)
        }}</el-tag
        ><el-tooltip v-if="hasSplitSettlement(row)" :content="t('orders.splitHint')"
          ><el-tag type="warning">{{ t("orders.split") }}</el-tag></el-tooltip
        ><span v-if="!row.settlementStatus">—</span></template
      >
      <!-- On failure a real element replaces the copy: a comment-only slot would fall back to DataTable's generic empty text. -->
      <template #empty
        ><div v-if="!error" class="empty-state">{{ t("orders.empty") }}</div>
        <span v-else
      /></template>
    </DataTable>
    <div class="order-cards">
      <article v-for="(order, index) in orders" :key="index" class="order-card">
        <button
          class="order-card__summary"
          type="button"
          :aria-expanded="expanded.has(index)"
          @click="toggle(index)"
        >
          <strong>{{ order.orderNo }}</strong>
          <span>{{ productLabel(order) }}</span>
          <span>{{ t("orders.orderAmount") }}: {{ money(order.payAmount) }}</span>
          <span v-if="order.settlementStatus">{{
            t(`orders.settlement.${order.settlementStatus}`)
          }}</span>
        </button>
        <div v-if="expanded.has(index)" class="order-card__details">
          <span>{{ t("orders.payTime") }}: {{ dateLabel(order.payTime) }}</span>
          <span>{{ t("orders.commissionRate") }}: {{ rate(order) }}</span>
          <span
            >{{ t("orders.commissionAmount") }}:
            {{ commission(order) == null ? "—" : money(commission(order)!) }}</span
          >
          <span
            v-if="order.afterSales"
            class="after-sales-note"
            data-test="order-card-after-sales"
            >{{ t("orders.afterSalesHint") }}</span
          >
          <div
            v-for="(item, itemIndex) in order.items"
            :key="item.itemId ?? itemIndex"
            class="order-card__item"
          >
            {{ item.itemName }}<span v-if="item.skuName"> · {{ item.skuName }}</span>
          </div>
        </div>
      </article>
    </div>
    <el-pagination
      v-if="total > 0 && !error"
      class="orders-pager"
      :current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next"
      @current-change="pageChanged"
    />
  </section>
</template>

<style scoped>
.after-sales-tag {
  margin-left: var(--tcrm-space-1);
}
.after-sales-note {
  color: var(--el-color-warning);
}
/* A height the rows cannot change, or DataTable's ceiling shrinks with its own table. On a very short
   window the floor keeps the first row readable and hands scrolling to the page. */
.orders-page {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  height: 100%;
  min-height: 420px;
  min-width: 0;
}
h1 {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-weight: 600;
}
.empty-state {
  padding: var(--tcrm-space-6);
  text-align: center;
  color: var(--tcrm-color-text-secondary);
}
.product-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.orders-total {
  color: var(--tcrm-color-text-secondary);
}
.pay-time {
  white-space: nowrap;
}
.summary {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-2);
}
.summary__cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-2);
}
.summary-card {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0 var(--tcrm-space-2);
  min-width: 0;
  padding: var(--tcrm-space-2) var(--tcrm-space-3);
  border-radius: var(--tcrm-radius-control);
}
.summary-card strong {
  font-variant-numeric: tabular-nums;
  overflow-wrap: break-word;
}
.summary-card--total {
  background: var(--tcrm-color-order-band);
  color: var(--tcrm-color-primary-text);
}
.summary-card--settled {
  background: var(--tcrm-color-success-bg);
  color: var(--tcrm-color-success);
}
.summary-card--pending {
  background: var(--tcrm-color-warning-bg);
  color: var(--tcrm-color-warning);
}
.summary-card--refunded {
  background: var(--tcrm-color-danger-bg);
  color: var(--tcrm-color-danger);
}
.summary__failed {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2);
  padding: var(--tcrm-space-2) var(--tcrm-space-3);
  border-radius: var(--tcrm-radius-control);
  background: var(--tcrm-color-danger-bg);
  color: var(--tcrm-color-danger);
}
.summary__note {
  margin: 0;
  color: var(--tcrm-color-text-secondary);
}
.order-cards,
.orders-pager {
  display: none;
}
.order-card {
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  overflow: hidden;
}
.order-card__summary {
  display: grid;
  gap: var(--tcrm-space-2);
  width: 100%;
  padding: var(--tcrm-space-4);
  text-align: left;
  border: 0;
  background: var(--tcrm-color-card);
}
.order-card__details {
  display: grid;
  gap: var(--tcrm-space-2);
  padding: 0 var(--tcrm-space-4) var(--tcrm-space-4);
}
.order-card__item {
  padding-top: var(--tcrm-space-2);
  border-top: 1px solid var(--tcrm-color-border);
}
@media (max-width: 767px) {
  .summary__cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .orders-page {
    height: auto;
    min-height: 0;
  }
  .orders-table {
    display: none;
  }
  .order-cards {
    display: grid;
    gap: var(--tcrm-space-3);
  }
  .orders-pager {
    display: flex;
    justify-content: center;
  }
  .product-cell {
    white-space: normal;
    overflow-wrap: anywhere;
  }
}
@media (max-width: 479px) {
  .summary__cards {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
