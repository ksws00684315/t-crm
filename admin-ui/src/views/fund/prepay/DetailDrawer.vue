<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import {
  approvePrepay,
  cancelPrepay,
  downloadPrepayFile,
  getPrepay,
  payPrepay,
  prepayRecipient,
  type PayPrepay,
  type PrepayDetail
} from "@/api/commission/prepay";
import PrepayStatusTag from "./PrepayStatusTag.vue";
import CopyText from "@/components/CopyText.vue";
import ApplicationDetailsForm from "./ApplicationDetailsForm.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { saveBlob } from "@/utils/download";
import { notifyError } from "@/utils/notify";
import { ApiError } from "@/utils/http/error";
import { serverFieldErrors } from "@/utils/validation";
import { randomUuid } from "@/utils/uuid";
import {
  canCancelPrepay,
  prepayAbilities,
  sameCny,
  validCny,
  validPrepayFiles
} from "@/utils/prepay";

const props = defineProps<{ modelValue: boolean; id: string | null; initialAction?: string }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; saved: [] }>();
const { t } = useI18n();
const me = useUserStore();
const abilities = computed(() => prepayAbilities(me.user));
const canApprove = computed(
  () =>
    detail.value?.applicantId !== me.user?.id &&
    (detail.value?.status === "PENDING_OPERATIONS_REVIEW"
      ? abilities.value.operationsReview
      : detail.value?.status === "PENDING_FINANCE_REVIEW" && abilities.value.financeReview)
);
const detail = ref<PrepayDetail | null>(null);
const loading = ref(false);
const saving = ref(false);
const preparing = ref(false);
const mode = ref("");
const rejectReason = ref("");
const recipient = ref<string | null>(null);
const recipientBusy = ref(false);
let loadSequence = 0;
let recipientSequence = 0;
let dialogSequence = 0;
const recipientFailed = ref(false);
const applicationRef = ref<InstanceType<typeof ApplicationDetailsForm>>();
const activeTab = ref("application");
const editable = computed(
  () =>
    detail.value?.status === "WAITING_DETAILS" &&
    detail.value.applicantId === me.user?.id &&
    abilities.value.apply
);
const paymentStage = computed(
  () => detail.value?.status === "APPROVED" || detail.value?.status === "PAID"
);
const stages = [
  "WAITING_DETAILS",
  "PENDING_OPERATIONS_REVIEW",
  "PENDING_FINANCE_REVIEW",
  "APPROVED",
  "PAID"
];
const progress = computed(() => stages.indexOf(detail.value?.status ?? ""));
function person(name?: string | null, role?: string | null) {
  if (role === "SYSTEM") return t("prepay.systemPerson");
  const label = name?.trim() || t("prepay.historicalPerson");
  return `${label} · ${t(`prepay.actorRole.${role && ["BUSINESS", "OPERATIONS_REVIEW", "FINANCE_REVIEW", "PAYMENT"].includes(role) ? role : "HISTORICAL"}`)}`;
}
const formRef = ref<FormInstance>();
const form = reactive({ payAmountCny: "", payTime: "", payMethod: "", payRemark: "" });
const voucher = ref<File | null>(null);
const fileError = ref(false);
const errors = ref<Record<string, string>>({});
const locked = ref(false);
const uncertain = ref(false);
const detailsBusy = ref(false);
const detailsComplete = computed(
  () =>
    typeof detail.value?.deductFromCommission === "boolean" &&
    !!detail.value?.reason?.trim() &&
    !!detail.value.expectedPayDate &&
    !!detail.value.payoutAccountSnapshot
);
function detailsSaved(value: PrepayDetail, text: string) {
  recipient.value = text;
  detail.value = value;
  emit("saved");
}
function beforeClose(done: () => void) {
  if (!saving.value && !preparing.value && !detailsBusy.value) done();
}
let attempt: { payload: PayPrepay; voucher: File } | null = null;
const rules = computed<FormRules>(() => ({
  payAmountCny: [
    {
      validator: (_rule, value: string, done) =>
        done(validCny(value) ? undefined : new Error(t("prepay.invalidAmount"))),
      trigger: "blur"
    }
  ],
  payTime: [{ required: true, message: t("prepay.required"), trigger: "change" }],
  payMethod: [
    { required: true, whitespace: true, message: t("prepay.required"), trigger: "blur" },
    {
      validator: (_rule, value: string, done) =>
        done(value.trim().length <= 100 ? undefined : new Error(t("prepay.tooLong", { max: 100 }))),
      trigger: "blur"
    }
  ],
  payRemark: [
    {
      validator: (_rule, value: string, done) =>
        done(
          value.trim().length <= 1000 ? undefined : new Error(t("prepay.tooLong", { max: 1000 }))
        ),
      trigger: "blur"
    },
    {
      validator: (_rule, value: string, done) =>
        done(
          detail.value && !sameCny(form.payAmountCny, detail.value.amountCny) && !value.trim()
            ? new Error(t("prepay.differenceRemark"))
            : undefined
        ),
      trigger: "blur"
    }
  ]
}));
watch(
  () => [props.modelValue, props.id] as const,
  async ([open, id]) => {
    const opening = ++dialogSequence;
    ++loadSequence;
    ++recipientSequence;
    recipientBusy.value = false;
    recipient.value = null;
    recipientFailed.value = false;
    activeTab.value = props.initialAction === "pay" ? "payment" : "application";
    if (!open || !id) {
      detail.value = null;
      return;
    }
    detail.value = null;
    attempt = null;
    locked.value = false;
    uncertain.value = false;
    voucher.value = null;
    fileError.value = false;
    rejectReason.value = "";
    errors.value = {};
    mode.value = props.initialAction ?? "";
    const data = await load();
    if (opening !== dialogSequence) return;
    Object.assign(form, {
      payAmountCny: data?.amountCny ?? "",
      payTime: "",
      payMethod: "",
      payRemark: ""
    });
  },
  { immediate: true }
);
async function load() {
  const id = props.id;
  if (!id) return;
  const request = ++loadSequence;
  loading.value = true;
  try {
    const data = await getPrepay(id);
    if (request === loadSequence && props.modelValue && props.id === id) {
      detail.value = data;
      recipient.value = null;
      if (data.payoutAccountSnapshot) await revealRecipient();
      if (request !== loadSequence) return;
      uncertain.value = false;
      return data;
    }
  } catch (error) {
    if (request === loadSequence) {
      notifyError(error);
      detail.value = null;
    }
  } finally {
    if (request === loadSequence) loading.value = false;
  }
}
async function download(id: string, name?: string) {
  try {
    const file = await downloadPrepayFile(id);
    saveBlob(file.blob, name ?? file.fileName ?? "prepay-file");
  } catch (error) {
    notifyError(error);
  }
}
async function revealRecipient() {
  if (!detail.value) return;
  const id = detail.value.id;
  const request = ++recipientSequence;
  recipientBusy.value = true;
  try {
    const data = await prepayRecipient(id);
    if (request === recipientSequence && props.modelValue && props.id === id) {
      recipient.value =
        data.type === "MANUAL"
          ? data.account
          : [data.type, data.holderName, data.account, data.currency, data.network]
              .filter(Boolean)
              .join("\n");
      recipientFailed.value = false;
    }
  } catch (error) {
    if (request === recipientSequence && props.modelValue && props.id === id) {
      recipientFailed.value = true;
      notifyError(error);
    }
  } finally {
    if (request === recipientSequence) recipientBusy.value = false;
  }
}
async function changeState(action: "APPROVE" | "REJECT" | "CANCEL") {
  if (preparing.value || saving.value || detailsBusy.value) return;
  if (action === "APPROVE" && !detailsComplete.value) {
    ElMessage.warning(t("prepay.detailsRequired"));
    return;
  }
  preparing.value = true;
  try {
    await submitState(action);
  } finally {
    preparing.value = false;
  }
}
async function submitState(action: "APPROVE" | "REJECT" | "CANCEL") {
  const row = detail.value;
  if (!row || saving.value) return;
  if (action === "CANCEL" ? !canCancelPrepay(me.user, row) : !canApprove.value) return;
  if (action === "REJECT" && !rejectReason.value.trim()) {
    ElMessage.warning(t("prepay.required"));
    return;
  }
  try {
    await ElMessageBox.confirm(t(`prepay.confirm${action}`), t("prepay.title"), {
      type: "warning"
    });
  } catch {
    return;
  }
  saving.value = true;
  try {
    detail.value =
      action === "CANCEL"
        ? await cancelPrepay(row.id)
        : await approvePrepay(row.id, action, rejectReason.value.trim() || undefined);
    mode.value = "";
    emit("saved");
    window.dispatchEvent(new Event("prepay-updated"));
  } catch (error) {
    notifyError(error);
    await load();
  } finally {
    saving.value = false;
  }
}
function chooseVoucher(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? []);
  fileError.value = files.length !== 1 || !validPrepayFiles(files, 1);
  voucher.value = fileError.value ? null : files[0];
}
async function registerPayment() {
  if (preparing.value || saving.value) return;
  preparing.value = true;
  try {
    await submitPayment();
  } finally {
    preparing.value = false;
  }
}
async function submitPayment() {
  const row = detail.value;
  if (!row || row.status !== "APPROVED" || !abilities.value.pay || saving.value || uncertain.value)
    return;
  if (!(await formRef.value?.validate().catch(() => false))) return;
  if (!voucher.value || fileError.value) {
    fileError.value = true;
    return;
  }
  if (!attempt) {
    if (!sameCny(form.payAmountCny, row.amountCny)) {
      try {
        await ElMessageBox.confirm(t("prepay.confirmDifference"), t("prepay.title"), {
          type: "warning"
        });
      } catch {
        return;
      }
    }
    attempt = {
      payload: {
        ...form,
        payMethod: form.payMethod.trim(),
        payTime: new Date(form.payTime).toISOString(),
        requestId: randomUuid()
      },
      voucher: voucher.value
    };
  }
  saving.value = true;
  locked.value = true;
  try {
    detail.value = await payPrepay(row.id, attempt.payload, attempt.voucher);
    window.dispatchEvent(new Event("prepay-updated"));
    mode.value = "";
    emit("saved");
    ElMessage.success(t("prepay.saved"));
  } catch (error) {
    errors.value = serverFieldErrors(error, Object.keys(form));
    notifyError(error);
    // Refresh every failed registration; never suggest making another offline transfer.
    uncertain.value = true;
    try {
      detail.value = await getPrepay(row.id);
      uncertain.value = false;
      emit("saved");
    } catch (refreshError) {
      detail.value = null;
      notifyError(refreshError);
    }
    if (error instanceof ApiError && error.status === 400) {
      attempt = null;
      locked.value = false;
    }
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('prepay.title')"
    width="860px"
    align-center
    class="prepay-dialog"
    :close-on-click-modal="false"
    :before-close="beforeClose"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <div v-if="detail" class="dialog-heading">
        <div class="identity">
          <NameCode :name="detail.kolName" :code="detail.kolCode" /><PrepayStatusTag
            :status="detail.status"
          />
        </div>
        <div class="metadata">
          <span>{{ t("prepay.number") }}：<CopyText :value="detail.prepayNo" /></span>
        </div>
        <div class="metadata">
          <span>{{ t("prepay.applicant") }}：{{ person(detail.applicantName, "BUSINESS") }}</span
          ><span>{{ formatInstant(detail.createdAt) }}</span>
        </div>
      </div>
      <span v-else>{{ t("prepay.title") }}</span>
    </template>
    <div v-loading="loading || recipientBusy" class="prepay-detail" data-test="prepay-detail">
      <template v-if="detail">
        <ol class="progress" :aria-label="t('prepay.statusLabel')">
          <li
            v-for="(stage, index) in stages"
            :key="stage"
            :class="{ current: index === progress, complete: index < progress }"
          >
            <span class="step-dot">{{ index + 1 }}</span
            ><span>{{ t(`prepay.status.${stage}`) }}</span>
          </li>
        </ol>
        <el-tabs v-model="activeTab">
          <el-tab-pane :label="t('prepay.applicationInfo')" name="application">
            <ApplicationDetailsForm
              ref="applicationRef"
              :detail="detail"
              :editable="editable"
              :recipient-text="recipient"
              :recipient-failed="recipientFailed"
              :disabled="saving || preparing || recipientBusy"
              @busy="detailsBusy = $event"
              @saved="detailsSaved"
              @download="download"
              @retry-recipient="revealRecipient"
            />
            <p
              v-if="!editable && !['REJECTED', 'CANCELLED', 'PAID'].includes(detail.status)"
              class="locked-hint"
            >
              {{ t("prepay.lockedInfo") }}
            </p>
          </el-tab-pane>
          <el-tab-pane :label="t('prepay.flowRecords')" name="history">
            <el-empty
              v-if="!detail.timeline.length"
              :description="t('prepay.noRecords')"
              :image-size="64"
            />
            <el-timeline v-else class="flow-history">
              <el-timeline-item
                v-for="(item, index) in detail.timeline"
                :key="index"
                :timestamp="formatInstant(item.time)"
              >
                <strong>{{ t(`prepay.action.${item.action}`) }}</strong
                ><span class="history-person">{{
                  person(item.operatorName, item.operatorRole)
                }}</span>
                <p v-if="item.reason" class="record-reason">{{ item.reason }}</p>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
          <el-tab-pane v-if="paymentStage" :label="t('prepay.paymentInfo')" name="payment">
            <section v-if="detail.payment" class="payment-summary">
              <el-descriptions :column="2" border>
                <el-descriptions-item :label="t('prepay.payAmount')"
                  >{{ detail.payment.payAmountCny }} CNY</el-descriptions-item
                >
                <el-descriptions-item :label="t('prepay.payTime')">{{
                  formatInstant(detail.payment.payTime)
                }}</el-descriptions-item>
                <el-descriptions-item :label="t('prepay.payMethod')">{{
                  detail.payment.payMethod
                }}</el-descriptions-item>
                <el-descriptions-item :label="t('prepay.actorRole.PAYMENT')">{{
                  person(detail.payment.operatorName, detail.payment.operatorRole || "PAYMENT")
                }}</el-descriptions-item>
                <el-descriptions-item :label="t('prepay.payRemark')" :span="2"
                  ><span class="record-reason">{{
                    detail.payment.payRemark || "—"
                  }}</span></el-descriptions-item
                >
                <el-descriptions-item :label="t('prepay.voucher')" :span="2"
                  ><el-button
                    data-test="prepay-download-voucher"
                    link
                    @click="download(detail.payment.voucherFileId)"
                    >{{ t("prepay.voucher") }}</el-button
                  ></el-descriptions-item
                >
              </el-descriptions>
              <p>
                {{ t("prepay.balance") }}：<span data-test="prepay-wallet"
                  >{{ detail.walletBalanceCny }} CNY</span
                >
              </p>
              <p v-if="detail.deduction">
                {{ t("prepay.posted") }} {{ detail.deduction.postedAmountCny }} CNY ·
                {{ t("prepay.recovered") }} {{ detail.deduction.recoveredAmountCny }} CNY ·
                {{ t("prepay.remaining") }} {{ detail.deduction.remainingAmountCny }} CNY
              </p>
            </section>
            <section v-else-if="detail.status === 'APPROVED' && abilities.pay">
              <el-alert :title="t('prepay.offlineHint')" type="info" :closable="false" />
              <el-alert
                v-if="locked"
                :title="t('prepay.retryPayment')"
                type="warning"
                :closable="false"
              />
              <el-form
                ref="formRef"
                class="payment-grid"
                :model="form"
                :rules="rules"
                label-position="top"
                :disabled="saving || preparing || locked"
              >
                <el-form-item
                  prop="payAmountCny"
                  :label="t('prepay.payAmount')"
                  :error="errors.payAmountCny"
                  ><el-input
                    v-model="form.payAmountCny"
                    inputmode="decimal"
                    data-test="prepay-pay-amount"
                /></el-form-item>
                <el-form-item prop="payTime" :label="t('prepay.payTime')" :error="errors.payTime"
                  ><el-date-picker
                    v-model="form.payTime"
                    type="datetime"
                    value-format="YYYY-MM-DDTHH:mm:ssZ"
                    data-test="prepay-pay-time"
                /></el-form-item>
                <el-form-item
                  prop="payMethod"
                  :label="t('prepay.payMethod')"
                  :error="errors.payMethod"
                  ><el-input v-model="form.payMethod" maxlength="100" data-test="prepay-pay-method"
                /></el-form-item>
                <el-form-item
                  prop="payRemark"
                  data-test="prepay-pay-remark-field"
                  :label="t('prepay.payRemark')"
                  :error="errors.payRemark"
                  ><el-input
                    v-model="form.payRemark"
                    type="textarea"
                    maxlength="1000"
                    data-test="prepay-pay-remark"
                /></el-form-item>
                <el-form-item
                  :label="t('prepay.voucher')"
                  :error="fileError ? t('prepay.invalidFiles') : ''"
                  ><input
                    type="file"
                    accept="image/png,image/jpeg,application/pdf"
                    :disabled="saving || preparing || locked"
                    data-test="prepay-voucher"
                    @change="chooseVoucher"
                /></el-form-item>
              </el-form>
            </section>
          </el-tab-pane>
        </el-tabs>
        <el-input
          v-if="mode === 'reject' && canApprove"
          v-model="rejectReason"
          type="textarea"
          maxlength="1000"
          show-word-limit
          :placeholder="t('prepay.rejectReason')"
          :aria-label="t('prepay.rejectReason')"
          data-test="prepay-reject-reason"
        />
      </template>
    </div>
    <template #footer>
      <div class="dialog-footer">
        <el-button
          :disabled="saving || detailsBusy || preparing"
          data-test="prepay-detail-refresh"
          @click="load"
          >{{ t("prepay.refresh") }}</el-button
        >
        <div class="footer-actions">
          <el-button
            :disabled="saving || detailsBusy || preparing"
            @click="emit('update:modelValue', false)"
            >{{ t("prepay.close") }}</el-button
          >
          <template v-if="detail">
            <el-button
              v-if="canCancelPrepay(me.user, detail)"
              :disabled="saving || detailsBusy || preparing"
              data-test="prepay-cancel"
              @click="changeState('CANCEL')"
              >{{ t("prepay.cancel") }}</el-button
            >
            <el-button
              v-if="editable"
              type="primary"
              :loading="detailsBusy"
              :disabled="saving || preparing || recipientFailed"
              data-test="finance-prepay-details-save"
              @click="
                activeTab = 'application';
                applicationRef?.save();
              "
              >{{ t("prepay.saveDetails") }}</el-button
            >
            <template v-if="canApprove">
              <el-button
                v-if="mode !== 'reject'"
                type="danger"
                plain
                :disabled="saving || preparing"
                data-test="prepay-reject"
                @click="mode = 'reject'"
                >{{ t("prepay.reject") }}</el-button
              >
              <el-button
                v-if="mode === 'reject'"
                type="danger"
                :loading="saving"
                :disabled="preparing"
                data-test="prepay-reject-submit"
                @click="changeState('REJECT')"
                >{{ t("prepay.reject") }}</el-button
              >
              <el-button
                v-else
                type="primary"
                :disabled="saving || detailsBusy || preparing || !detailsComplete"
                data-test="prepay-approve"
                @click="changeState('APPROVE')"
                >{{ t("prepay.approve") }}</el-button
              >
            </template>
            <el-button
              v-if="detail.status === 'APPROVED' && abilities.pay"
              type="primary"
              :loading="saving || preparing"
              :disabled="uncertain"
              data-test="prepay-pay-submit"
              @click="
                activeTab = 'payment';
                registerPayment();
              "
              >{{ t("prepay.registerPayment") }}</el-button
            >
          </template>
        </div>
      </div>
    </template>
  </el-dialog>
</template>
<style scoped>
.prepay-detail {
  overflow-wrap: anywhere;
}
.identity,
.metadata,
.dialog-footer,
.footer-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 16px;
}
.identity strong {
  font-size: var(--tcrm-font-size-section);
  color: var(--el-text-color-primary);
}
.metadata {
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
  margin-top: 8px;
}
.dialog-footer {
  justify-content: space-between;
}
.footer-actions {
  gap: 8px;
}
.footer-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.progress {
  display: flex;
  list-style: none;
  padding: 0;
  margin: 0 0 12px;
  gap: 8px;
}
.progress li {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--tcrm-font-size-help);
  color: var(--el-text-color-secondary);
}
.step-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--el-fill-color);
}
.progress li.current {
  color: var(--el-color-primary);
  font-weight: 600;
}
.progress li.current .step-dot {
  background: var(--el-color-primary);
  color: white;
}
.progress li.complete .step-dot {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}
.locked-hint {
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
  margin: 0;
}
.flow-history {
  padding: 12px 0 0 8px;
}
.history-person {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
}
.record-reason {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.payment-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 24px;
  margin-top: 16px;
}
.payment-grid :deep(.el-date-editor) {
  width: 100%;
}
@media (max-width: 640px) {
  .payment-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .progress li {
    flex-direction: column;
    text-align: center;
    font-size: var(--tcrm-font-size-help);
  }
  .metadata {
    gap: 4px 12px;
  }
  .dialog-footer,
  .footer-actions {
    gap: 6px;
  }
}
</style>
<style>
.el-dialog.prepay-dialog {
  display: flex;
  flex-direction: column;
  max-height: 90dvh;
  max-width: calc(100vw - 24px);
  border-radius: var(--el-border-radius-base);
}
.prepay-dialog > .el-dialog__body {
  overflow-y: auto;
  min-height: 0;
}
.prepay-dialog > .el-dialog__header {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.prepay-dialog > .el-dialog__footer {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 16px;
}
</style>
