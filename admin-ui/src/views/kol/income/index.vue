<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import FinancePreviewNotice from "@/components/FinancePreviewNotice.vue";
import FinanceUnavailable from "@/components/FinanceUnavailable.vue";
import { listIncome } from "@/api/commission/finance";
import type { Income, IncomeQuery, IncomeResult } from "@/api/commission/finance-types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import RowActions from "@/components/RowActions.vue";
import CopyText from "@/components/CopyText.vue";
import { formatInstant, formatPrice } from "@/utils/format";
import { financeApiReady, previewContext, periodBounds } from "@/utils/finance";
import { notifyError } from "@/utils/notify";
import IncomeDrawer from "./IncomeDrawer.vue";
defineOptions({ name: "KolIncomePage" });
const { t, locale } = useI18n();
const filters = reactive({ keyword: "", paymentType: "" });
const dates = ref<[string, string] | null>(null);
const page = ref(1);
const size = ref(20);
const result = ref<IncomeResult>();
const rows = ref<Income[]>([]);
const loading = ref(false);
const failed = ref(false);
const selected = ref("");
const drawer = ref(false);
const applied = ref<IncomeQuery>({});
function query(): IncomeQuery {
  return { ...filters, page: page.value, size: size.value, ...periodBounds(dates.value) };
}

