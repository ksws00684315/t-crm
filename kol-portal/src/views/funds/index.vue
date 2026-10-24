<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getFundRecords,
  getFundSummary,
  getWithdrawalAccounts,
  getWithdrawalConditions,
  getWithdrawals,
  submitWithdrawal,
  withdrawWithdrawal,
  type FundRecord,
  type FundSummary,
  type WithdrawalAccountOption,
  type WithdrawalConditions,
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
const conditions = ref<WithdrawalConditions | null>(null);
const loading = ref(false);
const failure = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const applyVisible = ref(false);
const submitting = ref(false);
const applyError = ref("");
const form = reactive({
  amount: "",
  previousId: "",
  payoutAccountId: "",
  note: "",
  type: "PAYPAL" as "PAYPAL" | "USDT",
  account: "",
  holder: "",
  currency: "USD",
  network: "TRC20"
});
const manualAccount = computed(() => form.payoutAccountId === "manual");
const money = (value: string) => formatMoney(value, locale.value);
const columns = computed<DataColumn[]>(() => [
  { key: "appliedAt", label: t("funds.appliedAt"), minWidth: 170 },
  { key: "applicationNo", label: t("funds.applicationNo"), minWidth: 160 },
  { key: "type", label: t("funds.type"), minWidth: 120 },
  { key: "amount", label: t("funds.amount"), minWidth: 130, amount: true },
  { key: "recipient", label: t("funds.recipient"), minWidth: 170 },
  { key: "receivedAt", label: t("funds.receivedAt"), minWidth: 170 }
]);
const commissionParts = computed(() =>
  summary.value
    ? [
        { key: "withdrawn", amount: summary.value.withdrawnCny },
        { key: "currentAvailable", amount: summary.value.availableCny },
        { key: "frozen", amount: summary.value.frozenCny },
        { key: "withdrawing", amount: summary.value.withdrawingCny }
      ]
    : []
);
function summaryMoney(value: string) {
  return `${Number(value).toLocaleString(locale.value, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })} CNY`;
}
function cents(value: string) {
  const [whole, fraction = ""] = value.split(".");
  const unsigned = BigInt(whole.replace("-", "")) * 100n + BigInt(fraction.padEnd(2, "0"));
  return value.startsWith("-") ? -unsigned : unsigned;
}
// Only draw an equality when the displayed states reconcile; deductions remain separate below.
const commissionReconciles = computed(
  () =>
    summary.value &&
    cents(summary.value.estimatedCommissionCny) ===
      commissionParts.value.reduce((sum, part) => sum + cents(part.amount), 0n)
);

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
async function openApply(previousId = "") {
  applyError.value = "";
  try {
    const [limits, options] = await Promise.all([
      getWithdrawalConditions(),
      getWithdrawalAccounts()
    ]);
    conditions.value = limits;
    accounts.value = options;
    form.payoutAccountId = options.find(option => option.id)?.id ?? "manual";
    const rejected = withdrawals.value.find(item => item.id === previousId);
    form.previousId = previousId;
    form.amount = rejected?.amount ?? "";
    applyVisible.value = true;
  } catch {
    applyError.value = t("funds.loadFailed");
  }
}
async function submit() {
  applyError.value = "";
  const normalized = form.amount.trim();
  const validAmount = /^\d+(\.\d{1,2})?$/.test(normalized);
  const cents = validAmount
    ? BigInt(normalized.split(".")[0]) * 100n +
      BigInt(((normalized.split(".")[1] ?? "") + "00").slice(0, 2))
    : 0n;
  const minimum = (conditions.value?.minimum ?? "0.00").split(".");
  const minimumCents = BigInt(minimum[0]) * 100n + BigInt(((minimum[1] ?? "") + "00").slice(0, 2));
  if (!validAmount || cents <= 0n || cents < minimumCents) {
    applyError.value = t("funds.amountInvalid", { minimum: conditions.value?.minimum ?? "0" });
    return;
  }
  if (!form.payoutAccountId) {
    applyError.value = t("funds.accountRequired");
    return;
  }
  if (conditions.value?.canApply === false) {
    applyError.value = conditionBlockMessage.value;
    return;
  }
  if (manualAccount.value) {
    const accountValid =
      form.type === "PAYPAL"
        ? /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.account.trim()) && Boolean(form.currency)
        : /^[A-Za-z0-9]{3,128}$/.test(form.account.trim()) && Boolean(form.network);
    if (!accountValid || !form.holder.trim()) {
      applyError.value = t("funds.accountRequired");
      return;
    }
  }
  submitting.value = true;
  try {
    await submitWithdrawal({
      amount: `${cents / 100n}.${(cents % 100n).toString().padStart(2, "0")}`,
      ...(manualAccount.value
        ? {
            account: {
              type: form.type,
              account: form.account.trim(),
              holder: form.holder.trim(),
              ...(form.type === "PAYPAL" ? { currency: form.currency } : { network: form.network })
            }
          }
        : { payoutAccountId: form.payoutAccountId }),
      note: form.note,
      ...(form.previousId ? { previousId: form.previousId } : {}),
      idempotencyKey: crypto.randomUUID()
    });
    applyVisible.value = false;
    form.amount = "";
    form.previousId = "";
    form.note = "";
    form.account = "";
    form.holder = "";
    await load();
  } catch {
    applyError.value = t("funds.submitFailed");
  } finally {
    submitting.value = false;
  }
}
async function cancelWithdrawal(item: WithdrawalRecord) {
  try {
    await ElMessageBox.confirm(t("funds.withdrawConfirm"), t("funds.withdrawAction"), {
      type: "warning"
    });
    await withdrawWithdrawal(item.id);
    ElMessage.success(t("funds.withdrawSuccess"));
    await load();
  } catch (error) {
    if (error !== "cancel" && error !== "close") ElMessage.error(t("funds.submitFailed"));
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
const conditionBlockMessage = computed(() => {
  if (conditions.value?.withdrawalFrozen) return t("funds.withdrawalFrozen");
  if (conditions.value && conditions.value.unverifiedCount > 0)
    return t("funds.unverifiedBlocked", { count: conditions.value.unverifiedCount });
  return t("funds.cannotApply");
});
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
      <section class="summary-card">
        <div class="income-banner" :aria-label="t('funds.incomeFormula')">
          <dl class="income-metric">
            <dt>{{ t("funds.totalIncome") }}</dt>
            <dd>{{ summaryMoney(summary.totalIncomeCny) }}</dd>
          </dl>
          <span class="income-operator" aria-hidden="true">=</span>
          <dl class="income-metric">
            <dt>{{ t("funds.commissionIncome") }}</dt>
            <dd>{{ summaryMoney(summary.commissionIncomeCny) }}</dd>
          </dl>
          <span class="income-operator" aria-hidden="true">+</span>
          <dl class="income-metric">
            <dt>{{ t("funds.prepayIncome") }}</dt>
            <dd>{{ summaryMoney(summary.receivedPrepayCny) }}</dd>
          </dl>
        </div>
        <div class="commission-breakdown" :aria-label="t('funds.commissionBreakdown')">
          <dl class="commission-metric commission-metric--total">
            <dt>{{ t("funds.estimatedCommission") }}</dt>
            <dd>{{ summaryMoney(summary.estimatedCommissionCny) }}</dd>
          </dl>
          <template v-for="(part, index) in commissionParts" :key="part.key">
            <span class="commission-operator" aria-hidden="true">{{
              commissionReconciles ? (index === 0 ? "=" : "+") : ""
            }}</span>
            <dl class="commission-metric" :data-state="part.key">
              <dt>{{ t(`funds.${part.key}`) }}</dt>
              <dd :class="{ negative: part.amount.startsWith('-') }">
                {{ summaryMoney(part.amount) }}
              </dd>
            </dl>
          </template>
        </div>
        <div class="summary-notes">
          <p>{{ t("funds.incomeHint") }}</p>
          <div class="summary-reconciliation">
            <span
              >{{ t("funds.formula") }}：<strong>{{
                summaryMoney(summary.totalCommissionCny)
              }}</strong></span
            >
            <span
              >{{ t("funds.prepayDeduction") }}：<strong
                :class="{ negative: Number(summary.prepayDeductionCny) > 0 }"
                >{{
                  summaryMoney(
                    Number(summary.prepayDeductionCny) > 0
                      ? `-${summary.prepayDeductionCny}`
                      : "0.00"
                  )
                }}</strong
              ></span
            >
          </div>
        </div>
      </section>
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
            <el-button
              v-if="item.status === 'PENDING_REVIEW'"
              link
              type="danger"
              @click="cancelWithdrawal(item)"
            >
              {{ t("funds.withdrawAction") }}
            </el-button>
            <el-button
              v-if="item.status === 'REJECTED'"
              link
              type="primary"
              @click="openApply(item.id)"
            >
              {{ t("funds.resubmit") }}
            </el-button>
          </div>
        </div>
        <el-empty v-else :description="t('funds.noWithdrawals')" />
      </el-card>
    </template>
    <el-dialog v-model="applyVisible" :title="t('funds.applyTitle')" width="520px" destroy-on-close>
      <el-alert v-if="applyError" type="error" :closable="false" :title="applyError" />
      <el-form label-position="top" @submit.prevent="submit">
        <div v-if="conditions" class="conditions-summary" data-test="withdrawal-conditions">
          <p>{{ t("funds.availableBalance", { amount: money(conditions.available) }) }}</p>
          <p>{{ t("funds.occupiedBalance", { amount: money(conditions.occupied) }) }}</p>
          <p>{{ t("funds.frozenBalance", { amount: money(conditions.frozen) }) }}</p>
          <p>{{ t("funds.estimatedBalance", { amount: money(conditions.estimated) }) }}</p>
          <el-alert
            v-if="conditions.withdrawalFrozen"
            type="warning"
            :closable="false"
            :title="t('funds.withdrawalFrozen')"
          />
          <el-alert
            v-else-if="conditions.unverifiedCount > 0"
            type="warning"
            :closable="false"
            :title="t('funds.unverifiedBlocked', { count: conditions.unverifiedCount })"
          />
          <el-alert
            v-else-if="!conditions.canApply"
            type="warning"
            :closable="false"
            :title="t('funds.cannotApply')"
          />
        </div>
        <el-form-item :label="t('funds.amount')"
          ><el-input v-model="form.amount" inputmode="decimal"
            ><template #append>{{ conditions?.currency ?? "CNY" }}</template></el-input
          ><small>{{
            t("funds.minimum", { amount: conditions?.minimum ?? "—" })
          }}</small></el-form-item
        >
        <el-form-item :label="t('funds.recipient')"
          ><el-select v-model="form.payoutAccountId" :placeholder="t('funds.chooseAccount')"
            ><el-option :value="'manual'" :label="t('funds.manualAccount')" /> ><el-option
              v-for="account in accounts"
              :key="account.id"
              :value="account.id"
              :label="`${account.accountType} · ${account.account}`" /></el-select
          ><RouterLink to="/payout-accounts" class="account-link">{{
            t("funds.manageAccounts")
          }}</RouterLink></el-form-item
        >
        <template v-if="manualAccount">
          <el-form-item :label="t('funds.accountType')">
            <el-select v-model="form.type">
              <el-option value="PAYPAL" label="PayPal" />
              <el-option value="USDT" label="USDT" />
            </el-select>
          </el-form-item>
          <el-form-item
            :label="form.type === 'PAYPAL' ? t('funds.paypalEmail') : t('funds.usdtAddress')"
          >
            <el-input v-model="form.account" autocomplete="off" />
          </el-form-item>
          <el-form-item :label="t('funds.holder')"><el-input v-model="form.holder" /></el-form-item>
          <el-form-item v-if="form.type === 'PAYPAL'" :label="t('funds.currency')">
            <el-select v-model="form.currency">
              <el-option
                v-for="currency in ['USD', 'EUR', 'GBP', 'CNY']"
                :key="currency"
                :value="currency"
                :label="currency"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-else :label="t('funds.network')">
            <el-select v-model="form.network">
              <el-option
                v-for="network in ['TRC20', 'ERC20', 'BEP20', 'SOL', 'TON']"
                :key="network"
                :value="network"
                :label="network"
              />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item :label="t('funds.note')"
          ><el-input v-model="form.note" type="textarea" maxlength="500" show-word-limit
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="applyVisible = false">{{ t("common.cancel") }}</el-button
        ><el-button
          type="primary"
          :loading="submitting"
          :disabled="conditions?.canApply === false"
          @click="submit"
          >{{ t("funds.submit") }}</el-button
        ></template
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
.summary-card {
  display: grid;
  gap: var(--tcrm-space-5);
}
.income-banner {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: var(--tcrm-space-5);
  padding: var(--tcrm-space-6);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-primary);
  color: var(--tcrm-color-on-primary);
}
.income-metric,
.commission-metric {
  display: grid;
  align-content: center;
  gap: var(--tcrm-space-4);
  min-width: 0;
  margin: 0;
}
#app .income-metric dt,
#app .commission-metric dt {
  font-size: var(--tcrm-font-size-section) !important;
  line-height: var(--tcrm-line-height-section) !important;
}
#app .income-metric dd,
#app .commission-metric dd {
  margin: 0;
  font-size: var(--tcrm-font-size-title) !important;
  line-height: var(--tcrm-line-height-title) !important;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.income-operator,
