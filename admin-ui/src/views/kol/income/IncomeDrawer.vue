<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { financePreview } from "@/utils/finance";
import { downloadWithdrawalVoucher, getIncome } from "@/api/commission/finance";
import type { IncomeDetail, IncomeQuery, Payment } from "@/api/commission/finance-types";
import FinanceMetrics from "./FinanceMetrics.vue";
import CommissionTable from "@/components/CommissionTable.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import CopyText from "@/components/CopyText.vue";
import RowActions from "@/components/RowActions.vue";
import { formatInstant, formatPrice } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { saveBlob } from "@/utils/download";
import WithdrawalDrawer from "@/views/fund/withdrawal/WithdrawalDrawer.vue";
const props = defineProps<{ modelValue: boolean; id: string; query: IncomeQuery }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();
const { t } = useI18n();
const detail = ref<IncomeDetail>();
const loading = ref(false);
const failed = ref(false);
const tab = ref("commission");
const voucher = ref(false);
const voucherPayment = ref<Payment>();
const applicationOpen = ref(false);
const applicationId = ref("");
async function downloadVoucher() {
  if (!voucherPayment.value?.voucher) return;
  try {
    const file = await downloadWithdrawalVoucher(voucherPayment.value.voucher);
    saveBlob(file.blob, file.fileName ?? "payment-voucher");
  } catch (error) {
    notifyError(error);
  }
}
async function load() {
  loading.value = true;
  failed.value = false;
  detail.value = undefined;
  try {
    detail.value = await getIncome(props.id, props.query);
  } catch (error) {
    failed.value = true;
    notifyError(error);
  } finally {
    loading.value = false;
  }
}
watch(
  () => [props.modelValue, props.id],
  () => {
    if (props.modelValue) {
      tab.value = "commission";
      void load();
    }
  }
);
const paymentColumns = computed<DataColumn[]>(() =>
  [
    "sourceNo",
    "paymentType",
    "amountCny",
    "actualAmount",
    "actualCurrency",
    "paidAt",
    "deduct",
    "deducted",
    "pendingDeduction",
    "actions"
  ].map(key => ({
    key,
    label: t(`finance.${key}`),
    minWidth: key === "paidAt" ? 200 : 160,
    amount: ["amountCny", "deducted", "pendingDeduction"].includes(key)
  }))
);
</script>
<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('finance.ledger')"
    size="min(1180px, 96vw)"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="income-detail" data-test="income-detail">
      <el-result v-if="failed" icon="error" :title="t('finance.loadFailed')"
        ><template #extra
          ><el-button data-test="income-retry" @click="load">{{
            t("finance.retry")
          }}</el-button></template
        ></el-result
      >
      <template v-if="detail">
        <h2>{{ detail.income.name }} <CopyText :value="detail.income.code" /></h2>
        <FinanceMetrics :metrics="detail.income" reconciliation />
        <el-alert
          v-if="detail.income.unverified"
          :title="t('finance.unverifiedNotice', { count: detail.income.unverified })"
          type="warning"
          :closable="false"
        />
        <p>{{ t("finance.balanceNote") }}</p>
        <p>{{ t("finance.paidNote") }}</p>
        <el-tabs v-model="tab">
          <el-tab-pane :label="t('finance.commissionLines')" name="commission"
            ><CommissionTable :rows="detail.lines" table-id="finance-income-ledger"
          /></el-tab-pane>
          <el-tab-pane :label="t('finance.payments')" name="payments">
            <p>{{ t("finance.prepayReadOnly") }}</p>
            <DataTable
              table-id="finance-income-payments"
              :rows="detail.payments"
              :columns="paymentColumns"
              :total="detail.payments.length"
              :paged="false"
              plain
            >
              <template #cell-sourceNo="{ row }"><CopyText :value="row.sourceNo" /></template>
              <template #cell-paymentType="{ row }">{{
                t(`finance.${row.type ?? "COMMISSION"}`)
              }}</template>
              <template #cell-amountCny="{ row }">{{ formatPrice(row.amountCny, "CNY") }}</template>
              <template #cell-actualAmount="{ row }">{{
                row.type === "COMMISSION" ? row.actualAmount : "—"
              }}</template
              ><template #cell-actualCurrency="{ row }">{{
                row.type === "COMMISSION" ? row.actualCurrency : "—"
              }}</template>
              <template #cell-paidAt="{ row }">{{ formatInstant(row.paidAt) }}</template>
              <template #cell-deduct="{ row }">{{
                row.type === "PREPAY" ? t(row.deduct ? "finance.yes" : "finance.no") : "—"
              }}</template>
              <template #cell-deducted="{ row }">{{
                row.type === "PREPAY" && row.deduct ? formatPrice(row.deducted, "CNY") : "—"
              }}</template>
              <template #cell-pendingDeduction="{ row }">{{
                row.type === "PREPAY" && row.deduct ? formatPrice(row.pending, "CNY") : "—"
              }}</template>
              <template #actions="{ row }"
                ><RowActions
                  :actions="[
                    {
                      key: 'application',
                      label: t('finance.view'),
                      hidden: row.type !== 'COMMISSION' || !row.withdrawalId,
                      onClick: () => {
                        applicationId = row.withdrawalId;
                        applicationOpen = true;
                      }
                    },
                    {
                      key: 'voucher',
                      label: t('finance.voucherPreview'),
                      hidden: row.type !== 'COMMISSION',
                      disabled: !row.voucher,
                      onClick: () => {
                        voucherPayment = row;
                        voucher = true;
                      }
                    }
                  ]"
              /></template>
            </DataTable>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
    <el-dialog
      v-model="voucher"
      :title="t('finance.voucher')"
      width="min(560px, 92vw)"
      append-to-body
      ><el-alert v-if="financePreview" :title="t('finance.voucherDemo')" :closable="false" />
      <p>{{ voucherPayment?.sourceNo }}</p>
      <p>{{ voucherPayment?.actualAmount }} {{ voucherPayment?.actualCurrency }}</p>
      <p>{{ voucherPayment?.amountCny }} CNY</p>
      <el-button data-test="income-voucher-download" @click="downloadVoucher">{{
        t("finance.voucherPreview")
      }}</el-button></el-dialog
    >
    <WithdrawalDrawer v-model="applicationOpen" :id="applicationId" />
  </el-drawer>
</template>
<style scoped>
.income-detail {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
h2 {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  margin: 0;
}
p {
  color: var(--tcrm-color-text-secondary);
  margin: 0;
}
</style>
