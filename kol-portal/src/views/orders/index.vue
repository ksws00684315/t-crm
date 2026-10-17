<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getKolOrders, type KolOrder, type OrderStatus } from "@/api/orders";
import {
  formatMoney,
  localDateRangeToUtc,
  recentLocalDateRange,
  type OrderRangeDays
} from "@/utils/orders";
import OrderItems from "./OrderItems.vue";

defineOptions({ name: "OrdersPage" });

const { t, locale } = useI18n();
const range = ref<OrderRangeDays | "custom">(30);
const selectedDates = ref<[string, string]>(recentLocalDateRange(30));
const customDates = ref<[Date, Date] | null>(null);
const status = ref<OrderStatus | "">("");
const orders = ref<KolOrder[]>([]);
const total = ref(0);
const page = ref(1);
const size = 20;
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

function amount(value: string): string {
  return formatMoney(value, locale.value);
}

function dateLabel(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(locale.value);
}

async function loadOrders() {
  loading.value = true;
  error.value = false;
  try {
    const [payFrom, payTo] = localDateRangeToUtc(...selectedDates.value);
    const result = await getKolOrders({
      payFrom,
      payTo,
      ...(status.value ? { orderStatus: status.value } : {}),
      page: page.value,
      size
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
  range.value = value;
  if (value !== "custom") {
    selectedDates.value = recentLocalDateRange(value);
    page.value = 1;
  }
}

function applyCustomDates(value: [Date, Date] | null) {
  if (!value) return;
  customDates.value = value;
  selectedDates.value = value.map(date => {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return year + "-" + month + "-" + day;
  }) as [string, string];
  page.value = 1;
}

function toggle(index: number) {
  const next = new Set(expanded.value);
  if (next.has(index)) next.delete(index);
  else next.add(index);
  expanded.value = next;
}

watch(status, () => {
  page.value = 1;
});
watch([selectedDates, status, page], loadOrders, { deep: true });
onMounted(loadOrders);
</script>

<template>
  <section class="orders-page">
    <h1>{{ t("orders.title") }}</h1>
    <div class="filters">
      <el-select :model-value="range" :aria-label="t('orders.dateRange')" @change="chooseRange">
        <el-option :label="t('orders.last7Days')" :value="7" />
        <el-option :label="t('orders.last30Days')" :value="30" />
        <el-option :label="t('orders.last90Days')" :value="90" />
        <el-option :label="t('orders.customRange')" value="custom" />
      </el-select>
      <el-date-picker
        v-if="range === 'custom'"
        :model-value="customDates"
        type="daterange"
        format="YYYY-MM-DD"
        :start-placeholder="t('orders.fromDate')"
        :end-placeholder="t('orders.toDate')"
        @update:model-value="applyCustomDates"
      />
      <el-select
        v-model="status"
        clearable
        :placeholder="t('orders.allStatuses')"
        :aria-label="t('orders.status')"
      >
        <el-option
          v-for="value in statusOptions"
          :key="value"
          :label="t('orders.statuses.' + value)"
          :value="value"
        />
      </el-select>
    </div>

    <el-alert v-if="error" type="error" :title="t('orders.loadFailed')" :closable="false" />
    <div v-else-if="!loading && total === 0" class="empty-state">{{ t("orders.empty") }}</div>
    <el-skeleton v-else-if="loading" :rows="4" animated />

    <template v-else>
      <el-table class="orders-table" :data="orders">
        <el-table-column type="expand">
          <template #default="{ row }">
            <OrderItems :items="row.items" :amount="amount" :labels="itemLabels" />
          </template>
        </el-table-column>
        <el-table-column prop="orderNo" :label="t('orders.orderNo')" min-width="150" />
        <el-table-column :label="t('orders.payTime')" min-width="180">
          <template #default="{ row }">{{ dateLabel(row.payTime) }}</template>
        </el-table-column>
        <el-table-column :label="t('orders.status')" min-width="130">
          <template #default="{ row }">
            <el-tag>{{ t("orders.statuses." + row.orderStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('orders.payAmount')" min-width="130">
          <template #default="{ row }">{{ amount(row.payAmount) }}</template>
        </el-table-column>
        <el-table-column :label="t('orders.refundAmount')" min-width="130">
          <template #default="{ row }">{{ amount(row.refundAmount) }}</template>
        </el-table-column>
      </el-table>

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
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
      />
    </template>
  </section>
</template>

<style scoped>
.orders-page {
  min-width: 0;
}
.orders-page h1 {
  font-size: 24px;
  margin: 0 0 20px;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.empty-state {
  padding: 48px 16px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.order-cards {
  display: none;
}
.item-list {
  display: grid;
  gap: 8px;
}
.item-list__head,
.item-list__row {
  display: grid;
  grid-template-columns: 2fr 1.5fr 0.6fr 1fr 1fr;
  gap: 8px;
}
.item-list__head {
  font-weight: 600;
}
.order-card__summary {
  display: grid;
  gap: 8px;
  width: 100%;
  padding: 14px;
  text-align: left;
  border: 0;
  background: transparent;
  cursor: pointer;
}
.order-card__number {
  font-weight: 600;
}
.order-card__details {
  padding: 0 14px 14px;
}
.el-pagination {
  justify-content: center;
  margin-top: 20px;
}
@media (max-width: 767px) {
  .orders-table {
    display: none;
  }
  .order-cards {
    display: grid;
    gap: 12px;
  }
  .order-card {
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    overflow: hidden;
  }
  .item-list__head,
  .item-list__row {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    overflow-wrap: anywhere;
  }
  .filters > * {
    min-width: 0;
    max-width: 100%;
  }
}
@media (max-width: 390px) {
  .filters {
    display: grid;
    grid-template-columns: 1fr;
  }
  .filters > * {
    width: 100%;
  }
}
</style>
