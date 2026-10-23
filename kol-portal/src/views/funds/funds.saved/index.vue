<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  getFundRecords,
  getFundSummary,
  getWithdrawalAccounts,
  getWithdrawalConditions,
  getWithdrawals,
  submitWithdrawal,
  type FundRecord,
  type FundSummary,
  type WithdrawalAccountOption,
  type WithdrawalRecord
} from "@/api/creator";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatMoney } from "@/utils/orders";

defineOptions({ name: "FundsPage" });
const { t, locale } = useI18n();
const summary = ref<FundSummary | null>(null);
const records = ref<FundRecord[]>([]);
const withdrawals = ref<WithdrawalRecord[]>([]);
const accounts = ref<WithdrawalAccountOption[]>([]);
const conditions = ref<{ currency: string; minimum: string } | null>(null);
const loading = ref(false);
const failure = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const applyVisible = ref(false);
const submitting = ref(false);
const applyError = ref("");
const form = reactive({ amount: "", payoutAccountId: "", note: "" });
const money = (value: string) => formatMoney(value, locale.value);
const columns = computed<DataColumn[]>(() => [
  { key: "appliedAt", label: t("funds.appliedAt"), minWidth: 170 },
  { key: "applicationNo", label: t("funds.applicationNo"), minWidth: 160 },
  { key: "type", label: t("funds.type"), minWidth: 120 },
  { key: "amount", label: t("funds.amount"), minWidth: 130, amount: true },
  { key: "recipient", label: t("funds.recipient"), minWidth: 170 },
  { key: "receivedAt", label: t("funds.receivedAt"), minWidth: 170 }
]);
const chartParts = computed(() => {
  const data = summary.value;
  if (!data) return [];
  return [
    { key: "withdrawn", value: Number(data.withdrawnCny), color: "var(--tcrm-color-success)" },
    { key: "withdrawing", value: Number(data.withdrawingCny), color: "#3478c8" },
    { key: "frozen", value: Number(data.frozenCny), color: "var(--tcrm-color-warning)" }
  ];
});
const sumParts = computed(() => chartParts.value.reduce((sum, part) => sum + part.value, 0));
const chartBackground = computed(() => {
  let start = 0;
  const stops = chartParts.value.map(part => {
    const from = start;
    start += sumParts.value > 0 ? (part.value / sumParts.value) * 100 : 0;
    return `${part.color} ${from}% ${start}%`;
  });
  return `linear-gradient(90deg, ${stops.join(", ")})`;
});

async function load() {
  loading.value = true;
  failure.value = false;
  try {
    const [summaryResult, history, withdrawalsPage] = await Promise.all([
      getFundSummary(),
      getFundRecords({ page: page.value, size: size.value }),
      getWithdrawals({ page: 1, size: 50 })
    ]);
    summary.value = summaryResult.fundSummary;
    records.value = history.items;
    total.value = Number(history.total);
    withdrawals.value = withdrawalsPage.items;
  } catch {
    failure.value = true;
  } finally {
    loading.value = false;
  }
}
async function openApply() {
  applyError.value = "";
  try {
    const [limits, options] = await Promise.all([
      getWithdrawalConditions(),
      getWithdrawalAccounts()
    ]);
    conditions.value = limits;
    accounts.value = options;
    form.payoutAccountId = options.find(option => option.id)?.id ?? "";
    applyVisible.value = true;
  } catch {
    applyError.value = t("funds.loadFailed");
  }
}
async function submit() {
  applyError.value = "";
  const amount = Number(form.amount);
  if (!Number.isFinite(amount) || amount <= 0 || amount < Number(conditions.value?.minimum ?? 0)) {
    applyError.value = t("funds.amountInvalid", { minimum: conditions.value?.minimum ?? "0" });
    return;
  }
  if (!form.payoutAccountId) {
    applyError.value = t("funds.accountRequired");
    return;
  }
  submitting.value = true;
  try {
    await submitWithdrawal({
      amount: amount.toFixed(2),
      payoutAccountId: form.payoutAccountId,
      note: form.note,
      idempotencyKey: crypto.randomUUID()
    });
    applyVisible.value = false;
    form.amount = "";
    form.note = "";
    await load();
  } catch {
    applyError.value = t("funds.submitFailed");
  } finally {
    submitting.value = false;
  }
}
function dateLabel(value: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString(locale.value);
}
function pageChanged(value: number) {
  page.value = value;
  void load();
}
function recordType(value: FundRecord["type"]) {
  return value === "PREPAY" ? t("funds.prepay") : t("funds.withdrawal");
}
onMounted(load);
</script>

