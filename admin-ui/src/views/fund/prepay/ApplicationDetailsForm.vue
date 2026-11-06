<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import {
  prepayPayoutAccounts,
  savePrepayApplicationDetails,
  type PrepayDetail,
  type PrepayPayoutOption
} from "@/api/commission/prepay";
import { notifyError } from "@/utils/notify";
import { serverFieldErrors } from "@/utils/validation";
import { validPrepayFiles } from "@/utils/prepay";
import { formatAmountWithCurrency } from "@/utils/format";
const props = defineProps<{
  detail: PrepayDetail;
  editable: boolean;
  recipientText: string | null;
  recipientFailed?: boolean;
  disabled?: boolean;
}>();
const emit = defineEmits<{
  saved: [detail: PrepayDetail];
  busy: [value: boolean];
  download: [id: string, name: string];
  retryRecipient: [];
}>();
const { t } = useI18n();
const formRef = ref<FormInstance>();
const form = reactive<{
  reason: string;
  expectedPayDate: string;
  payoutAccountId: string;
  deductFromCommission: boolean | null;
}>({ reason: "", expectedPayDate: "", payoutAccountId: "", deductFromCommission: null });
const accounts = ref<PrepayPayoutOption[]>([]);
const accountsLoading = ref(false);
const accountsFailed = ref(false);
const saving = ref(false);
const files = ref<File[]>([]);
const fileError = ref(false);
const errors = ref<Record<string, string>>({});
const textRules = () => [
  { required: true, whitespace: true, message: t("prepay.required"), trigger: "blur" },
  {
    validator: (_rule: unknown, value: string, done: (error?: Error) => void) =>
      done(value.length <= 1000 ? undefined : new Error(t("prepay.tooLong", { max: 1000 }))),
    trigger: "blur"
  }
];
const rules = computed<FormRules>(() => ({
  deductFromCommission: [
    {
      required: true,
      validator: (_rule, value: unknown, done) =>
        done(typeof value === "boolean" ? undefined : new Error(t("prepay.chooseDeduction"))),
      trigger: "change"
    }
  ],
  reason: textRules(),
  payoutAccountId: [
    {
      required: true,
      validator: (_rule, value: unknown, done) =>
        done(typeof value === "string" && value ? undefined : new Error(t("prepay.chooseAccount"))),
      trigger: "change"
    }
  ],
  expectedPayDate: [{ required: true, message: t("prepay.required"), trigger: "change" }]
}));
watch(
  () => props.detail,
  detail => {
    Object.assign(form, {
      reason: detail.reason ?? "",
      expectedPayDate: detail.expectedPayDate ?? "",
      payoutAccountId: detail.payoutAccountId ?? "",
      deductFromCommission: detail.deductFromCommission ?? null
    });
    files.value = [];
    fileError.value = false;
    errors.value = {};
  },
  { immediate: true }
);
async function loadAccounts(kolId: string) {
  accountsLoading.value = true;
  accountsFailed.value = false;
  try {
    accounts.value = await prepayPayoutAccounts(kolId);
  } catch (error) {
    accounts.value = [];
    accountsFailed.value = true;
    notifyError(error);
  } finally {
    accountsLoading.value = false;
  }
}
watch(
  () => [props.detail.kolId, props.editable] as const,
  ([kolId, editable]) => {
    accounts.value = [];
    accountsFailed.value = false;
    if (editable && kolId) void loadAccounts(kolId);
  },
  { immediate: true }
);
function accountLabel(account: PrepayPayoutOption): string {
  return [
    t(`payout.typeOption.${account.type}`),
    account.holderName,
    account.maskedAccount,
    account.currency ?? account.network
  ]
    .filter(Boolean)
    .join(" · ");
}
function chooseFiles(event: Event) {
  const selected = Array.from((event.target as HTMLInputElement).files ?? []);
  fileError.value = !validPrepayFiles(selected, Math.max(0, 5 - props.detail.attachments.length));
  files.value = fileError.value ? [] : selected;
}
async function save() {
  if (saving.value || props.disabled || !props.editable || fileError.value) return;
  saving.value = true;
  emit("busy", true);
  try {
    if (
      !(await formRef.value?.validate().catch(() => false)) ||
      typeof form.deductFromCommission !== "boolean"
    )
      return;
    const detail = await savePrepayApplicationDetails(
      props.detail.id,
      {
        reason: form.reason.trim(),
        expectedPayDate: form.expectedPayDate,
        payoutAccountId: form.payoutAccountId,
        deductFromCommission: form.deductFromCommission
      },
      files.value
    );
    ElMessage.success(t("prepay.saved"));
    emit("saved", detail);
    window.dispatchEvent(new Event("prepay-updated"));
  } catch (error) {
    errors.value = serverFieldErrors(error, Object.keys(form));
    notifyError(error);
  } finally {
    saving.value = false;
    emit("busy", false);
  }
}
defineExpose({ save });
</script>
<template>
  <section data-test="prepay-application-details">
    <el-form
      ref="formRef"
      :model="form"
      :rules="editable ? rules : {}"
      label-position="top"
      :disabled="saving || disabled"
      :class="['application-grid', { 'application-grid--readonly': !editable }]"
    >
      <el-form-item :label="t('prepay.amount')"
        ><div class="read-value amount">
          {{ formatAmountWithCurrency(detail.amountCny, detail.currency) }}
        </div></el-form-item
      >
      <el-form-item
        prop="expectedPayDate"
        :label="t('prepay.expectedDate')"
        :error="errors.expectedPayDate"
      >
        <el-date-picker
          v-if="editable"
          v-model="form.expectedPayDate"
          type="date"
          value-format="YYYY-MM-DD"
          data-test="finance-prepay-date"
        />
        <div v-else class="read-value">{{ detail.expectedPayDate || "—" }}</div>
      </el-form-item>
      <el-form-item prop="reason" :label="t('prepay.reason')" :error="errors.reason">
        <el-input
          v-if="editable"
          v-model="form.reason"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          :placeholder="t('prepay.reasonHint')"
          data-test="finance-prepay-reason"
        />
        <div v-else class="read-value">{{ detail.reason || "—" }}</div>
      </el-form-item>
      <el-form-item
        prop="payoutAccountId"
        :label="t('prepay.account')"
        :error="errors.payoutAccountId"
      >
        <div v-if="editable" class="account-picker">
          <el-select
            v-model="form.payoutAccountId"
            :placeholder="t('prepay.accountPlaceholder')"
            :loading="accountsLoading"
            filterable
            data-test="finance-prepay-account"
          >
            <el-option
              v-for="account in accounts"
              :key="account.id"
              :value="account.id"
              :label="accountLabel(account)"
            />
          </el-select>
          <p
            v-if="!accountsLoading && accounts.length === 0"
            class="account-empty"
            data-test="prepay-account-empty"
          >
            {{ accountsFailed ? t("prepay.accountsFailed") : t("prepay.noAccounts") }}
            <el-button
              link
              type="primary"
              :loading="accountsLoading"
              data-test="prepay-reload-accounts"
              @click="loadAccounts(detail.kolId)"
              >{{ t("prepay.reloadAccounts") }}</el-button
            >
          </p>
        </div>
        <div v-else class="read-value" data-test="prepay-recipient-value">
          {{ recipientText ?? "—" }}
        </div>
        <el-button
          v-if="!editable && recipientFailed"
          link
          type="primary"
          @click="emit('retryRecipient')"
          >{{ t("prepay.retryRecipient") }}</el-button
        >
      </el-form-item>
      <el-form-item
        prop="deductFromCommission"
        :label="t('prepay.deduct')"
        :error="errors.deductFromCommission"
      >
        <el-radio-group
          v-if="editable"
          v-model="form.deductFromCommission"
          data-test="finance-prepay-deduct"
        >
          <el-radio :value="true">{{ t("prepay.yes") }}</el-radio
          ><el-radio :value="false">{{ t("prepay.no") }}</el-radio>
        </el-radio-group>
        <div v-else class="read-value">
          {{
            detail.deductFromCommission == null
              ? "—"
              : t(detail.deductFromCommission ? "prepay.yes" : "prepay.no")
          }}
        </div>
      </el-form-item>
      <el-form-item
        :label="t('prepay.attachments')"
        :error="fileError ? t('prepay.invalidFiles') : ''"
      >
        <div>
          <el-button
            v-for="file in detail.attachments"
            :key="file.id"
            link
            data-test="prepay-download-attachment"
            @click="emit('download', file.id, file.name)"
            >{{ file.name }}</el-button
          >
          <template v-if="editable">
            <input
              type="file"
              multiple
              accept="image/png,image/jpeg,application/pdf"
              :disabled="saving || disabled"
              data-test="finance-prepay-attachments"
              @change="chooseFiles"
            />
            <p class="file-hint">{{ t("prepay.fileHint") }}</p>
          </template>
          <span v-else-if="!detail.attachments.length">—</span>
        </div>
      </el-form-item>
    </el-form>
  </section>
</template>
<style scoped>
.application-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 0 24px;
}
.application-grid--readonly {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}
.application-grid :deep(.el-form-item__content) {
  align-items: flex-start;
}
.application-grid :deep(.el-date-editor) {
  width: 100%;
}
.read-value {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  min-height: 32px;
  color: var(--el-text-color-primary);
}
.amount {
  font-size: var(--tcrm-font-size-title);
  font-weight: 600;
}
.account-picker {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: var(--tcrm-space-2);
}
.account-picker :deep(.el-select) {
  width: 100%;
}
.account-empty {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: 1.5;
}
.file-hint {
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: 1.5;
}
@media (max-width: 640px) {
  .application-grid,
  .application-grid--readonly {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
