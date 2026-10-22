<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import {
  applyPrepay,
  prepayAccounts,
  type ApplyPrepay,
  type PrepayAccount
} from "@/api/commission/prepay";
import { useUserStore } from "@/stores/user";
import { notifyError } from "@/utils/notify";
import { serverFieldErrors } from "@/utils/validation";
import { ApiError } from "@/utils/http/error";
import { prepayAbilities, validCny, validPrepayFiles } from "@/utils/prepay";

const props = defineProps<{
  modelValue: boolean;
  kol: { id: string; kolCode: string; name: string | null } | null;
}>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; saved: [] }>();
const { t } = useI18n();
const me = useUserStore();
const formRef = ref<FormInstance>();
const loading = ref(false);
const saving = ref(false);
const preparing = ref(false);
const accountsFailed = ref(false);
const accounts = ref<PrepayAccount[]>([]);
const files = ref<File[]>([]);
const fileError = ref(false);
const errors = ref<Record<string, string>>({});
const form = reactive({
  amountCny: "",
  reason: "",
  expectedPayDate: "",
  payoutAccountId: "",
  deductFromCommission: true
});
// The immutable attempt is retained after an ambiguous response; editing requires explicit abandonment.
let attempt: { payload: ApplyPrepay; files: File[] } | null = null;
const locked = ref(false);
function beforeClose(done: () => void) {
  if (!saving.value && !preparing.value) done();
}
const rules = computed<FormRules>(() => ({
  amountCny: [
    {
      validator: (_rule, value: string, done) =>
        done(validCny(value) ? undefined : new Error(t("prepay.invalidAmount"))),
      trigger: "blur"
    }
  ],
  reason: [{ required: true, whitespace: true, message: t("prepay.required"), trigger: "blur" }],
  expectedPayDate: [{ required: true, message: t("prepay.required"), trigger: "change" }],
  payoutAccountId: [{ required: true, message: t("prepay.required"), trigger: "change" }]
}));
let accountSequence = 0;
watch(
  () => [props.modelValue, props.kol?.id] as const,
  async ([open]) => {
    if (!open || !props.kol) return;
    Object.assign(form, {
      amountCny: "",
      reason: "",
      expectedPayDate: "",
      payoutAccountId: "",
      deductFromCommission: true
    });
    attempt = null;
    locked.value = false;
    files.value = [];
    fileError.value = false;
    errors.value = {};
    accounts.value = [];
    formRef.value?.clearValidate();
    await loadAccounts();
  },
  { immediate: true }
);
async function loadAccounts() {
  if (!props.kol) return;
  const id = props.kol.id;
  const request = ++accountSequence;
  loading.value = true;
  accountsFailed.value = false;
  try {
    const data = await prepayAccounts(id);
    if (props.modelValue && props.kol?.id === id && request === accountSequence)
      accounts.value = data;
  } catch (error) {
    if (request === accountSequence) {
      accountsFailed.value = true;
      notifyError(error);
    }
  } finally {
    if (request === accountSequence) loading.value = false;
  }
}
function chooseFiles(event: Event) {
  const chosen = Array.from((event.target as HTMLInputElement).files ?? []);
  fileError.value = !validPrepayFiles(chosen);
  files.value = fileError.value ? [] : chosen;
}
async function save() {
  if (preparing.value || saving.value) return;
  preparing.value = true;
  try {
    await submitApplication();
  } finally {
    preparing.value = false;
  }
}
async function submitApplication() {
  if (
    saving.value ||
    !props.kol ||
    !prepayAbilities(me.user).apply ||
    loading.value ||
    !accounts.value.length ||
    fileError.value
  )
    return;
  if (!(await formRef.value?.validate().catch(() => false))) return;
  if (!attempt)
    attempt = {
      payload: {
        ...form,
        reason: form.reason.trim(),
        requestId: crypto.randomUUID(),
        kolId: props.kol.id
      },
      files: [...files.value]
    };
  saving.value = true;
  locked.value = true;
  try {
    await applyPrepay(attempt.payload, attempt.files);
    window.dispatchEvent(new Event("prepay-updated"));
    ElMessage.success(t("prepay.saved"));
    emit("saved");
    emit("update:modelValue", false);
  } catch (error) {
    errors.value = serverFieldErrors(error, Object.keys(form));
    notifyError(error);
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
    :title="t('kol.actionPrepay')"
    width="min(640px, 95vw)"
    :close-on-click-modal="false"
    :before-close="beforeClose"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" data-test="prepay-apply">
      <p>{{ kol?.kolCode }} · {{ kol?.name }} · {{ me.user?.nickname }}</p>
      <el-alert
        v-if="!loading && !accounts.length && !accountsFailed"
        :title="t('prepay.noAccounts')"
        type="warning"
        :closable="false"
        data-test="prepay-no-accounts"
      />
      <el-button v-if="!accounts.length" data-test="prepay-reload-accounts" @click="loadAccounts">{{
        t("prepay.reloadAccounts")
      }}</el-button>
      <el-alert v-if="locked" :title="t('prepay.retrySame')" :closable="false" type="warning" />
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        :disabled="saving || preparing || locked"
      >
        <el-form-item prop="amountCny" :label="t('prepay.amount')" :error="errors.amountCny"
          ><el-input v-model="form.amountCny" inputmode="decimal" data-test="prepay-amount"
        /></el-form-item>
        <el-form-item prop="reason" :label="t('prepay.reason')" :error="errors.reason"
          ><el-input
            v-model="form.reason"
            type="textarea"
            maxlength="2000"
            data-test="prepay-reason"
        /></el-form-item>
        <el-form-item
          prop="expectedPayDate"
          :label="t('prepay.expectedDate')"
          :error="errors.expectedPayDate"
          ><el-date-picker
            v-model="form.expectedPayDate"
            type="date"
            value-format="YYYY-MM-DD"
            data-test="prepay-date"
        /></el-form-item>
        <el-form-item
          prop="payoutAccountId"
          :label="t('prepay.account')"
          :error="errors.payoutAccountId"
          ><el-select v-model="form.payoutAccountId" data-test="prepay-account"
            ><el-option
              v-for="account in accounts"
              :key="account.id"
              :value="account.id"
              :label="
                [
                  account.type,
                  account.currency,
                  account.network,
                  account.maskedAccount,
                  account.holderName
                ]
                  .filter(Boolean)
                  .join(' · ')
              " /></el-select
        ></el-form-item>
        <el-form-item :label="t('prepay.deduct')"
          ><el-switch v-model="form.deductFromCommission" data-test="prepay-deduct"
        /></el-form-item>
        <el-form-item
          :label="t('prepay.attachments')"
          :error="fileError ? t('prepay.invalidFiles') : ''"
          ><input
            type="file"
            accept="image/png,image/jpeg,application/pdf"
            multiple
            :disabled="saving || preparing || locked"
            data-test="prepay-attachments"
            @change="chooseFiles"
        /></el-form-item>
        <p>{{ t("prepay.fileHint") }}</p>
      </el-form>
    </div>
    <template #footer
      ><el-button
        :disabled="saving || preparing"
        data-test="prepay-abandon"
        @click="emit('update:modelValue', false)"
        >{{ t("prepay.close") }}</el-button
      ><el-button
        type="primary"
        :loading="saving || preparing"
        :disabled="loading || !accounts.length || fileError || !prepayAbilities(me.user).apply"
        data-test="prepay-submit"
        @click="save"
        >{{ t("prepay.submit") }}</el-button
      ></template
    >
  </el-dialog>
</template>
