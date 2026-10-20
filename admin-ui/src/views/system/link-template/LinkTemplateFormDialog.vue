<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { createLinkTemplate, editLinkTemplate } from "@/api/link/template";
import type { LinkTemplateVO } from "@/api/types";
import {
  previewTemplateUrl,
  validateSortOrder,
  validateTemplateCode,
  validateTemplateName,
  validateTemplateUrl
} from "@/utils/link-template";
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
  templateName: "",
  templateUrl: "",
  sortOrder: 0 as number | null,
  sampleProductId: "7849640325",
  sampleInviteCode: "ABC123"
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
  templateName: [
    {
      trigger: "blur",
      validator: (_rule, value: string, callback) => {
        const message = validateTemplateName(value ?? "");
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
  ],
  sortOrder: [
    {
      trigger: "change",
      validator: (_rule, value: number | null, callback) => {
        const message = validateSortOrder(value);
        callback(message ? new Error(message) : undefined);
      }
    }
  ]
};

const preview = computed(() =>
  previewTemplateUrl(form.templateUrl.trim(), form.sampleProductId, form.sampleInviteCode)
);

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    serverErrors.value = {};
    const current = props.template;
    form.templateCode = current?.templateCode ?? "";
    form.templateName = current?.templateName ?? "";
    form.templateUrl = current?.templateUrl ?? "";
    form.sortOrder = current?.sortOrder ?? 0;
    form.sampleProductId = "7849640325";
    form.sampleInviteCode = "ABC123";
    formRef.value?.clearValidate();
  }
);

function close() {
  emit("update:modelValue", false);
}

async function submit() {
  serverErrors.value = {};
  const valid = formRef.value ? await formRef.value.validate().catch(() => false) : false;
  if (!valid || form.sortOrder == null) return;
  saving.value = true;
  const body = {
    templateName: form.templateName.trim(),
    templateUrl: form.templateUrl.trim(),
    sortOrder: form.sortOrder
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
    serverErrors.value = serverFieldErrors(error, [
      "templateCode",
      "templateName",
      "templateUrl",
      "sortOrder"
    ]);
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
    <el-form ref="formRef" :model="form" :rules="rules" label-width="120px" @submit.prevent>
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
        :label="t('linkTemplate.name')"
        prop="templateName"
        :error="serverErrors.templateName"
      >
        <el-input v-model="form.templateName" data-test="template-name" maxlength="64" />
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
      <el-form-item
        :label="t('linkTemplate.sort')"
        prop="sortOrder"
        :error="serverErrors.sortOrder"
      >
        <el-input-number
          v-model="form.sortOrder"
          data-test="template-sort"
          :min="0"
          :max="9999"
          :step="1"
          :precision="0"
        />
      </el-form-item>
      <h4 class="section">{{ t("linkTemplate.preview") }}</h4>
      <el-form-item :label="t('linkTemplate.sampleProduct')">
        <el-input v-model="form.sampleProductId" data-test="sample-product" maxlength="32" />
      </el-form-item>
      <el-form-item :label="t('linkTemplate.sampleInvite')">
        <el-input v-model="form.sampleInviteCode" data-test="sample-invite" maxlength="64" />
      </el-form-item>
      <el-form-item :label="t('linkTemplate.preview')">
        <div class="preview" data-test="template-preview">{{ preview.url }}</div>
        <p v-if="preview.fallback" class="fallback" data-test="template-fallback">
          {{ t("linkTemplate.fallback") }}
        </p>
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

<style scoped>
.section {
  margin: 4px 0 12px;
  font-weight: 600;
}

.preview {
  font-family: var(--tcrm-font-sans);
  overflow-wrap: anywhere;
}

.fallback {
  margin: 8px 0 0;
  color: var(--el-color-warning);
}
</style>
