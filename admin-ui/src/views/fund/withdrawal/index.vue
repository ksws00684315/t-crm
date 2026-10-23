<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import FinancePreviewNotice from "@/components/FinancePreviewNotice.vue";
import { exportWithdrawals, listWithdrawals } from "@/api/commission/finance";
import type { Withdrawal, WithdrawalQuery } from "@/api/commission/finance-types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import RowActions from "@/components/RowActions.vue";
import CopyText from "@/components/CopyText.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { financeAbilities, previewContext } from "@/utils/finance";
import { notifyError } from "@/utils/notify";
import { saveBlob } from "@/utils/download";
import WithdrawalDrawer from "./WithdrawalDrawer.vue";
import ReviewDialog from "./ReviewDialog.vue";
import PaymentDialog from "./PaymentDialog.vue";

defineOptions({ name: "WithdrawalTasksPage" });
const { t, locale } = useI18n();
const user = useUserStore();
const abilities = computed(() => financeAbilities(user.user));
const filters = reactive({ applicationNo: "", kolId: "", bdName: "", status: "" });
const rows = ref<Withdrawal[]>([]);
const appliedFilters = ref<WithdrawalQuery>({ ...filters });
const total = ref(0);
const page = ref(1);
const size = ref(20);
const loading = ref(false);
const failed = ref(false);
const selected = ref<Withdrawal | null>(null);
const viewOpen = ref(false);
const reviewOpen = ref(false);
const paymentOpen = ref(false);

const columns = computed<DataColumn[]>(() =>
  ["applicationNo", "kolIdentity", "amount", "status", "createdAt", "actions"].map(key => ({
    key,
    prop: key,
    label: t(`finance.${key}`),
    minWidth:
      key === "actions"
        ? locale.value === "en-US"
          ? 190
          : 170
        : key === "applicationNo"
          ? 175
          : key === "kolIdentity"
            ? 165
            : key === "createdAt"
              ? 165
              : key === "amount"
                ? 145
                : 145,
    amount: key === "amount"
  }))
);
const chips = computed(() =>
  Object.entries(filters)
    .filter(([, value]) => value)
    .map(([key, value]) => ({
      key,
      label: t(`finance.${key}`),
      text: key === "status" ? t(`finance.${value}`) : value
    }))
);

