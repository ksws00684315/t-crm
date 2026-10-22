<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { savePrepayApplicationDetails, type PrepayDetail } from "@/api/commission/prepay";
import { notifyError } from "@/utils/notify";
import { serverFieldErrors } from "@/utils/validation";
import { validPrepayFiles } from "@/utils/prepay";
const props = defineProps<{
  detail: PrepayDetail;
  editable: boolean;
  recipientText: string | null;
  recipientFailed?: boolean;
  disabled?: boolean;
}>();
const emit = defineEmits<{
  saved: [detail: PrepayDetail, recipientText: string];
  busy: [value: boolean];
  download: [id: string, name: string];
  retryRecipient: [];
}>();
const { t } = useI18n();
const formRef = ref<FormInstance>();
const form = reactive<{
  reason: string;
  expectedPayDate: string;
  recipientText: string;
  deductFromCommission: boolean | null;
}>({ reason: "", expectedPayDate: "", recipientText: "", deductFromCommission: null });
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
  recipientText: textRules(),
  expectedPayDate: [{ required: true, message: t("prepay.required"), trigger: "change" }]
}));
watch(
  () => props.detail,
  detail => {
    Object.assign(form, {
      reason: detail.reason ?? "",
      expectedPayDate: detail.expectedPayDate ?? "",
      recipientText: props.recipientText ?? "",
      deductFromCommission: detail.deductFromCommission ?? null
    });
    files.value = [];
    fileError.value = false;
    errors.value = {};
  },
  { immediate: true }
);
watch(
  () => props.recipientText,
  text => {
    if (text != null) form.recipientText = text;
  }
);
function chooseFiles(event: Event) {
  const selected = Array.from((event.target as HTMLInputElement).files ?? []);
  fileError.value = !validPrepayFiles(selected, Math.max(0, 5 - props.detail.attachments.length));
  files.value = fileError.value ? [] : selected;
}
async function save() {
  if (saving.value || props.disabled || !props.editable || fileError.value || props.recipientFailed)
    return;
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
    emit("saved", detail, form.recipientText);
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
      class="application-grid"
    >
      <el-form-item :label="t('prepay.amount')"
        ><div class="read-value amount">{{ detail.amountCny }} CNY</div></el-form-item
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
          data-test="finance-prepay-reason"
        />
        <div v-else class="read-value">{{ detail.reason || "—" }}</div>
      </el-form-item>
      <el-form-item prop="recipientText" :label="t('prepay.account')" :error="errors.recipientText">
        <el-input
          v-if="editable"
          v-model="form.recipientText"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          :placeholder="t('prepay.recipientHint')"
          data-test="finance-prepay-recipient"
        />
        <div v-else class="read-value" data-test="prepay-recipient-value">
          {{ recipientText ?? "—" }}
        </div>
        <el-button v-if="recipientFailed" link type="primary" @click="emit('retryRecipient')">{{
          t("prepay.retryRecipient")
        }}</el-button>
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
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 24px;
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
.file-hint {
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: 1.5;
}
@media (max-width: 640px) {
  .application-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
