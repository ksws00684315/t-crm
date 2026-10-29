<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { subscribeNarrow } from "@/utils/sidebar";
import FinancePreviewNotice from "@/components/FinancePreviewNotice.vue";
import FinanceUnavailable from "@/components/FinanceUnavailable.vue";
import { listSettlements } from "@/api/commission/finance";
import type { Settlement } from "@/api/commission/finance-types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import CopyText from "@/components/CopyText.vue";
import RowActions from "@/components/RowActions.vue";
import CommissionTable from "@/components/CommissionTable.vue";
import { formatRate } from "@/utils/rate";
import { formatInstant, formatPrice } from "@/utils/format";
import { financeApiReady, previewContext } from "@/utils/finance";
import { notifyError } from "@/utils/notify";
defineOptions({ name: "SettlementDetailsPage" });
const { t, locale } = useI18n();
const narrow = ref(false);
let unsubscribe: (() => void) | undefined;
onMounted(() => {
  unsubscribe = subscribeNarrow(value => (narrow.value = value));
});
onBeforeUnmount(() => unsubscribe?.());
const filters = reactive({ orderNo: "", channel: "", shopId: "" });
const rows = ref<Settlement[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const loading = ref(false);
const failed = ref(false);
const detail = ref<Settlement>();
const open = ref(false);
const columns = computed<DataColumn[]>(() =>
  [
    "orderNo",
    "channel",
    "shop",
    "creator",
    "payment",
    "refund",
    "weidianWithdrawable",
    "commission",
    "rate",
    "accountStatus",
    "businessAt",
    "actions"
  ].map(key => ({
    key,
    prop: key,
    label: t(`finance.${key}`),
    minWidth:
      key === "businessAt" ? 200 : key === "orderNo" ? 210 : locale.value === "en-US" ? 180 : 140,
    amount: ["payment", "refund", "weidianWithdrawable", "commission"].includes(key),
    tooltip: true
  }))
);
const lineColumns = computed<DataColumn[]>(() =>
  ["product", "amountType", "direction", "amount", "rate", "subject", "creator"].map(key => ({
    key,
    label: t(`finance.${key}`),
    minWidth: 180,
    amount: key === "amount"
  }))
);
const chips = computed(() =>
  Object.entries(filters)
    .filter(([, value]) => value)
    .map(([key, value]) => ({
      key,
      label: t(`finance.${key}`),
      text: key === "channel" ? t("finance.weidian") : value
    }))
);
async function load() {
  if (!financeApiReady) return;
  loading.value = true;
  failed.value = false;
  try {
    const result = await listSettlements({ ...filters, page: page.value, size: size.value });
    rows.value = result.items;
    total.value = Number(result.total);
  } catch (error) {
    rows.value = [];
    total.value = 0;
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
  Object.assign(filters, { orderNo: "", channel: "", shopId: "" });
  search();
}
function remove(key: string) {
  filters[key as keyof typeof filters] = "";
  search();
}
function actions(row: Settlement) {
  if (!row.id) return [];
  return [
    {
      key: "view",
      testId: "settlement-view",
      label: t("finance.view"),
      onClick: () => {
        detail.value = row;
        open.value = true;
      }
    }
  ];
}
watch([page, size], load);
watch(
  () => [previewContext.role, previewContext.scenario],
  () => {
    open.value = false;
    search();
  }
);
onMounted(load);
</script>
<template>
  <FinanceUnavailable v-if="!financeApiReady" />
  <div v-else class="settlement-page" data-test="settlement-page">
    <FinancePreviewNotice />
    <div class="finance-content">
      <ListSearch :chips="chips" @search="search" @reset="reset" @remove="remove"
        ><template #primary>
          <el-form-item :label="t('finance.orderNo')"
            ><el-input v-model="filters.orderNo" data-test="settlement-order" clearable
          /></el-form-item>
          <el-form-item :label="t('finance.channel')"
            ><el-select v-model="filters.channel" clearable
              ><el-option :label="t('finance.weidian')" value="WEIDIAN" /></el-select
          ></el-form-item>
          <el-form-item :label="t('finance.shopId')"
            ><el-input v-model="filters.shopId" clearable data-test="settlement-shop-id"
          /></el-form-item> </template
      ></ListSearch>
      <p>{{ t("finance.weidianWithdrawableNote") }}</p>
      <el-alert v-if="failed" :title="t('finance.loadFailed')" type="error" :closable="false" />
      <DataTable
        v-model:page="page"
        v-model:size="size"
        table-id="commission-settlements"
        fill-available-height
        :rows="rows"
        :columns="columns"
        :total="total"
        :loading="loading"
        :filter-count="chips.length"
        @refresh="load"
        @clear-filters="reset"
      >
        <template #cell-orderNo="{ row }"><CopyText :value="row.orderNo" /></template
        ><template #cell-channel>{{ t("finance.weidian") }}</template>
        <template #cell-creator="{ row }">{{
          row.kolNames.length > 1 ? t("finance.multiple") : row.kolNames[0]
        }}</template
        ><template #cell-rate="{ row }">{{
          row.rates.length > 1
            ? t("finance.multiple")
            : row.rates[0]
              ? formatRate(row.rates[0])
              : t("finance.unverified")
        }}</template>
        <template
          v-for="key in ['payment', 'refund', 'commission']"
          :key="key"
          #[`cell-${key}`]="{ row }"
          >{{
            row[key] === null
              ? t(row.scopeLimited ? "finance.scopeLimited" : "finance.unverified")
              : formatPrice(row[key], "CNY")
          }}</template
        ><template #cell-weidianWithdrawable="{ row }">{{
          row.weidianWithdrawable === null ? "-" : formatPrice(row.weidianWithdrawable, "CNY")
        }}</template>
        <template #cell-accountStatus="{ row }"
          ><el-tag :type="row.status === 'POSTED' ? 'success' : 'warning'">{{
            t(`finance.${row.status ?? "UNVERIFIED"}`)
          }}</el-tag></template
        ><template #cell-businessAt="{ row }">{{ formatInstant(row.businessAt) }}</template
        ><template #actions="{ row }"><RowActions :actions="actions(row)" /></template>
      </DataTable>
      <el-drawer
        v-model="open"
        :title="t('finance.settlementDetail')"
        size="min(1180px, 96vw)"
        destroy-on-close
      >
        <div v-if="detail" class="detail" data-test="settlement-detail">
          <el-alert
            v-if="detail.scopeLimited"
            :title="t('finance.scopeLimited')"
            type="info"
            :closable="false"
          />
          <h2><CopyText :value="detail.orderNo" /></h2>
          <el-descriptions :column="narrow ? 1 : 2" border
            ><el-descriptions-item
              v-for="key in ['payment', 'refund', 'commission'] as const"
              :key="key"
              :label="t(`finance.${key}`)"
              >{{
                detail[key] === null
                  ? t(detail.scopeLimited ? "finance.scopeLimited" : "finance.unverified")
                  : formatPrice(detail[key], "CNY")
              }}</el-descriptions-item
            ><el-descriptions-item :label="t('finance.weidianWithdrawable')">{{
              detail.weidianWithdrawable === null
                ? "-"
                : formatPrice(detail.weidianWithdrawable, "CNY")
            }}</el-descriptions-item></el-descriptions
          >
          <DataTable
            table-id="settlement-item-movements"
            :rows="detail.lines"
            :columns="lineColumns"
            :total="detail.lines.length"
            :paged="false"
            plain
          >
            <template #cell-product="{ row }">{{ row.product }}</template
            ><template #cell-amountType>{{ t("finance.commissionType") }}</template
            ><template #cell-direction="{ row }">{{
              t(`finance.${row.direction ?? "CREDIT"}`)
            }}</template
            ><template #cell-amount="{ row }">{{
              row.commission === null ? t("finance.unverified") : formatPrice(row.commission, "CNY")
            }}</template
            ><template #cell-rate="{ row }">{{
              row.rate == null ? t("finance.unverified") : formatRate(row.rate)
            }}</template
            ><template #cell-subject>{{ t("finance.creator") }}</template
            ><template #cell-creator="{ row }">{{ row.kol }}</template>
          </DataTable>
          <h2>{{ t("finance.commissionLines") }}</h2>
          <CommissionTable :rows="detail.lines" table-id="settlement-commission-evidence" />
        </div>
      </el-drawer>
    </div>
  </div>
</template>
<style scoped>
.finance-content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  min-width: 0;
  gap: var(--tcrm-space-4);
}
.settlement-page,
.detail {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
p {
  margin: 0;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
h2 {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  margin: 0;
}
</style>