async function load() {
  loading.value = true;
  failed.value = false;
  try {
    const result = await listWithdrawals({ ...filters, page: page.value, size: size.value });
    appliedFilters.value = { ...filters };
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
  Object.assign(filters, { applicationNo: "", kolId: "", bdName: "", status: "" });
  search();
}
function remove(key: string) {
  if (key in filters) filters[key as keyof typeof filters] = "";
  search();
}
function canReview(row: Withdrawal) {
  return (
    abilities.value.review && ["PENDING_REVIEW", "PENDING_FINANCE_REVIEW"].includes(row.status)
  );
}
function canPay(row: Withdrawal) {
  return abilities.value.pay && row.status === "PENDING_PAYMENT";
}
function startReview(row: Withdrawal) {
  selected.value = row;
  reviewOpen.value = true;
}
function actions(row: Withdrawal) {
  if (!row.id) return [];
  const actionLabel =
    row.status === "PENDING_REVIEW"
      ? t("finance.operationsReview")
      : row.status === "PENDING_FINANCE_REVIEW"
        ? t("finance.financeReview")
        : t("finance.financePay");
  const action =
    canReview(row) || canPay(row)
      ? {
          key: "progress",
          testId: "withdrawal-progress-action",
          label: actionLabel,
          disabled:
            (row.status === "PENDING_PAYMENT" && row.paused) ||
            (row.status === "PENDING_FINANCE_REVIEW" &&
              row.history.some(
                event =>
                  event.action === "PENDING_FINANCE_REVIEW" && event.actorId === user.user?.id
              )),
          tooltip:
            row.status === "PENDING_PAYMENT" && row.paused
              ? t("finance.refundPause")
              : row.status === "PENDING_FINANCE_REVIEW" &&
                  row.history.some(
                    event =>
                      event.action === "PENDING_FINANCE_REVIEW" && event.actorId === user.user?.id
                  )
                ? t("finance.reviewerMustDiffer")
                : undefined,
          onClick: () => {
            selected.value = row;
            if (row.status === "PENDING_PAYMENT") paymentOpen.value = true;
            else startReview(row);
          }
        }
      : null;
  return [
    {
      key: "view",
      testId: "withdrawal-view",
      label: t("finance.viewDetails"),
      onClick: () => {
        selected.value = row;
        viewOpen.value = true;
      }
    },
    ...(action ? [action] : [])
  ];
}
async function exportRows() {
  if (!abilities.value.export) return;
  try {
    const file = await exportWithdrawals({ ...appliedFilters.value });
    saveBlob(file.blob, file.fileName ?? "t1023_withdrawals.csv");
  } catch (error) {
    notifyError(error);
  }
}
function reviewSaved() {
  reviewOpen.value = false;
  void load();
}
function amountCurrency(amount: string, currency: string) {
  return `${amount} ${currency}`;
}
watch([page, size], load);
watch(
  () => [previewContext.role, previewContext.scenario],
  () => search()
);
onMounted(load);
</script>

<template>
  <div class="tasks-page" data-test="withdrawal-page">
    <FinancePreviewNotice />
    <div class="finance-content">
      <ListSearch :chips="chips" @search="search" @reset="reset" @remove="remove">
        <template #primary>
          <el-form-item :label="t('finance.applicationNo')">
            <el-input v-model="filters.applicationNo" clearable data-test="withdrawal-number" />
          </el-form-item>
          <el-form-item :label="t('finance.kolId')">
            <el-input v-model="filters.kolId" clearable data-test="withdrawal-kol-id" />
          </el-form-item>
          <el-form-item :label="t('finance.bdName')">
            <el-input v-model="filters.bdName" clearable data-test="withdrawal-bd-name" />
          </el-form-item>
          <el-form-item :label="t('finance.status')">
            <el-select v-model="filters.status" clearable data-test="withdrawal-status">
              <el-option
                v-for="status in [
                  'PENDING_REVIEW',
                  'PENDING_FINANCE_REVIEW',
                  'PENDING_PAYMENT',
                  'PAID',
                  'REJECTED',
                  'WITHDRAWN'
                ]"
                :key="status"
                :label="t(`finance.${status}`)"
                :value="status"
              />
            </el-select>
          </el-form-item>
        </template>
      </ListSearch>
      <el-alert v-if="failed" :title="t('finance.loadFailed')" type="error" :closable="false" />
      <DataTable
        v-model:page="page"
        v-model:size="size"
        table-id="commission-withdrawals"
        :rows="rows"
        :columns="columns"
        :total="total"
        :loading="loading"
        :filter-count="chips.length"
        :exportable="abilities.export && !failed"
        @refresh="load"
        @clear-filters="reset"
        @export="exportRows"
      >
        <template #cell-applicationNo="{ row }"><CopyText :value="row.applicationNo" /></template>
        <template #cell-kolIdentity="{ row }">
          <div class="creator-name">{{ row.kolName }}</div>
          <small class="creator-id">{{ row.kolCode }}</small>
        </template>
        <template #cell-amount="{ row }">{{ amountCurrency(row.amount, row.currency) }}</template>
        <template #cell-status="{ row }">
          <div class="status">
            <el-tag :type="row.status === 'PAID' ? 'success' : 'info'">{{
              t(`finance.${row.status ?? "UNVERIFIED"}`)
            }}</el-tag>
            <el-tooltip v-if="row.paused" :content="t('finance.refundPause')"
              ><el-tag type="danger">{{ t("finance.paused") }}</el-tag></el-tooltip
            >
          </div>
        </template>
        <template #cell-createdAt="{ row }">{{ formatInstant(row.createdAt) }}</template>
        <template #actions="{ row }"><RowActions :actions="actions(row)" /></template>
      </DataTable>
      <WithdrawalDrawer v-model="viewOpen" :id="selected?.id ?? ''" />
      <ReviewDialog v-model="reviewOpen" :withdrawal="selected" @saved="reviewSaved" />
      <PaymentDialog v-model="paymentOpen" :withdrawal="selected" @saved="load" />
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
.tasks-page {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
.creator-name {
  color: var(--el-text-color-primary);
  line-height: 1.35;
}
.creator-id {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: 1.25;
}
.status {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-1);
  align-items: center;
}
@media (max-width: 767px) {
  .finance-content {
    overflow-y: auto;
  }
  .finance-content > * {
    flex-shrink: 0;
  }
  .finance-content :deep(.data-table) {
    flex: 0 0 auto;
    max-height: none;
  }
}
</style>
