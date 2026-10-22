<script setup lang="ts">
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
import CopyText from "@/components/CopyText.vue";
import ApplicationDetailsForm from "./ApplicationDetailsForm.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { saveBlob } from "@/utils/download";
import { notifyError } from "@/utils/notify";
import { ApiError } from "@/utils/http/error";
import { serverFieldErrors } from "@/utils/validation";
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
const detail = ref<PrepayDetail | null>(null);
const loading = ref(false);
const saving = ref(false);
const preparing = ref(false);
const mode = ref("");
const rejectReason = ref("");
const recipient = ref<string | null>(null);
const recipientBusy = ref(false);
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
function detailsSaved(value: PrepayDetail) {
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
    recipient.value = null;
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
  loading.value = true;
  try {
    const data = await getPrepay(id);
    if (props.modelValue && props.id === id) {
      detail.value = data;
      uncertain.value = false;
      return data;
    }
  } catch (error) {
    notifyError(error);
    detail.value = null;
  } finally {
    loading.value = false;
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
  if (!detail.value || !abilities.value.pay || recipientBusy.value) return;
  const id = detail.value.id;
  recipientBusy.value = true;
  try {
    const data = await prepayRecipient(id);
    if (props.modelValue && props.id === id) recipient.value = data.account;
  } catch (error) {
    notifyError(error);
  } finally {
    recipientBusy.value = false;
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
  if (!row || saving.value || row.status !== "PENDING_APPROVAL") return;
  if (action === "CANCEL" ? !canCancelPrepay(me.user, row) : !abilities.value.review) return;
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
        requestId: crypto.randomUUID()
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
    recipient.value = null;
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
  <el-drawer
    :model-value="modelValue"
    :title="t('prepay.title')"
    size="min(760px, 95vw)"
    :close-on-click-modal="false"
    :before-close="beforeClose"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="prepay-detail" data-test="prepay-detail">
      <el-button :disabled="saving" data-test="prepay-detail-refresh" @click="load">{{
        t("prepay.refresh")
      }}</el-button>
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('prepay.number')"
            ><CopyText :value="detail.prepayNo"
          /></el-descriptions-item>
          <el-descriptions-item :label="t('prepay.task')"
            >{{ t("prepay.type") }} · {{ detail.financeTaskId }}</el-descriptions-item
          >
          <el-descriptions-item :label="t('prepay.kol')"
            >{{ detail.kolCode }} · {{ detail.kolName }}</el-descriptions-item
          >
          <el-descriptions-item :label="t('prepay.applicant')">{{
            detail.applicantId
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('prepay.createdAt')">{{
            formatInstant(detail.createdAt)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('prepay.amount')"
            >{{ detail.amountCny }} CNY</el-descriptions-item
          >
          <el-descriptions-item :label="t('common.status')">{{
            t(`prepay.status.${detail.status}`)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('prepay.reason')">{{
            detail.reason
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('prepay.expectedDate')">{{
            detail.expectedPayDate
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('prepay.account')">{{
            [
              detail.payoutAccountSnapshot?.type,
              detail.payoutAccountSnapshot?.holderName,
              detail.payoutAccountSnapshot?.currency,
              detail.payoutAccountSnapshot?.network,
              detail.payoutAccountSnapshot?.maskedAccount
            ]
              .filter(Boolean)
              .join(" · ")
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('prepay.deduct')">{{
            t(
              detail.deductFromCommission == null
                ? "prepay.toComplete"
                : detail.deductFromCommission
                  ? "prepay.yes"
                  : "prepay.no"
            )
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('prepay.attachments')"
            ><el-button
              v-for="file in detail.attachments"
              :key="file.id"
              link
              data-test="prepay-download-attachment"
              @click="download(file.id, file.name)"
              >{{ file.name }}</el-button
            ></el-descriptions-item
          >
        </el-descriptions>
        <section v-if="detail.approval">
          <h3>{{ t("prepay.approval") }}</h3>
          <p>
            {{ t(`prepay.decision.${detail.approval.decision}`) }} ·
            {{ detail.approval.operatorId }} · {{ formatInstant(detail.approval.time) }}
          </p>
          <p>{{ detail.approval.reason }}</p>
        </section>
        <section v-if="detail.payment">
          <h3>{{ t("prepay.payment") }}</h3>
          <p>
            {{ detail.payment.payAmountCny }} CNY · {{ detail.payment.payMethod }} ·
            {{ formatInstant(detail.payment.payTime) }} · {{ detail.payment.operatorId }}
          </p>
          <p>{{ detail.payment.payRemark }}</p>
          <el-button
            data-test="prepay-download-voucher"
            @click="download(detail.payment.voucherFileId)"
            >{{ t("prepay.voucher") }}</el-button
          >
        </section>
        <section>
          <h3>{{ t("prepay.balance") }}</h3>
          <p data-test="prepay-wallet">{{ detail.walletBalanceCny }} CNY</p>
          <p v-if="detail.deduction">
            {{ t("prepay.posted") }} {{ detail.deduction.postedAmountCny }} CNY ·
            {{ t("prepay.recovered") }} {{ detail.deduction.recoveredAmountCny }} CNY ·
            {{ t("prepay.remaining") }} {{ detail.deduction.remainingAmountCny }} CNY
          </p>
        </section>
        <section>
          <h3>{{ t("prepay.timeline") }}</h3>
          <ol>
            <li v-for="(item, index) in detail.timeline" :key="index">
              {{ t(`prepay.action.${item.action}`) }} · {{ item.operatorId }} ·
              {{ formatInstant(item.time) }}
              <p>{{ item.reason }}</p>
            </li>
          </ol>
        </section>
        <ApplicationDetailsForm
          v-if="detail.status === 'PENDING_APPROVAL' && (abilities.review || abilities.pay)"
          :detail="detail"
          :disabled="saving || preparing"
          @busy="detailsBusy = $event"
          @saved="detailsSaved"
        />
        <el-alert
          v-if="detail.status === 'PENDING_APPROVAL' && !detailsComplete"
          :title="t('prepay.detailsRequired')"
          type="warning"
          :closable="false"
        />
        <div v-if="detail.status === 'PENDING_APPROVAL'" class="prepay-actions">
          <el-button
            v-if="abilities.review"
            :disabled="saving || detailsBusy || !detailsComplete"
            data-test="prepay-approve"
            @click="changeState('APPROVE')"
            >{{ t("prepay.approve") }}</el-button
          >
          <el-button
            v-if="abilities.review"
            :disabled="saving"
            data-test="prepay-reject"
            @click="mode = 'reject'"
            >{{ t("prepay.reject") }}</el-button
          >
          <el-button
            v-if="canCancelPrepay(me.user, detail)"
            :disabled="saving"
            data-test="prepay-cancel"
            @click="changeState('CANCEL')"
            >{{ t("prepay.cancel") }}</el-button
          >
        </div>
        <section
          v-if="mode === 'reject' && detail.status === 'PENDING_APPROVAL' && abilities.review"
        >
          <el-input
            v-model="rejectReason"
            type="textarea"
            :aria-label="t('prepay.rejectReason')"
            data-test="prepay-reject-reason"
          /><el-button
            :loading="saving"
            data-test="prepay-reject-submit"
            @click="changeState('REJECT')"
            >{{ t("prepay.reject") }}</el-button
          >
        </section>
        <section v-if="detail.status === 'APPROVED' && abilities.pay">
          <el-alert :title="t('prepay.offlineHint')" type="warning" :closable="false" />
          <el-button
            :loading="recipientBusy"
            data-test="prepay-recipient"
            @click="revealRecipient"
            >{{ t("prepay.revealRecipient") }}</el-button
          >
          <p v-if="recipient" data-test="prepay-recipient-value">
            {{ recipient }} <el-button @click="recipient = null">{{ t("prepay.hide") }}</el-button>
          </p>
          <el-alert
            v-if="locked"
            :title="t('prepay.retryPayment')"
            type="warning"
            :closable="false"
          />
          <el-form
            ref="formRef"
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
            <el-form-item prop="payMethod" :label="t('prepay.payMethod')" :error="errors.payMethod"
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
          <el-button
            type="primary"
            :loading="saving || preparing"
            :disabled="uncertain"
            data-test="prepay-pay-submit"
            @click="registerPayment"
            >{{ t("prepay.registerPayment") }}</el-button
          >
        </section>
      </template>
    </div>
  </el-drawer>
</template>
<style scoped>
.prepay-detail {
  overflow-wrap: anywhere;
}
.prepay-detail section {
  margin-top: var(--tcrm-space-4);
}
.prepay-detail h3 {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
}
.prepay-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
  margin-top: var(--tcrm-space-4);
}
</style>
