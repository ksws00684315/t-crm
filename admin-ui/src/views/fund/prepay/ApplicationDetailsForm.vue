<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import {
  prepayAccounts,
  savePrepayApplicationDetails,
  type PrepayAccount,
  type PrepayDetail
} from "@/api/commission/prepay";
import { notifyError } from "@/utils/notify";
import { serverFieldErrors } from "@/utils/validation";
import { validPrepayFiles } from "@/utils/prepay";

const props = defineProps<{ detail: PrepayDetail; disabled?: boolean }>();
const emit = defineEmits<{ saved: [detail: PrepayDetail]; busy: [value: boolean] }>();
const { t } = useI18n();
const formRef = ref<FormInstance>();
const form = reactive<{
  reason: string;
  expectedPayDate: string;
  payoutAccountId: string;
  deductFromCommission: boolean | null;
}>({ reason: "", expectedPayDate: "", payoutAccountId: "", deductFromCommission: null });
const accounts = ref<PrepayAccount[]>([]);
const loading = ref(false);
const saving = ref(false);
const files = ref<File[]>([]);
const fileError = ref(false);
const errors = ref<Record<string, string>>({});
let sequence = 0;
const rules = computed<FormRules>(() => ({
  deductFromCommission: [
    {
      required: true,
      validator: (_rule, value: unknown, done) =>
        done(typeof value === "boolean" ? undefined : new Error(t("prepay.chooseDeduction"))),
      trigger: "change"
    }
  ],
  reason: [{ required: true, whitespace: true, message: t("prepay.required"), trigger: "blur" }],
  expectedPayDate: [{ required: true, message: t("prepay.required"), trigger: "change" }],
  payoutAccountId: [{ required: true, message: t("prepay.required"), trigger: "change" }]
}));
watch(
  () => props.detail,
  async detail => {
    Object.assign(form, {
      reason: detail.reason ?? "",
      expectedPayDate: detail.expectedPayDate ?? "",
      payoutAccountId: detail.payoutAccountId ?? "",
      deductFromCommission: detail.deductFromCommission ?? null
    });
    files.value = [];
    fileError.value = false;
    errors.value = {};
    accounts.value = [];
    await loadAccounts();
  },
  { immediate: true }
);
async function loadAccounts() {
  const request = ++sequence;
  const kolId = props.detail.kolId;
  loading.value = true;
  try {
    const data = await prepayAccounts(kolId);
    if (request === sequence && props.detail.kolId === kolId) accounts.value = data;
  } catch (error) {
    if (request === sequence) notifyError(error);
  } finally {
    if (request === sequence) loading.value = false;
  }
}
function chooseFiles(event: Event) {
  const selected = Array.from((event.target as HTMLInputElement).files ?? []);
  fileError.value = !validPrepayFiles(selected, Math.max(0, 5 - props.detail.attachments.length));
  files.value = fileError.value ? [] : selected;
}
async function save() {
  if (saving.value || loading.value || props.disabled || fileError.value) return;
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
      { ...form, deductFromCommission: form.deductFromCommission, reason: form.reason.trim() },
      files.value
    );
    ElMessage.success(t("prepay.saved"));
    emit("saved", detail);
  } catch (error) {
    errors.value = serverFieldErrors(error, Object.keys(form));
    notifyError(error);
  } finally {
    saving.value = false;
    emit("busy", false);
  }
}
</script>
<template>
  <section v-loading="loading" data-test="prepay-application-details">
    <h3>{{ t("prepay.completeDetails") }}</h3>
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      :disabled="saving || disabled"
    >
      <el-form-item
        prop="deductFromCommission"
        :label="t('prepay.deduct')"
        :error="errors.deductFromCommission"
      >
        <el-radio-group v-model="form.deductFromCommission" data-test="finance-prepay-deduct"
          ><el-radio :value="true">{{ t("prepay.yes") }}</el-radio
          ><el-radio :value="false">{{ t("prepay.no") }}</el-radio></el-radio-group
        >
      </el-form-item>
      <el-form-item prop="reason" :label="t('prepay.reason')" :error="errors.reason"
        ><el-input
          v-model="form.reason"
          type="textarea"
          maxlength="2000"
          data-test="finance-prepay-reason"
      /></el-form-item>
      <el-form-item
        prop="expectedPayDate"
        :label="t('prepay.expectedDate')"
        :error="errors.expectedPayDate"
        ><el-date-picker
          v-model="form.expectedPayDate"
          type="date"
          value-format="YYYY-MM-DD"
          data-test="finance-prepay-date"
      /></el-form-item>
      <el-form-item
        prop="payoutAccountId"
        :label="t('prepay.account')"
        :error="errors.payoutAccountId"
        ><el-select v-model="form.payoutAccountId" data-test="finance-prepay-account"
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
      <el-alert
        v-if="!loading && !accounts.length"
        :title="t('prepay.noAccounts')"
        type="warning"
        :closable="false"
      />
      <el-button
        :disabled="saving"
        data-test="finance-prepay-reload-accounts"
        @click="loadAccounts"
        >{{ t("prepay.reloadAccounts") }}</el-button
      >
      <el-form-item
        :label="t('prepay.attachments')"
        :error="fileError ? t('prepay.invalidFiles') : ''"
        ><input
          type="file"
          multiple
          accept="image/png,image/jpeg,application/pdf"
          :disabled="saving || disabled"
          data-test="finance-prepay-attachments"
          @change="chooseFiles"
      /></el-form-item>
      <p>{{ t("prepay.fileHint") }}</p>
    </el-form>
    <el-button
      :loading="saving"
      :disabled="disabled || loading || !accounts.length || fileError"
      type="primary"
      data-test="finance-prepay-details-save"
      @click="save"
      >{{ t("prepay.saveDetails") }}</el-button
    >
  </section>
</template>
<style scoped>
h3 {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
}
</style>
