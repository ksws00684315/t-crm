<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { createTenant, updateTenant, type TenantVO } from "@/api/platform/tenant";
import { messageForErrorCode } from "@/api/error-codes";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";
import {
  rule,
  serverFieldErrors,
  validateRemark,
  validateTenantCode,
  validateTenantName
} from "@/utils/validation";

defineOptions({ name: "TenantFormDialog" });

const { t } = useI18n();

const props = defineProps<{ modelValue: boolean; tenant: TenantVO | null }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const editing = computed(() => props.tenant !== null);
const title = computed(() => (editing.value ? t("tenant.editTitle") : t("tenant.createTitle")));
const formRef = ref<FormInstance>();
const saving = ref(false);
const serverErrors = ref<Record<string, string>>({});
const form = reactive({ tenantCode: "", name: "", remark: "" });
const rules = computed<FormRules>(() => ({
  tenantCode: editing.value ? [] : [rule(validateTenantCode)],
  name: [rule(validateTenantName)],
  remark: [rule(value => validateRemark(value))]
}));

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    serverErrors.value = {};
    form.tenantCode = props.tenant?.tenantCode ?? "";
    form.name = props.tenant?.name ?? "";
    form.remark = props.tenant?.remark ?? "";
    formRef.value?.clearValidate();
  }
);

function close() {
  emit("update:modelValue", false);
}

async function submit() {
  serverErrors.value = {};
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  saving.value = true;
  try {
    const remark = form.remark.trim() || null;
    if (props.tenant) {
      await updateTenant(props.tenant.id, { name: form.name.trim(), remark });
    } else {
      await createTenant({ tenantCode: form.tenantCode, name: form.name.trim(), remark });
    }
    ElMessage.success(props.tenant ? t("tenant.saved") : t("tenant.created"));
    emit("saved");
    close();
  } catch (error) {
    serverErrors.value = serverFieldErrors(error, ["tenantCode", "name", "remark"]);
    if (error instanceof ApiError && error.errorCode === "TENANT_CODE_EXISTS") {
      serverErrors.value.tenantCode = messageForErrorCode(error.errorCode);
    }
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="500px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @submit.prevent>
      <el-form-item :label="t('tenant.code')" prop="tenantCode" :error="serverErrors.tenantCode">
        <el-input
          v-model="form.tenantCode"
          :disabled="editing"
          maxlength="32"
          :placeholder="t('tenant.codePlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('tenant.name')" prop="name" :error="serverErrors.name">
        <el-input v-model="form.name" maxlength="64" />
      </el-form-item>
      <el-form-item :label="t('common.remark')" prop="remark" :error="serverErrors.remark">
        <el-input v-model="form.remark" type="textarea" maxlength="500" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t("common.save") }}</el-button>
    </template>
  </el-dialog>
</template>
