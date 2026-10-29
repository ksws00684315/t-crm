<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { createLinkTemplate, editLinkTemplate } from "@/api/link/template";
import type { LinkTemplateVO } from "@/api/types";
import { validateTemplateCode, validateTemplateUrl } from "@/utils/link-template";
import { notifyError } from "@/utils/notify";
import { serverFieldErrors } from "@/utils/validation";

defineOptions({ name: "LinkTemplateFormDialog" });

const { t } = useI18n();

const props = defineProps<{
  modelValue: boolean;
  template: LinkTemplateVO | null;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const editing = computed(() => props.template !== null);
const formRef = ref<FormInstance>();
const saving = ref(false);
const serverErrors = ref<Record<string, string>>({});
const form = reactive({
  templateCode: "",
  templateUrl: ""
});

const rules: FormRules = {
  templateCode: [
    {
      trigger: "blur",
      validator: (_rule, value: string, callback) => {
        if (editing.value) {
          callback();
          return;
        }
        const message = validateTemplateCode(value ?? "");
        callback(message ? new Error(message) : undefined);
      }
    }
  ],
  templateUrl: [
    {
      trigger: "blur",
      validator: (_rule, value: string, callback) => {
        const message = validateTemplateUrl(value ?? "");
        callback(message ? new Error(message) : undefined);
      }
    }
  ]
};

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    serverErrors.value = {};
    const current = props.template;
    form.templateCode = current?.templateCode ?? "";
    form.templateUrl = current?.templateUrl ?? "";
    formRef.value?.clearValidate();
  }
);

function close() {
  emit("update:modelValue", false);
}

async function submit() {
  serverErrors.value = {};
  const valid = formRef.value ? await formRef.value.validate().catch(() => false) : false;
  if (!valid) return;
  saving.value = true;
  const body = {
    templateName: props.template?.templateName ?? form.templateCode.trim(),
    templateUrl: form.templateUrl.trim(),
    sortOrder: props.template?.sortOrder ?? 0
  };
  try {
    if (props.template) {
      await editLinkTemplate(props.template.id, body);
      ElMessage.success(t("linkTemplate.saved"));
    } else {
      await createLinkTemplate({ ...body, templateCode: form.templateCode.trim() });
      ElMessage.success(t("linkTemplate.created"));
    }
    emit("saved");
    close();
  } catch (error) {
    serverErrors.value = serverFieldErrors(error, ["templateCode", "templateUrl"]);
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="editing ? t('linkTemplate.editTitle') : t('linkTemplate.createTitle')"
    width="640px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
      <el-form-item
        :label="t('linkTemplate.code')"
        prop="templateCode"
        :error="serverErrors.templateCode"
      >
        <el-input
          v-model="form.templateCode"
          data-test="template-code"
          maxlength="32"
          :disabled="editing"
          :placeholder="t('linkTemplate.codePlaceholder')"
        />
      </el-form-item>
      <el-form-item
        :label="t('linkTemplate.url')"
        prop="templateUrl"
        :error="serverErrors.templateUrl"
      >
        <el-input
          v-model="form.templateUrl"
          data-test="template-url"
          maxlength="512"
          :placeholder="t('linkTemplate.urlPlaceholder')"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" data-test="template-save" :loading="saving" @click="submit">
        {{ t("common.save") }}
      </el-button>
    </template>
  </el-dialog>
</template>
