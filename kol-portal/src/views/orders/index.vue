<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { getCreatorOrders, type CreatorOrder, type SettlementStatus } from "@/api/creator";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import {
  formatMoney,
  localDateRangeToUtc,
  recentLocalDateRange,
  type OrderRangeDays
} from "@/utils/orders";

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
  { key: "product", align: "left" as const, label: t("orders.productSku"), minWidth: 220 },
  { key: "payAmount", label: t("orders.orderAmount"), minWidth: 120, amount: true },
  { key: "payTime", label: t("orders.payTime"), minWidth: 170 },
  { key: "rate", label: t("orders.commissionRate"), minWidth: 110 },
  { key: "commission", label: t("orders.commissionAmount"), minWidth: 120, amount: true },
  { key: "settlementStatus", label: t("orders.settlementStatus"), minWidth: 130 }
]);
function money(value: string) {
  return formatMoney(value, locale.value);
}
function dateLabel(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString(locale.value);
}
function productLabel(row: CreatorOrder) {
  return (
    row.items
      .map(item => `${item.itemName}${item.skuName ? ` · ${item.skuName}` : ""}`)
      .join(" / ") || "—"
  );
}
function commission(row: CreatorOrder) {
  const values = row.items
    .map(item => item.commissionAmount)
    .filter((value): value is string => value != null);
  return values.length ? values.reduce((sum, value) => sum + Number(value), 0).toFixed(2) : null;
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
async function load() {
  loading.value = true;
  error.value = false;
  try {
    const [payFrom, payTo] = localDateRangeToUtc(...dates.value);
    const result = await getCreatorOrders({
      payFrom,
      payTo,
      ...(orderNo.value.trim() ? { orderNo: orderNo.value.trim() } : {}),
      ...(productName.value.trim() ? { productName: productName.value.trim() } : {}),
      ...(status.value ? { settlementStatus: status.value } : {}),
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
onMounted(load);
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
      :page="page"
      :size="size"
      @update:page="pageChanged"
      @refresh="load"
    >
      <template #cell-orderNo="{ row }"><CopyText :value="row.orderNo" /></template>
      <template #cell-product="{ row }"
        ><span class="product-cell">{{ productLabel(row) }}</span></template
      >
      <template #cell-payAmount="{ row }">{{ money(row.payAmount) }}</template>
      <template #cell-payTime="{ row }">{{ dateLabel(row.payTime) }}</template>
      <template #cell-rate="{ row }"
        ><span data-test="order-rates">{{ rate(row) }}</span></template
      >
      <template #cell-commission="{ row }">{{
        commission(row) == null ? "—" : money(commission(row)!)
      }}</template>
      <template #cell-settlementStatus="{ row }"
        ><el-tag v-if="row.settlementStatus" :type="statusType(row.settlementStatus)">{{
          t(`orders.settlement.${row.settlementStatus}`)
        }}</el-tag
        ><el-tooltip v-if="hasSplitSettlement(row)" :content="t('orders.splitHint')"
          ><el-tag type="warning">{{ t("orders.split") }}</el-tag></el-tooltip
        ><span v-if="!row.settlementStatus">—</span></template
      >
      <template #empty
        ><div class="empty-state">{{ t("orders.empty") }}</div></template
      >
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
</style>
