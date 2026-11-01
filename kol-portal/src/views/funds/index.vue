<script setup lang="ts">
import { ArrowRight } from "@element-plus/icons-vue";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getFundRecords,
  getFundSummary,
  getWithdrawalAccounts,
  getWithdrawalConditions,
  submitWithdrawal,
  withdrawWithdrawal,
  type FundRecord,
  type FundSummary,
  type WithdrawalAccountOption,
  type WithdrawalConditions
} from "@/api/creator";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import AmountText from "@/components/AmountText.vue";
import CopyText from "@/components/CopyText.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { uploadQrFile } from "@/api/payout-account";
import { formatInstant } from "@/utils/format";
import { ApiError } from "@/utils/http/error";
import { moneyCents } from "@/utils/money";
import { checkQrFile } from "@/utils/payout-account";
import { formatMoney } from "@/utils/orders";
import { randomUuid } from "@/utils/uuid";
import { isNegativeAmount, workspaceFigures } from "@/utils/workspace";

defineOptions({ name: "FundsPage" });
const { t, locale } = useI18n();
const route = useRoute();
const router = useRouter();
const summary = ref<FundSummary | null>(null);
const records = ref<(FundRecord & { rowKey: string })[]>([]);
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
/** Column floors per language (规范 11 §2): long English labels get more room, nothing wraps. */
const columns = computed<DataColumn[]>(() => {
  const zh = locale.value === "zh-CN";
  return [
    { key: "appliedAt", label: t("funds.appliedAt"), minWidth: zh ? 180 : 200 },
    {
      key: "applicationNo",
      align: "left" as const,
      label: t("funds.applicationNo"),
      minWidth: 210
    },
    { key: "type", label: t("funds.type"), minWidth: zh ? 110 : 200 },
    { key: "amount", label: t("funds.amount"), minWidth: 130, amount: true },
    { key: "status", label: t("funds.statusLabel"), minWidth: zh ? 190 : 250 },
    { key: "recipient", align: "left" as const, label: t("funds.recipient"), minWidth: 200 },
    { key: "receivedAt", label: t("funds.receivedAt"), minWidth: zh ? 180 : 200 },
    { key: "actions", label: t("common.actions"), width: zh ? 110 : 150 }
  ];
});
const figures = computed(() => (summary.value ? workspaceFigures(summary.value) : null));
/** The four steps of a commission, left to right (task 92 section 4.1). */
type StepKey = "frozen" | "withdrawable" | "withdrawing" | "withdrawn";
const steps = computed<{ key: StepKey; tone: string; amount: string }[]>(() =>
  figures.value
    ? [
        { key: "frozen", tone: "warning", amount: figures.value.frozen },
        { key: "withdrawable", tone: "success", amount: figures.value.available },
        { key: "withdrawing", tone: "neutral", amount: figures.value.occupied },
        { key: "withdrawn", tone: "neutral", amount: figures.value.commissionPaid }
      ]
    : []
);
const stepText = {
  frozen: ["funds.stepFrozen", "funds.stepFrozenCaption"],
  withdrawable: ["funds.stepWithdrawable", "funds.stepWithdrawableCaption"],
  withdrawing: ["funds.stepWithdrawing", "funds.stepWithdrawingCaption"],
  withdrawn: ["funds.stepWithdrawn", "funds.stepWithdrawnCaption"]
} as const;
const prepayNote = computed(() => {
  if (!summary.value) return "";
  const deducted = moneyCents(summary.value.prepayDeductionCny);
  const pending = moneyCents(summary.value.prepayOutstandingCny);
  if (deducted === 0n && pending === 0n) return "";
  return t("funds.prepayNote", {
    deducted: money(summary.value.prepayDeductionCny),
    pending: money(summary.value.prepayOutstandingCny)
  });
});
async function load() {
  loading.value = true;
  failure.value = false;
  try {
    const [summaryResult, history] = await Promise.all([
      getFundSummary(),
      getFundRecords({ page: page.value, size: size.value })
    ]);
    summary.value = summaryResult.fundSummary;
    records.value = history.items.map(item => ({ ...item, rowKey: `${item.type}-${item.id}` }));
    total.value = Number(history.total);
  } catch {
    failure.value = true;
  } finally {
    loading.value = false;
  }
}
async function openApply(previousId = "", previousAmount = "") {
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
    form.previousId = previousId;
    form.amount = previousAmount;
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
      idempotencyKey = randomUuid();
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
async function cancelWithdrawal(item: { id: string }) {
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
function recordAmount(row: FundRecord) {
  // Commission and advance records are CNY; any other currency keeps its code instead of a yen sign.
  return row.currency === "CNY"
    ? money(row.amount)
    : `${Number(row.amount).toLocaleString(locale.value, {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
      })} ${row.currency}`;
}
function statusLabel(value: string) {
  return t(`funds.status.${value}`, value);
}
/** Waiting statuses use the warning colour, paid the success colour, rejected the danger colour. */
function statusType(value: string): "warning" | "success" | "danger" | "info" {
  if (value === "PENDING_REVIEW" || value === "PENDING_PAYMENT") return "warning";
  if (value === "PAID") return "success";
  if (value === "REJECTED") return "danger";
  return "info";
}
function canCancel(row: FundRecord) {
  return row.type === "COMMISSION" && row.publicStatus === "PENDING_REVIEW";
}
function canReapply(row: FundRecord) {
  return row.type === "COMMISSION" && row.publicStatus === "REJECTED";
}
function rowActions(row: FundRecord): RowAction[] {
  return [
    {
      key: "cancel",
      label: t("funds.rowWithdraw"),
      type: "danger",
      testId: "cancel-withdrawal",
      hidden: !canCancel(row),
      onClick: () => void cancelWithdrawal(row)
    },
    {
      key: "reapply",
      label: t("funds.resubmit"),
      testId: "resubmit-withdrawal",
      hidden: !canReapply(row),
      onClick: () => void openApply(row.id, row.amount)
    }
  ];
}
function hasActions(row: FundRecord) {
  return canCancel(row) || canReapply(row);
}
const conditionBlockMessage = computed(() => {
  if (conditions.value?.withdrawalFrozen) return t("funds.withdrawalFrozen");
  if (conditions.value && conditions.value.unverifiedCount > 0)
    return t("funds.unverifiedBlocked", { count: conditions.value.unverifiedCount });
  return t("funds.cannotApply");
});
/** The workspace links here with ?apply=1: open the dialog once and drop the parameter. */
watch(
  () => route.query.apply,
  value => {
    if (value !== "1") return;
    void openApply();
    const rest = { ...route.query };
    delete rest.apply;
    void router.replace({ query: rest });
  },
  { immediate: true }
);
onMounted(load);
</script>

<template>
  <section class="funds-page">
    <header class="page-heading">
      <h1>{{ t("funds.title") }}</h1>
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
      <section class="progress-card" aria-labelledby="progress-title" data-test="progress-card">
        <h2 id="progress-title">{{ t("funds.progressTitle") }}</h2>
        <ol class="progress-steps">
          <template v-for="(step, index) in steps" :key="step.key">
            <li
              class="progress-step"
              :class="`progress-step--${step.tone}`"
              :data-test="`step-${step.key}`"
            >
              <span class="progress-step__label">
                <span class="progress-step__dot" aria-hidden="true" />
                {{ t(stepText[step.key][0]) }}
              </span>
              <strong
                class="tcrm-amount-detail progress-step__amount"
                :class="{ negative: isNegativeAmount(step.amount) }"
                :data-test="`step-${step.key}-amount`"
                ><AmountText :value="money(step.amount)"
              /></strong>
              <span class="progress-step__caption">{{ t(stepText[step.key][1]) }}</span>
            </li>
            <li v-if="index < steps.length - 1" class="progress-arrow" aria-hidden="true">
              <el-icon :size="16"><ArrowRight /></el-icon>
            </li>
          </template>
        </ol>
        <p v-if="prepayNote" class="progress-note" data-test="prepay-note">{{ prepayNote }}</p>
      </section>
      <section class="action-card" data-test="action-card">
        <el-button type="primary" data-test="apply-withdrawal" @click="openApply()">{{
          t("funds.apply")
        }}</el-button>
        <el-button data-test="manage-accounts" @click="router.push('/payout-accounts')">{{
          t("funds.manageAccounts")
        }}</el-button>
      </section>
    </template>
    <DataTable
      table-id="fund-records"
      class="records-table"
      row-key="rowKey"
      :rows="records"
      :columns="columns"
      :loading="loading"
      :total="total"
      :paged="total > 0 && !failure"
      :page="page"
      :size="size"
      @update:page="pageChanged"
      @update:size="sizeChanged"
      @refresh="load"
    >
      <template #title
        ><h2>{{ t("funds.recordsTitle") }}</h2></template
      >
      <template #cell-appliedAt="{ row }">{{ formatInstant(row.appliedAt) }}</template>
      <template #cell-applicationNo="{ row }"><CopyText :value="row.applicationNo" /></template>
      <template #cell-type="{ row }">{{ recordType(row.type) }}</template>
      <template #cell-amount="{ row }"
        ><span data-test="fund-record-amount">{{ recordAmount(row) }}</span></template
      >
      <template #cell-status="{ row }"
        ><el-tag :type="statusType(row.publicStatus)" data-test="fund-record-status">{{
          statusLabel(row.publicStatus)
        }}</el-tag></template
      >
      <template #cell-recipient="{ row }">{{ row.maskedRecipient || "—" }}</template>
      <template #cell-receivedAt="{ row }">{{ formatInstant(row.receivedAt) }}</template>
      <template #actions="{ row }">
        <RowActions v-if="hasActions(row)" :actions="rowActions(row)" />
        <span v-else>-</span>
      </template>
      <!-- On failure a real element replaces the copy: a comment-only slot would fall back to DataTable's generic empty text. -->
      <template #empty
        ><div v-if="!failure" class="empty-state">{{ t("funds.empty") }}</div>
        <span v-else
      /></template>
    </DataTable>
    <div class="fund-record-cards" :aria-busy="loading">
      <h2 class="fund-record-cards__title">{{ t("funds.recordsTitle") }}</h2>
      <article
        v-for="record in records"
        :key="record.rowKey"
        class="fund-record-card"
        data-test="fund-record-card"
      >
        <dl>
          <dt>{{ t("funds.applicationNo") }}</dt>
          <dd><CopyText :value="record.applicationNo" /></dd>
          <dt>{{ t("funds.type") }}</dt>
          <dd>{{ recordType(record.type) }}</dd>
          <dt>{{ t("funds.statusLabel") }}</dt>
          <dd>
            <el-tag :type="statusType(record.publicStatus)">{{
              statusLabel(record.publicStatus)
            }}</el-tag>
          </dd>
          <dt>{{ t("funds.appliedAt") }}</dt>
          <dd>{{ formatInstant(record.appliedAt) }}</dd>
          <dt>{{ t("funds.amount") }}</dt>
          <dd class="fund-record-amount">{{ recordAmount(record) }}</dd>
          <dt>{{ t("funds.recipient") }}</dt>
          <dd>{{ record.maskedRecipient || "—" }}</dd>
          <dt>{{ t("funds.receivedAt") }}</dt>
          <dd>{{ formatInstant(record.receivedAt) }}</dd>
        </dl>
        <RowActions
          v-if="hasActions(record)"
          class="fund-record-card__actions"
          :actions="rowActions(record)"
        />
      </article>
      <el-empty v-if="!records.length && !loading && !failure" :description="t('funds.empty')" />
      <el-pagination
        v-if="total > 0 && !failure"
        class="fund-records-pager"
        :current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next"
        @current-change="pageChanged"
      />
    </div>
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
  height: 100%;
  min-height: 720px;
  min-width: 0;
}
.page-heading {
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
.progress-card,
.action-card {
  padding: var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.progress-card {
  display: grid;
  gap: var(--tcrm-space-3);
}
.progress-card h2,
.records-table h2 {
  margin: 0;
}
.progress-steps {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: stretch;
  gap: var(--tcrm-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}
.progress-step {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--tcrm-space-1);
  min-width: 0;
  padding: var(--tcrm-space-3) var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.progress-step--success {
  border-color: var(--tcrm-color-success);
  background: var(--tcrm-color-success-bg);
}
.progress-step__label {
  display: inline-flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  font-weight: 600;
}
.progress-step__dot {
  width: var(--tcrm-space-3);
  height: var(--tcrm-space-3);
  border-radius: 50%;
  background: var(--tcrm-color-text-secondary);
}
.progress-step--warning .progress-step__dot {
  background: var(--tcrm-color-warning);
}
.progress-step--success .progress-step__dot {
  background: var(--tcrm-color-success);
}
.progress-step__amount {
  overflow-wrap: break-word;
}
.progress-step--success .progress-step__amount:not(.negative) {
  color: var(--tcrm-color-success);
}
.progress-step__caption {
  color: var(--tcrm-color-text-secondary);
}
.progress-arrow {
  display: flex;
  align-items: center;
  color: var(--tcrm-color-text-secondary);
}
/* The icon is 16px (规范 13 §4.5); the global type reset would otherwise shrink it to the body size. */
.progress-arrow :deep(.el-icon),
.progress-arrow :deep(svg) {
  width: 16px;
  height: 16px;
}
.progress-note {
  margin: 0;
  color: var(--tcrm-color-text-secondary);
}
.action-card {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-start;
  gap: var(--tcrm-space-2);
}
.action-card .el-button {
  margin: 0;
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
.fund-record-cards {
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
/* A long status such as "Approved, awaiting payment" wraps inside the card instead of widening the page. */
.fund-record-card dd .el-tag {
  max-width: 100%;
  height: auto;
  padding-block: var(--tcrm-space-1);
  white-space: normal;
}
.fund-record-card dd :deep(.el-tag__content) {
  white-space: normal;
  overflow-wrap: anywhere;
}
.fund-record-card__actions {
  display: flex;
  gap: var(--tcrm-space-3);
  margin-top: var(--tcrm-space-3);
}
.fund-record-amount {
  font-variant-numeric: tabular-nums;
}
.fund-record-cards__title {
  margin: 0;
}
.empty-state {
  padding: var(--tcrm-space-6);
  text-align: center;
  color: var(--tcrm-color-text-secondary);
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
.fund-records-pager {
  display: none;
}
@media (max-width: 1279px) {
  .progress-steps {
    grid-template-columns: minmax(0, 1fr);
  }
  .progress-arrow {
    justify-content: center;
    transform: rotate(90deg);
  }
}
@media (max-width: 767px) {
  .funds-page {
    height: auto;
    min-height: 0;
  }
  .progress-card,
  .action-card {
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
}
</style>