<template>
  <section class="funds-page">
    <header class="page-heading">
      <div>
        <h1>{{ t("funds.title") }}</h1>
        <p>{{ t("funds.summaryHint") }}</p>
      </div>
      <el-button type="primary" @click="openApply">{{ t("funds.apply") }}</el-button>
    </header>
    <el-alert
      v-if="failure"
      type="error"
      :closable="false"
      :title="t('funds.loadFailed')"
      show-icon
    />
    <el-skeleton v-if="loading && !summary" :rows="4" animated />
    <template v-else-if="summary">
      <el-card shadow="never" class="summary-card">
        <div class="summary-heading">
          <div>
            <span>{{ t("funds.totalCommission") }}</span
            ><strong>{{ money(summary.totalCommissionCny) }}</strong>
          </div>
          <span class="formula">{{ t("funds.formula") }}</span>
        </div>
        <div class="distribution" role="img" :aria-label="t('funds.distribution')">
          <span :style="{ background: chartBackground }" />
        </div>
        <div class="legend">
          <div v-for="part in chartParts" :key="part.key">
            <i :style="{ background: part.color }" /><span>{{ t(`funds.${part.key}`) }}</span
            ><strong>{{ money(String(part.value)) }}</strong>
          </div>
        </div>
        <div class="summary-extra">
          <div>
            <span>{{ t("funds.prepayReceived") }}</span
            ><strong>{{ money(summary.receivedPrepayCny) }}</strong>
          </div>
          <div>
            <span>{{ t("funds.available") }}</span
            ><strong>{{ money(summary.availableCny) }}</strong>
          </div>
          <div>
            <span>{{ t("funds.prepayDeduction") }}</span
            ><strong :class="{ negative: Number(summary.prepayDeductionCny) > 0 }">{{
              Number(summary.prepayDeductionCny) > 0
                ? `-${money(summary.prepayDeductionCny)}`
                : money("0")
            }}</strong>
          </div>
        </div>
      </el-card>
      <el-card shadow="never" class="records-card">
        <div class="section-heading">
          <h2>{{ t("funds.history") }}</h2>
          <el-button link type="primary" @click="$router.push('/payout-accounts')">{{
            t("funds.manageAccounts")
          }}</el-button>
        </div>
        <DataTable
          table-id="fund-records"
          :rows="records"
          :columns="columns"
          :loading="loading"
          :total="total"
          :page="page"
          :size="size"
          @update:page="pageChanged"
          @refresh="load"
        >
          <template #cell-appliedAt="{ row }">{{ dateLabel(row.appliedAt) }}</template>
          <template #cell-applicationNo="{ row }"
            ><span class="application-no">{{ row.applicationNo || "—" }}</span></template
          >
          <template #cell-type="{ row }"
            ><el-tag :type="row.type === 'PREPAY' ? 'warning' : 'primary'">{{
              recordType(row.type)
            }}</el-tag></template
          >
          <template #cell-amount="{ row }">{{ money(row.amount) }} {{ row.currency }}</template>
          <template #cell-recipient="{ row }">{{ row.maskedRecipient || "—" }}</template>
          <template #cell-receivedAt="{ row }">{{ dateLabel(row.receivedAt) }}</template>
          <template #empty
            ><div class="empty-state">{{ t("funds.empty") }}</div></template
          >
        </DataTable>
      </el-card>
      <el-card shadow="never" class="withdrawals-card">
        <div class="section-heading">
          <h2>{{ t("funds.withdrawalHistory") }}</h2>
          <RouterLink to="/payout-accounts">{{ t("funds.manageAccounts") }}</RouterLink>
        </div>
        <div v-if="withdrawals.length" class="withdrawal-list">
          <div v-for="item in withdrawals" :key="item.id" class="withdrawal-row">
            <span>{{ item.applicationNo }}</span
            ><strong>{{ money(item.amount) }} {{ item.currency }}</strong
            ><el-tag>{{ t(`funds.status.${item.status}`, item.status) }}</el-tag
            ><span>{{ dateLabel(item.createdAt) }}</span>
          </div>
        </div>
        <el-empty v-else :description="t('funds.noWithdrawals')" />
      </el-card>
    </template>
    <el-dialog v-model="applyVisible" :title="t('funds.applyTitle')" width="520px" destroy-on-close>
      <el-alert v-if="applyError" type="error" :closable="false" :title="applyError" />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item :label="t('funds.amount')"
          ><el-input v-model="form.amount" inputmode="decimal"
            ><template #append>{{ conditions?.currency ?? "CNY" }}</template></el-input
          ><small>{{
            t("funds.minimum", { amount: conditions?.minimum ?? "—" })
          }}</small></el-form-item
        >
        <el-form-item :label="t('funds.recipient')"
          ><el-select v-model="form.payoutAccountId" :placeholder="t('funds.chooseAccount')"
            ><el-option
              v-for="account in accounts"
              :key="account.id"
              :value="account.id"
              :label="`${account.accountType} · ${account.account}`" /></el-select
          ><RouterLink to="/payout-accounts" class="account-link">{{
            t("funds.manageAccounts")
          }}</RouterLink></el-form-item
        >
        <el-form-item :label="t('funds.note')"
          ><el-input v-model="form.note" type="textarea" maxlength="500" show-word-limit
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="applyVisible = false">{{ t("common.cancel") }}</el-button
        ><el-button type="primary" :loading="submitting" @click="submit">{{
          t("funds.submit")
        }}</el-button></template
      >
    </el-dialog>
  </section>
