<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  updateLibraryFollowUp,
  type LibraryFollowUpRequest,
  type LibraryKolDetailVO
} from "@/api/kol-library/kol";
import {
  LIBRARY_OUTREACH_STATUSES,
  LIBRARY_SAMPLE_STATUSES,
  libraryOptionLabel
} from "@/utils/kol-library";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "LibraryFollowUpDialog" });

const { t } = useI18n();

const props = defineProps<{ modelValue: boolean; detail: LibraryKolDetailVO }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const formRef = ref<FormInstance>();
const saving = ref(false);
const form = reactive<LibraryFollowUpRequest>({
  outreachStatus: "NOT_CONTACTED",
  lastContactDate: null,
  sampleStatus: "NOT_SENT",
  trackingNo: null,
  sampleDate: null
});

/** Only the two status choices are required; the rest may stay empty (requirement 1.2.6 ①). */
const rules = computed<FormRules>(() => ({
  outreachStatus: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (!value) callback(new Error(t("common.required")));
        else callback();
      },
      trigger: "change"
    }
  ],
  sampleStatus: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (!value) callback(new Error(t("common.required")));
        else callback();
      },
      trigger: "change"
    }
  ]
}));

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    // Starts from the stored follow-up state; only this dialog's five fields change.
    Object.assign(form, {
      outreachStatus: props.detail.outreachStatus,
      lastContactDate: props.detail.lastContactDate,
      sampleStatus: props.detail.sampleStatus,
      trackingNo: props.detail.trackingNo,
      sampleDate: props.detail.sampleDate
    });
    formRef.value?.clearValidate();
  }
);

function close(): void {
  emit("update:modelValue", false);
}

async function submit(): Promise<void> {
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  saving.value = true;
  try {
    const text = (form.trackingNo ?? "").trim();
    await updateLibraryFollowUp(props.detail.id, {
      ...form,
      trackingNo: text === "" ? null : text
    });
    ElMessage.success(t("library.followUpSaved"));
    emit("saved");
    close();
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('library.updateFollowUp')"
    width="480px"
    data-test="follow-up-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" @submit.prevent>
      <el-form-item :label="t('library.field.outreachStatus')" prop="outreachStatus">
        <el-select v-model="form.outreachStatus" data-test="follow-up-outreach-status">
          <el-option
            v-for="option in LIBRARY_OUTREACH_STATUSES"
            :key="option.value"
            :label="libraryOptionLabel('outreachStatus', option.value)"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('library.field.lastContactDate')" prop="lastContactDate">
        <el-date-picker
          v-model="form.lastContactDate"
          type="date"
          value-format="YYYY-MM-DD"
          data-test="follow-up-last-contact"
        />
      </el-form-item>
      <el-form-item :label="t('library.field.sampleStatus')" prop="sampleStatus">
        <el-select v-model="form.sampleStatus" data-test="follow-up-sample-status">
          <el-option
            v-for="option in LIBRARY_SAMPLE_STATUSES"
            :key="option.value"
            :label="libraryOptionLabel('sampleStatus', option.value)"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('library.field.trackingNo')" prop="trackingNo">
        <el-input v-model="form.trackingNo" maxlength="64" data-test="follow-up-tracking-no" />
      </el-form-item>
      <el-form-item :label="t('library.field.sampleDate')" prop="sampleDate">
        <el-date-picker
          v-model="form.sampleDate"
          type="date"
          value-format="YYYY-MM-DD"
          data-test="follow-up-sample-date"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-test="follow-up-cancel" @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" data-test="follow-up-save" @click="submit">
        {{ t("common.save") }}
      </el-button>
    </template>
  </el-dialog>
</template>
