<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { createTenant, updateTenant, type TenantVO } from "@/api/platform/tenant";
import type { CompanyEdition } from "@/api/types";
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
/** Editions a company can be created with (ADR-038 §1); the full product is the default. */
const editions: readonly CompanyEdition[] = ["DISTRIBUTION", "KOL_LIBRARY"];
const form = reactive({
  tenantCode: "",
  name: "",
  remark: "",
  edition: "DISTRIBUTION" as CompanyEdition
});
const rules = computed<FormRules>(() => ({
  tenantCode: editing.value ? [] : [rule(validateTenantCode)],
  name: [rule(validateTenantName)],
  remark: [rule(value => validateRemark(value))],
  edition: editing.value
    ? []
    : [{ required: true, message: t("tenant.editionRequired"), trigger: "change" }]
}));

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    serverErrors.value = {};
    form.tenantCode = props.tenant?.tenantCode ?? "";
    form.name = props.tenant?.name ?? "";
    form.remark = props.tenant?.remark ?? "";
    // Editing never changes the edition, so the form starts from the stored one.
    form.edition = props.tenant?.edition ?? "DISTRIBUTION";
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
      await createTenant({
        tenantCode: form.tenantCode,
        name: form.name.trim(),
        remark,
        edition: form.edition
      });
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
      <el-form-item :label="t('tenant.edition')" prop="edition" :error="serverErrors.edition">
        <!-- Read-only after creation: the request of an edit carries no edition (ADR-038 §1). -->
        <el-radio-group v-model="form.edition" :disabled="editing" data-test="edition">
          <el-radio v-for="option in editions" :key="option" :value="option">
            {{ t(`tenant.editionValue.${option}`) }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <p v-if="editing" class="tenant-form__edition-note">{{ t("tenant.editionLocked") }}</p>
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

<style scoped>
.tenant-form__edition-note {
  margin: -8px 0 16px 90px;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
}
</style>