</template>

<style scoped>
.funds-page {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
.page-heading,
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
}
h1 {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-weight: 600;
}
.page-heading p {
  margin: var(--tcrm-space-1) 0 0;
  color: var(--tcrm-color-text-secondary);
}
.summary-card :deep(.el-card__body) {
  display: grid;
  gap: var(--tcrm-space-4);
}
.summary-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: var(--tcrm-space-4);
}
.summary-heading > div,
.summary-extra > div {
  display: grid;
  gap: var(--tcrm-space-2);
}
.summary-heading span,
.summary-extra span,
.formula {
  color: var(--tcrm-color-text-secondary);
}
.summary-heading strong {
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
}
.formula {
  text-align: right;
}
.distribution {
  height: 20px;
  padding: 2px;
  border: 1px solid var(--tcrm-color-border);
  border-radius: 999px;
  overflow: hidden;
}
.distribution span {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: inherit;
}
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-4);
}
.legend div {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.legend i {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.legend strong,
.summary-extra strong {
  font-variant-numeric: tabular-nums;
}
.summary-extra {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
  padding-top: var(--tcrm-space-4);
  border-top: 1px solid var(--tcrm-color-border);
}
.negative {
  color: var(--tcrm-color-danger);
}
.section-heading h2 {
  margin: 0;
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
}
.application-no {
  overflow-wrap: anywhere;
}
.empty-state {
  padding: var(--tcrm-space-6);
  text-align: center;
  color: var(--tcrm-color-text-secondary);
}
.withdrawal-list {
  display: grid;
}
.withdrawal-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(100px, auto) minmax(100px, auto) minmax(
      140px,
      auto
    );
  align-items: center;
  gap: var(--tcrm-space-3);
  padding: var(--tcrm-space-3) 0;
  border-bottom: 1px solid var(--tcrm-color-border);
}
.withdrawal-row:last-child {
  border-bottom: 0;
}
.account-link {
  margin-top: var(--tcrm-space-2);
}
.el-alert {
  margin-bottom: var(--tcrm-space-3);
}
small {
  color: var(--tcrm-color-text-secondary);
}
@media (max-width: 767px) {
  .summary-extra {
    grid-template-columns: 1fr;
  }
  .formula {
    max-width: 50%;
  }
  .withdrawal-row {
    grid-template-columns: 1fr auto;
  }
}
</style>