async function load() {
  if (!financeApiReady) return;
  loading.value = true;
  failed.value = false;
  try {
    applied.value = query();
    result.value = await listIncome(applied.value);
    rows.value = result.value.items;
  } catch (error) {
    result.value = undefined;
    rows.value = [];
    failed.value = true;
    notifyError(error);
  } finally {
    loading.value = false;
  }
}
function search() {
  page.value = 1;
  void load();
}
function reset() {
  Object.assign(filters, { keyword: "", paymentType: "" });
  dates.value = null;
  search();
}
const chips = computed(() =>
  Object.entries(filters)
    .filter(([, value]) => value)
    .map(([key, value]) => ({
      key,
      label: t(`finance.${key}`),
      text: key === "paymentType" ? t(`finance.${value}`) : value
    }))
    .concat(
      dates.value
        ? [{ key: "period", label: t("finance.period"), text: dates.value.join(" – ") }]
        : []
    )
);
function remove(key: string) {
  if (key === "period") dates.value = null;
  else filters[key as keyof typeof filters] = "";
  search();
}
const columns = computed<DataColumn[]>(() =>
  [
    "creator",
    "bd",
    "sales",
    "estimated",
    "frozen",
    "available",
    "paid",
    "lastPaidAt",
    "actions"
  ].map(key => ({
    key,
    prop: key,
    label: t(`finance.${key}`),
    minWidth:
      key === "creator"
        ? 250
        : key === "paid"
          ? 230
          : key === "lastPaidAt"
            ? 200
            : key === "actions"
              ? 100
              : locale.value === "en-US"
                ? 190
                : 140,
    amount: ["estimated", "frozen", "available", "paid"].includes(key),
    tooltip: ["bd"].includes(key)
  }))
);
function actions(row: Income) {
  if (!row.id) return [];
  return [
    {
      key: "ledger",
      testId: "income-ledger",
      label: t("finance.ledger"),
      onClick: () => {
        selected.value = row.id;
        drawer.value = true;
      }
    }
  ];
}
watch([page, size], load);
watch(
  () => [previewContext.role, previewContext.scenario],
  () => {
    drawer.value = false;
    search();
  }
);
onMounted(load);
</script>
<template>
  <FinanceUnavailable v-if="!financeApiReady" />
  <div v-else class="finance-page" data-test="income-page">
    <FinancePreviewNotice />
    <div class="finance-content">
      <ListSearch :chips="chips" @search="search" @reset="reset" @remove="remove"
        ><template #primary>
          <el-form-item :label="t('finance.keyword')"
            ><el-input v-model="filters.keyword" data-test="income-keyword" clearable
          /></el-form-item>
          <el-form-item :label="t('finance.paymentType')"
            ><el-select v-model="filters.paymentType" data-test="payment-type" clearable
              ><el-option :label="t('finance.all')" value="" /><el-option
                v-for="type in ['COMMISSION', 'PREPAY']"
                :key="type"
                :label="t(`finance.${type}`)"
                :value="type" /></el-select
          ></el-form-item>
          <el-form-item :label="t('finance.period')"
            ><el-date-picker
              v-model="dates"
              type="daterange"
              value-format="YYYY-MM-DD"
              data-test="income-dates"
          /></el-form-item> </template
      ></ListSearch>
      <div v-if="result" class="income-summary" data-test="income-summary">
        <article>
          <span>{{ t("finance.periodPaid") }}</span
          ><strong>{{ formatPrice(result.summary.paid, "CNY") }}</strong
          ><small class="summary-breakdown"
            ><span
              >{{ t("finance.commissionPaid") }}
              {{ formatPrice(result.summary.commissionPaid, "CNY") }}</span
            ><span
              >{{ t("finance.prepayPaid") }}
              {{ formatPrice(result.summary.prepayPaid, "CNY") }}</span
            ></small
          >
        </article>
        <article v-for="key in ['creators', 'orders', 'quantity'] as const" :key="key">
          <span>{{ t(`finance.${key}`) }}</span
          ><strong>{{ result.summary[key] }}</strong>
        </article>
      </div>
      <div class="income-notes">
        <el-tooltip :content="`${t('finance.currentNote')} ${t('finance.salesNote')}`">
          <el-button text type="info" data-test="income-definitions">{{
            t("finance.definitions")
          }}</el-button>
        </el-tooltip>
        <el-tooltip
          v-if="result?.summary.unverified"
          :content="t('finance.unverifiedNotice', { count: result.summary.unverified })"
        >
          <el-tag type="warning" data-test="income-unverified-count">{{
            t("finance.unverifiedCount", { count: result.summary.unverified })
          }}</el-tag>
        </el-tooltip>
      </div>
      <el-alert v-if="failed" :title="t('finance.loadFailed')" type="error" :closable="false"
        ><el-button data-test="income-retry" @click="load">{{
          t("finance.retry")
        }}</el-button></el-alert
      >
      <DataTable
        v-model:page="page"
        v-model:size="size"
        table-id="commission-income"
        fill-available-height
        :rows="rows"
        :columns="columns"
        :total="Number(result?.total ?? 0)"
        :loading="loading"
        :filter-count="chips.length"
        @refresh="load"
        @clear-filters="reset"
      >
        <template #cell-creator="{ row }"
          ><div class="identity">
            <strong>{{ row.name }}</strong
            ><CopyText :value="row.code" /><CopyText :value="row.email" /></div
        ></template>
        <template #cell-sales="{ row }">{{ row.orderCount }} / {{ row.quantity }}</template>
        <template
          v-for="key in ['estimated', 'frozen', 'available']"
          :key="key"
          #[`cell-${key}`]="{ row }"
          ><el-tooltip :content="t(`finance.${key}Help`)"
            ><span>{{ formatPrice(row[key], "CNY") }}</span></el-tooltip
          ></template
        >
        <template #cell-paid="{ row }"
          ><div class="paid-cell">
            <strong>{{ formatPrice(row.paid, "CNY") }}</strong
            ><small
              >{{ t("finance.commissionPaid") }} {{ formatPrice(row.commissionPaid, "CNY") }}</small
            ><small>{{ t("finance.prepayPaid") }} {{ formatPrice(row.prepayPaid, "CNY") }}</small>
          </div></template
        >
        <template #cell-lastPaidAt="{ row }">{{ formatInstant(row.lastPaidAt) }}</template>
        <template #actions="{ row }"><RowActions :actions="actions(row)" /></template>
      </DataTable>
      <IncomeDrawer v-model="drawer" :id="selected" :query="applied" />
    </div>
  </div>
</template>
<style scoped>
.income-notes {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.income-notes :deep(.el-button) {
  padding: 0;
  height: auto;
}
.finance-content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  min-width: 0;
  gap: var(--tcrm-space-4);
}
.finance-page {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
.income-summary {
  display: grid;
  grid-template-columns: 2fr repeat(3, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
}
article {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-1);
  padding: var(--tcrm-space-3) var(--tcrm-space-4);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
article strong {
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-variant-numeric: tabular-nums;
}
.help,
small {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
  margin: 0;
}
.summary-breakdown {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-1) var(--tcrm-space-3);
}
.identity,
.paid-cell {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-1);
  min-width: 0;
}
.identity strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 767px) {
  .income-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
