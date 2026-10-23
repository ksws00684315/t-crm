<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import FinancePreviewNotice from "@/components/FinancePreviewNotice.vue";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import { listWithdrawals, reviewWithdrawal, exportWithdrawals } from "@/api/commission/finance";
import type { Withdrawal, WithdrawalQuery } from "@/api/commission/finance-types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import RowActions from "@/components/RowActions.vue";
import CopyText from "@/components/CopyText.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant, formatPrice } from "@/utils/format";
import { financeAbilities, previewContext } from "@/utils/finance";
import { notifyError } from "@/utils/notify";
import { serverFieldErrors } from "@/utils/validation";
import { saveBlob } from "@/utils/download";
import WithdrawalDrawer from "./WithdrawalDrawer.vue";
import PaymentDialog from "./PaymentDialog.vue";
defineOptions({ name: "WithdrawalTasksPage" });
const { t, locale } = useI18n();
const user = useUserStore();
const abilities = computed(() => financeAbilities(user.user));
const filters = reactive({ applicationNo: "", kol: "", type: "COMMISSION", status: "" });
const rows = ref<Withdrawal[]>([]);
const appliedFilters = ref<WithdrawalQuery>({ ...filters });
const total = ref(0);
const page = ref(1);
const size = ref(20);
const loading = ref(false);
const failed = ref(false);
const selected = ref<Withdrawal | null>(null);
const open = ref(false);
const paymentOpen = ref(false);
const reviewOpen = ref(false);
const decision = ref<"APPROVE" | "REJECT">("APPROVE");
const form = reactive({ note: "" });
const formRef = ref<FormInstance>();
const busy = ref(false);
const errors = ref<Record<string, string>>({});
const rules = computed<FormRules>(() => ({
  note: [
    {
      required: decision.value === "REJECT",
      whitespace: true,
      message: t("finance.required"),
      trigger: "blur"
    }
  ]
}));
const columns = computed<DataColumn[]>(() =>
  [
    "applicationNo",
    "kolIdentity",
    "applicationType",
    "amount",
    "currency",
    "status",
    "createdAt",
    "actions"
  ].map(key => ({
    key,
    prop: key,
    label: t(`finance.${key}`),
    minWidth:
      key === "actions"
        ? locale.value === "en-US"
          ? 340
          : 240
        : key === "applicationNo" || key === "kolIdentity"
          ? 230
          : key === "createdAt"
            ? 200
            : 160,
    amount: key === "amount"
  }))
);
const chips = computed(() =>
  Object.entries(filters)
    .filter(([key, value]) => value && key !== "type")
    .map(([key, value]) => ({
      key,
      label: t(`finance.${key === "kol" ? "creator" : key}`),
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
  Object.assign(filters, { applicationNo: "", kol: "", type: "COMMISSION", status: "" });
  search();
}
function remove(key: string) {
  filters[key as keyof typeof filters] = "";
  search();
}
function canReview(row: Withdrawal) {
  return abilities.value.review && row.status === "PENDING_REVIEW";
}
function canReject(row: Withdrawal) {
  return canReview(row) || (abilities.value.pay && row.status === "PENDING_PAYMENT");
}
function canPay(row: Withdrawal) {
  return abilities.value.pay && row.status === "PENDING_PAYMENT";
}
function startReview(row: Withdrawal, next: "APPROVE" | "REJECT") {
  selected.value = row;
  decision.value = next;
  form.note = "";
  errors.value = {};
  reviewOpen.value = true;
  formRef.value?.clearValidate();
}
function actions(row: Withdrawal) {
  if (!row.id) return [];
  return [
    {
      key: "view",
      testId: "withdrawal-view",
      label: t("finance.view"),
      onClick: () => {
        selected.value = row;
        open.value = true;
      }
    },
    {
      key: "approve",
      testId: "withdrawal-approve",
      label: t("finance.approve"),
      hidden: !canReview(row),
      onClick: () => startReview(row, "APPROVE")
    },
    {
      key: "reject",
      testId: "withdrawal-reject",
      label: t("finance.reject"),
      hidden: !canReject(row),
      onClick: () => startReview(row, "REJECT")
    },
    {
      key: "pay",
      testId: "withdrawal-pay",
      label: t("finance.pay"),
      hidden: !canPay(row),
      disabled: row.paused,
      tooltip: row.paused ? t("finance.refundPause") : undefined,
      onClick: () => {
        selected.value = row;
        paymentOpen.value = true;
      }
    }
  ];
}
async function submitReview() {
  if (busy.value || !selected.value || !(await formRef.value?.validate().catch(() => false)))
    return;
  busy.value = true;
  try {
    await ElMessageBox.confirm(
      t("finance.reviewConfirm"),
      t(decision.value === "APPROVE" ? "finance.approve" : "finance.reject"),
      { confirmButtonText: t("finance.confirm"), cancelButtonText: t("finance.cancel") }
    );
    await reviewWithdrawal(selected.value.id, decision.value, form.note);
    ElMessage.success(t("finance.saved"));
    reviewOpen.value = false;
    await load();
  } catch (error) {
    if (error !== "cancel" && error !== "close") {
      errors.value = serverFieldErrors(error, ["note"]);
      notifyError(error);
    }
  } finally {
    busy.value = false;
  }
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

watch([page, size], load);
watch(
  () => [previewContext.role, previewContext.scenario],
  () => {
    open.value = false;
    paymentOpen.value = false;
    reviewOpen.value = false;
    search();
  }
);
onMounted(load);
</script>
<template>
  <div class="tasks-page" data-test="withdrawal-page">
    <FinancePreviewNotice />
    <div class="finance-content">
      <ListSearch :chips="chips" @search="search" @reset="reset" @remove="remove"
        ><template #primary>
          <el-form-item :label="t('finance.applicationNo')"
            ><el-input
              v-model="filters.applicationNo"
              clearable
              data-test="withdrawal-number" /></el-form-item
          ><el-form-item :label="t('finance.creator')"
            ><el-input v-model="filters.kol" clearable
          /></el-form-item>
          <el-form-item :label="t('finance.applicationType')"
            ><el-select v-model="filters.type"
              ><el-option
                :label="t('finance.COMMISSION')"
                value="COMMISSION" /></el-select></el-form-item
          ><el-form-item :label="t('finance.status')"
            ><el-select v-model="filters.status" clearable data-test="withdrawal-status"
              ><el-option
                v-for="status in [
                  'PENDING_REVIEW',
                  'PENDING_PAYMENT',
                  'PAID',
                  'REJECTED',
                  'WITHDRAWN'
                ]"
                :key="status"
                :label="t(`finance.${status}`)"
                :value="status" /></el-select
          ></el-form-item> </template></ListSearch
      ><el-alert v-if="failed" :title="t('finance.loadFailed')" type="error" :closable="false" />
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
        <template #cell-applicationNo="{ row }"><CopyText :value="row.applicationNo" /></template
        ><template #cell-kolIdentity="{ row }"
          ><div>{{ row.kolName }}</div>
          <CopyText :value="row.kolCode" /></template
        ><template #cell-applicationType>{{ t("finance.COMMISSION") }}</template
        ><template #cell-amount="{ row }">{{ formatPrice(row.amount, "CNY") }}</template
        ><template #cell-status="{ row }"
          ><div class="status">
            <el-tag :type="row.status === 'PAID' ? 'success' : 'info'">{{
              t(`finance.${row.status ?? "UNVERIFIED"}`)
            }}</el-tag
            ><el-tooltip v-if="row.paused" :content="t('finance.refundPause')"
              ><el-tag type="danger">{{ t("finance.paused") }}</el-tag></el-tooltip
            >
          </div></template
        ><template #cell-createdAt="{ row }">{{ formatInstant(row.createdAt) }}</template
        ><template #actions="{ row }"><RowActions :actions="actions(row)" /></template>
      </DataTable>
      <WithdrawalDrawer v-model="open" :id="selected?.id ?? ''" /><PaymentDialog
        v-model="paymentOpen"
        :withdrawal="selected"
        @saved="load"
      />
      <el-dialog
        v-model="reviewOpen"
        :title="t(decision === 'APPROVE' ? 'finance.approve' : 'finance.reject')"
        width="min(560px, 92vw)"
        :close-on-click-modal="false"
        ><el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="submitReview"
          ><el-form-item
            :label="t(decision === 'REJECT' ? 'finance.rejectReason' : 'finance.reviewNote')"
            prop="note"
            :error="errors.note"
            ><el-input
              v-model="form.note"
              type="textarea"
              maxlength="500"
              show-word-limit
              data-test="review-note" /></el-form-item></el-form
        ><template #footer
          ><el-button :disabled="busy" @click="reviewOpen = false">{{
            t("finance.cancel")
          }}</el-button
          ><el-button
            type="primary"
            :loading="busy"
            data-test="review-submit"
            @click="submitReview"
            >{{ t("finance.confirm") }}</el-button
          ></template
        ></el-dialog
      >
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
.status {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-2);
  align-items: start;
}
</style>
