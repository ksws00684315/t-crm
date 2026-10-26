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
import CopyText from "@/components/CopyText.vue";
import { uploadQrFile } from "@/api/payout-account";
import { ApiError } from "@/utils/http/error";
import { checkQrFile } from "@/utils/payout-account";
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
const limitsLoading = ref(false);
const qrUploading = ref(false);
const applyError = ref("");
let submittedPayload = "";
let idempotencyKey = "";
const form = reactive({
  amount: "",
  previousId: "",
  payoutAccountId: "",
  note: "",
  type: "PAYPAL" as "PAYPAL" | "USDT",
  account: "",
  holder: "",
  currency: "USD",
  network: "TRC20",
  qrFileId: ""
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
const incomeComposition = computed(() => {
  if (!summary.value) return null;
  const total = cents(summary.value.totalIncomeCny);
  const commission = cents(summary.value.commissionIncomeCny);
  const prepay = cents(summary.value.receivedPrepayCny);
  if (total <= 0n || commission < 0n || prepay < 0n || commission + prepay !== total) return null;
  const percent = (part: bigint) => Number((part * 1000n + total / 2n) / total) / 10;
  return {
    commissionWidth: `${percent(commission)}%`,
    prepayWidth: `${percent(prepay)}%`,
    commissionPercent: `${percent(commission).toLocaleString(locale.value, {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1
    })}%`,
    prepayPercent: `${percent(prepay).toLocaleString(locale.value, {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1
    })}%`
  };
});
function summaryMoney(value: string) {
  return amountLabel(value, "CNY");
}
function amountLabel(value: string, currency: string) {
  return `${Number(value).toLocaleString(locale.value, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })} ${currency}`;
}
function cents(value: string) {
  const [whole, fraction = ""] = value.split(".");
  const unsigned = BigInt(whole.replace("-", "")) * 100n + BigInt(fraction.padEnd(2, "0"));
  return value.startsWith("-") ? -unsigned : unsigned;
}
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
  if (limitsLoading.value || submitting.value || qrUploading.value) return;
  applyError.value = "";
  conditions.value = null;
  accounts.value = [];
  applyVisible.value = true;
  limitsLoading.value = true;
  submittedPayload = "";
  idempotencyKey = "";
  Object.assign(form, {
    amount: "",
    previousId,
    payoutAccountId: "",
    note: "",
    type: "PAYPAL",
    account: "",
    holder: "",
    currency: "USD",
    network: "TRC20",
    qrFileId: ""
  });
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
  } catch (error) {
    applyError.value = errorMessage(error, "funds.loadFailed");
  } finally {
    limitsLoading.value = false;
  }
}
function errorMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError ? (error.handled ? "" : error.displayMessage) : t(fallback);
}
async function pickQr(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  const check = checkQrFile(file);
  if (check !== "ok") {
    applyError.value = t(check === "type" ? "errors.FILE_INVALID" : "errors.FILE_TOO_LARGE");
    input.value = "";
    return;
  }
  qrUploading.value = true;
  applyError.value = "";
  try {
    form.qrFileId = (await uploadQrFile(file)).fileId;
  } catch (error) {
    applyError.value = errorMessage(error, "errors.generic");
  } finally {
    qrUploading.value = false;
    input.value = "";
  }
}
async function submit() {
  if (submitting.value || qrUploading.value || limitsLoading.value || !conditions.value) return;
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
    const payload = {
      amount: `${cents / 100n}.${(cents % 100n).toString().padStart(2, "0")}`,
      ...(manualAccount.value
        ? {
            account: {
              type: form.type,
              account: form.account.trim(),
              holder: form.holder.trim(),
              ...(form.type === "PAYPAL"
                ? { currency: form.currency }
                : { network: form.network, ...(form.qrFileId ? { qrFileId: form.qrFileId } : {}) })
            }
          }
        : { payoutAccountId: form.payoutAccountId }),
      note: form.note,
      ...(form.previousId ? { previousId: form.previousId } : {})
    };
    const fingerprint = JSON.stringify(payload);
    if (submittedPayload !== fingerprint) {
      submittedPayload = fingerprint;
      idempotencyKey = crypto.randomUUID();
    }
    await submitWithdrawal({ ...payload, idempotencyKey });
    applyVisible.value = false;
    form.amount = "";
    form.previousId = "";
    form.note = "";
    form.account = "";
    form.holder = "";
    await load();
  } catch (error) {
    applyError.value = errorMessage(error, "funds.submitFailed");
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
    if (error !== "cancel" && error !== "close") {
      const message = errorMessage(error, "funds.submitFailed");
      if (message) ElMessage.error(message);
    }
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
function sizeChanged(value: number) {
  size.value = value;
  page.value = 1;
  // DataTable follows this event with update:page(1), which performs the single refresh.
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
      <el-button type="primary" data-test="apply-withdrawal" @click="openApply()">{{
        t("funds.apply")
      }}</el-button>
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
        <section class="income-overview" :aria-label="t('funds.incomeComposition')">
          <div class="income-total">
            <span class="income-total__eyebrow">{{ t("funds.totalIncome") }}</span>
            <strong class="income-total__amount">{{ summaryMoney(summary.totalIncomeCny) }}</strong>
            <span class="income-total__caption">{{ t("funds.incomeReceived") }}</span>
          </div>
          <div class="income-sources">
            <div class="income-source">
              <span class="income-source__dot income-source__dot--commission" aria-hidden="true" />
              <span class="income-source__label">{{ t("funds.commissionIncome") }}</span>
              <strong>{{ summaryMoney(summary.commissionIncomeCny) }}</strong>
              <span class="income-source__percent">{{
                incomeComposition?.commissionPercent ?? "—"
              }}</span>
            </div>
            <div class="income-source">
              <span class="income-source__dot income-source__dot--prepay" aria-hidden="true" />
              <span class="income-source__label">{{ t("funds.prepayIncome") }}</span>
              <strong>{{ summaryMoney(summary.receivedPrepayCny) }}</strong>
              <span class="income-source__percent">{{
                incomeComposition?.prepayPercent ?? "—"
              }}</span>
            </div>
            <div
              v-if="incomeComposition"
              class="income-composition-bar"
              role="img"
              :aria-label="t('funds.incomeFormula')"
            >
              <span
                class="income-composition-bar__commission"
                :style="{ width: incomeComposition.commissionWidth }"
              />
              <span
                class="income-composition-bar__prepay"
                :style="{ width: incomeComposition.prepayWidth }"
              />
            </div>
            <p class="income-sources__caption">{{ t("funds.incomeFormula") }}</p>
          </div>
        </section>
        <div class="commission-overview-heading">
          <h2>{{ t("funds.commissionOverview") }}</h2>
          <span>{{ t("funds.amountCurrency") }}</span>
        </div>
        <div class="commission-cards" :aria-label="t('funds.commissionBreakdown')">
          <dl class="commission-metric">
            <dt>{{ t("funds.estimatedCommission") }}</dt>
            <dd>{{ summaryMoney(summary.estimatedCommissionCny) }}</dd>
          </dl>
          <dl
            v-for="part in commissionParts"
            :key="part.key"
            class="commission-metric"
            :data-state="part.key"
          >
            <dt :data-state="part.key">
              <span
                v-if="part.key !== 'currentAvailable'"
                class="commission-metric__dot"
                :data-state="part.key"
                aria-hidden="true"
              />
              <span class="commission-metric__label">{{ t(`funds.${part.key}`) }}</span>
              <span v-if="part.key === 'currentAvailable'" class="available-badge">
                {{ t("funds.availableBadge") }}
              </span>
            </dt>
            <dd
              :class="[
                { negative: part.amount.startsWith('-') },
                { 'commission-metric__amount--available': part.key === 'currentAvailable' }
              ]"
            >
              {{ summaryMoney(part.amount) }}
            </dd>
          </dl>
        </div>
        <div class="funds-note">
          <span class="funds-note__icon" aria-hidden="true">i</span>
          <div class="funds-note__content">
            <strong>{{ t("funds.fundsNote") }}</strong>
            <p>{{ t("funds.incomeHint") }}</p>
            <div class="funds-note__details">
              <span>
                {{ t("funds.totalCommission") }}：<strong>{{
                  summaryMoney(summary.totalCommissionCny)
                }}</strong>
                <span class="funds-note__calculation">({{ t("funds.commissionTotalParts") }})</span>
              </span>
              <span>
                {{ t("funds.prepayDeduction") }}：
                <strong :class="{ negative: Number(summary.prepayDeductionCny) > 0 }">
                  {{
                    summaryMoney(
                      Number(summary.prepayDeductionCny) > 0
                        ? `-${summary.prepayDeductionCny}`
                        : "0.00"
                    )
                  }}
                </strong>
              </span>
            </div>
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
          class="records-table"
          :rows="records"
          :columns="columns"
          :loading="loading"
          :total="total"
          :page="page"
          :size="size"
          @update:page="pageChanged"
          @update:size="sizeChanged"
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
          <template #cell-amount="{ row }"
            ><span data-test="fund-record-amount">{{
              amountLabel(row.amount, row.currency)
            }}</span></template
          >
          <template #cell-recipient="{ row }">{{ row.maskedRecipient || "—" }}</template>
          <template #cell-receivedAt="{ row }">{{ dateLabel(row.receivedAt) }}</template>
          <template #empty
            ><div class="empty-state">{{ t("funds.empty") }}</div></template
          >
        </DataTable>
        <div class="fund-record-cards" :aria-busy="loading">
          <article
            v-for="record in records"
            :key="record.id"
            class="fund-record-card"
            data-test="fund-record-card"
          >
            <dl>
              <dt>{{ t("funds.applicationNo") }}</dt>
              <dd><CopyText :value="record.applicationNo" /></dd>
              <dt>{{ t("funds.type") }}</dt>
              <dd>
                <el-tag :type="record.type === 'PREPAY' ? 'warning' : 'primary'">{{
                  recordType(record.type)
                }}</el-tag>
              </dd>
              <dt>{{ t("funds.appliedAt") }}</dt>
              <dd>{{ dateLabel(record.appliedAt) }}</dd>
              <dt>{{ t("funds.amount") }}</dt>
              <dd class="fund-record-amount">{{ amountLabel(record.amount, record.currency) }}</dd>
              <dt>{{ t("funds.recipient") }}</dt>
              <dd>{{ record.maskedRecipient || "—" }}</dd>
              <dt>{{ t("funds.receivedAt") }}</dt>
              <dd>{{ dateLabel(record.receivedAt) }}</dd>
            </dl>
          </article>
          <el-empty v-if="!records.length && !loading" :description="t('funds.empty')" />
        </div>
        <el-pagination
          class="fund-records-pager"
          :current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          @current-change="pageChanged"
        />
      </el-card>
      <el-card shadow="never" class="withdrawals-card">
        <div class="section-heading">
          <h2>{{ t("funds.withdrawalHistory") }}</h2>
          <RouterLink to="/payout-accounts">{{ t("funds.manageAccounts") }}</RouterLink>
        </div>
        <div v-if="withdrawals.length" class="withdrawal-list">
          <div v-for="item in withdrawals" :key="item.id" class="withdrawal-row">
            <span>{{ item.applicationNo }}</span
            ><strong>{{ amountLabel(item.amount, item.currency) }}</strong
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
              data-test="resubmit-withdrawal"
              @click="openApply(item.id)"
            >
              {{ t("funds.resubmit") }}
            </el-button>
          </div>
        </div>
        <el-empty v-else :description="t('funds.noWithdrawals')" />
      </el-card>
    </template>
    <el-dialog
      v-model="applyVisible"
      :title="t('funds.applyTitle')"
      width="min(520px, calc(100vw - 24px))"
      destroy-on-close
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting && !qrUploading"
      :show-close="!submitting && !qrUploading"
    >
      <el-alert v-if="applyError" type="error" :closable="false" :title="applyError" />
      <el-form
        v-loading="limitsLoading"
        :disabled="limitsLoading || submitting"
        label-position="top"
        @submit.prevent="submit"
      >
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
          ><el-input v-model="form.amount" inputmode="decimal" data-test="withdrawal-amount"
            ><template #append>{{ conditions?.currency ?? "CNY" }}</template></el-input
          ><small>{{
            t("funds.minimum", { amount: conditions?.minimum ?? "—" })
          }}</small></el-form-item
        >
        <el-form-item :label="t('funds.recipient')"
          ><el-select
            v-model="form.payoutAccountId"
            :placeholder="t('funds.chooseAccount')"
            data-test="withdrawal-recipient"
            ><el-option :value="'manual'" :label="t('funds.manualAccount')" /><el-option
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
            <el-select v-model="form.type" data-test="withdrawal-account-type">
              <el-option value="PAYPAL" label="PayPal" />
              <el-option value="USDT" label="USDT" />
            </el-select>
          </el-form-item>
          <el-form-item
            :label="form.type === 'PAYPAL' ? t('funds.paypalEmail') : t('funds.usdtAddress')"
          >
            <el-input
              v-model="form.account"
              autocomplete="off"
              maxlength="128"
              data-test="withdrawal-account"
            />
          </el-form-item>
          <el-form-item :label="t('funds.holder')"
            ><el-input v-model="form.holder" maxlength="64" data-test="withdrawal-holder"
          /></el-form-item>
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
          <el-form-item v-if="form.type === 'USDT'" :label="t('payoutAccounts.form.qr')">
            <input
              type="file"
              accept="image/png,image/jpeg"
              :disabled="qrUploading || submitting"
              data-test="withdrawal-qr"
              @change="pickQr"
            />
            <small>{{ t("payoutAccounts.form.qrHint") }}</small>
            <el-tag v-if="form.qrFileId" type="success">{{ t("account.qrUploaded") }}</el-tag>
          </el-form-item>
        </template>
        <el-form-item :label="t('funds.note')"
          ><el-input v-model="form.note" type="textarea" maxlength="500" show-word-limit
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button :disabled="submitting || qrUploading" @click="applyVisible = false">{{
          t("common.cancel")
        }}</el-button
        ><el-button
          type="primary"
          :loading="submitting"
          :disabled="!conditions || !conditions.canApply || limitsLoading || qrUploading"
          data-test="submit-withdrawal"
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
.income-overview {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
  align-items: center;
  gap: var(--tcrm-space-6);
  padding: var(--tcrm-space-6);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.income-total {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: var(--tcrm-space-2);
  min-height: 144px;
  padding: var(--tcrm-space-5);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-muted);
}
.income-total__eyebrow {
  color: var(--tcrm-color-text);
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  font-weight: 600;
}
.income-total__amount {
  color: var(--tcrm-color-primary-text);
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.income-total__caption,
.income-source__percent,
.income-sources__caption {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
.income-sources {
  display: grid;
  align-content: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.income-source {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: var(--tcrm-space-3);
  min-width: 0;
  padding: var(--tcrm-space-2) 0;
}
.income-source + .income-source {
  border-top: 1px solid var(--tcrm-color-border);
}
.income-source__dot,
.commission-metric__dot {
  width: var(--tcrm-space-3);
  height: var(--tcrm-space-3);
  flex: 0 0 var(--tcrm-space-3);
  border-radius: 50%;
  background: var(--tcrm-color-primary);
}
.income-source__dot--prepay {
  background: var(--tcrm-color-success);
}
.income-source__label {
  min-width: 0;
  overflow-wrap: anywhere;
}
.income-source strong {
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.income-source__percent {
  min-width: 4ch;
  text-align: right;
}
.income-composition-bar {
  display: flex;
  width: 100%;
  height: var(--tcrm-space-2);
  overflow: hidden;
  margin-top: var(--tcrm-space-2);
  border-radius: var(--tcrm-radius-tag);
  background: var(--tcrm-color-muted);
}
.income-composition-bar__commission {
  min-width: 0;
  background: var(--tcrm-color-primary);
}
.income-composition-bar__prepay {
  min-width: 0;
  background: var(--tcrm-color-success);
}
.income-sources__caption {
  margin: var(--tcrm-space-1) 0 0;
}
.commission-overview-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
}
.commission-overview-heading h2 {
  margin: 0;
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  font-weight: 600;
}
.commission-overview-heading > span {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
.commission-cards {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--tcrm-space-3);
}
.commission-metric {
  display: grid;
  align-content: space-between;
  gap: var(--tcrm-space-3);
  min-width: 0;
  margin: 0;
  padding: var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.commission-metric[data-state="currentAvailable"] {
  border-color: var(--tcrm-color-primary);
  background: var(--tcrm-color-muted);
}
.commission-metric dt {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-height: var(--tcrm-line-height-body);
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  overflow-wrap: anywhere;
}
.commission-metric__dot[data-state="withdrawn"] {
  background: var(--tcrm-color-success);
}
.commission-metric__dot[data-state="frozen"] {
  background: var(--tcrm-color-warning);
}
.commission-metric__dot[data-state="withdrawing"] {
  background: var(--tcrm-color-primary);
}
.available-badge {
  margin-left: auto;
  padding: 0 var(--tcrm-space-2);
  border: 1px solid var(--tcrm-color-primary);
  border-radius: var(--tcrm-radius-tag);
  color: var(--tcrm-color-primary-text);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
  white-space: nowrap;
}
.commission-metric dd {
  margin: 0;
  color: var(--tcrm-color-text);
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.commission-metric__amount--available:not(.negative) {
  color: var(--tcrm-color-primary-text);
}
.funds-note {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--tcrm-space-3);
  padding: var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.funds-note__icon {
  display: grid;
  place-items: center;
  width: var(--tcrm-space-6);
  height: var(--tcrm-space-6);
  border-radius: 50%;
  background: var(--tcrm-color-muted);
  color: var(--tcrm-color-primary-text);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  font-weight: 600;
}
.funds-note__content {
  display: grid;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.funds-note__content > strong {
  color: var(--tcrm-color-text);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
}
.funds-note__content p {
  margin: 0;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
}
.funds-note__details {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-3) var(--tcrm-space-5);
  padding-top: var(--tcrm-space-2);
  border-top: 1px solid var(--tcrm-color-border);
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
}
.funds-note__details strong {
  color: var(--tcrm-color-text);
  font-variant-numeric: tabular-nums;
}
.funds-note__calculation {
  margin-left: var(--tcrm-space-1);
  color: var(--tcrm-color-text-secondary);
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
.fund-record-cards,
.fund-records-pager {
  display: none;
}
.fund-record-card {
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  padding: var(--tcrm-space-3);
}
.fund-record-card dl {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr);
  align-items: start;
  gap: var(--tcrm-space-2);
  margin: 0;
}
.fund-record-card dt {
  color: var(--tcrm-color-text-secondary);
}
.fund-record-card dd {
  margin: 0;
  min-width: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
.fund-record-amount {
  font-variant-numeric: tabular-nums;
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
  .commission-cards {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 1023px) {
  .income-overview {
    grid-template-columns: 1fr;
    padding: var(--tcrm-space-5);
  }
  .income-total {
    min-height: auto;
  }
}
@media (max-width: 767px) {
  .commission-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--tcrm-space-2);
  }
  .commission-metric {
    padding: var(--tcrm-space-3);
  }
  .commission-metric dt {
    align-items: flex-start;
  }
  .commission-metric__dot {
    margin-top: var(--tcrm-space-1);
  }
  .commission-metric[data-state="currentAvailable"] dt {
    display: grid;
    justify-items: start;
    gap: var(--tcrm-space-1);
  }
  .commission-metric[data-state="currentAvailable"] .available-badge {
    margin-left: 0;
  }
  .income-source {
    grid-template-columns: auto minmax(0, 1fr) auto;
  }
  .income-source__percent {
    display: none;
  }
  .funds-note {
    padding: var(--tcrm-space-3);
  }
  .records-table {
    display: none;
  }
  .fund-record-cards {
    display: grid;
    gap: var(--tcrm-space-3);
  }
  .fund-records-pager {
    display: flex;
    justify-content: center;
    margin-top: var(--tcrm-space-3);
  }
  .withdrawal-row {
    grid-template-columns: 1fr auto;
  }
}
</style>