.commission-operator {
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  text-align: center;
}
.commission-breakdown {
  display: grid;
  grid-template-columns: minmax(0, 1fr) repeat(4, auto minmax(0, 1fr));
  align-items: center;
  gap: var(--tcrm-space-3);
}
.commission-metric {
  align-content: space-between;
  align-self: stretch;
  padding: var(--tcrm-space-5) var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-primary);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.summary-notes {
  display: grid;
  gap: var(--tcrm-space-2);
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
.conditions-summary {
  display: grid;
  gap: var(--tcrm-space-1);
  margin-bottom: var(--tcrm-space-4);
  padding: var(--tcrm-space-3);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
  background: var(--tcrm-color-muted);
}
.conditions-summary p {
  margin: 0;
  color: var(--tcrm-color-text-secondary);
}
.summary-notes p {
  margin: 0;
}
.summary-reconciliation {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-4);
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
@media (max-width: 1279px) {
  .commission-operator {
    display: none;
  }
  .commission-breakdown {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .commission-metric--total {
    grid-column: 1 / -1;
  }
}
@media (max-width: 1023px) {
  .income-banner {
    grid-template-columns: 1fr;
    padding: var(--tcrm-space-5);
  }
  .income-metric {
    gap: var(--tcrm-space-2);
  }
  .income-operator {
    display: none;
  }
}
@media (max-width: 767px) {
  .withdrawal-row {
    grid-template-columns: 1fr auto;
  }
}
</style>
