<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  resetSystemConfig,
  updateSystemConfig,
  type SystemConfigItem
} from "@/api/system/site-config";
import { notifyError } from "@/utils/notify";
import { rule } from "@/utils/validation";

defineOptions({ name: "ConfigEditDialog" });

const props = defineProps<{
  modelValue: boolean;
  item: SystemConfigItem | null;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  /** A save or a restore landed; the page reloads the list and the site config. */
  (event: "saved"): void;
}>();

const { t } = useI18n();
const formRef = ref<FormInstance>();
const form = reactive({ value: "" });
const acting = ref(false);

/** Backend types whose value must be a whole number; the backend still checks the range. */
const INTEGER_TYPES = new Set(["integer", "minutes", "hours", "days"]);

const rules = computed<FormRules>(() => {
  if (!props.item || !INTEGER_TYPES.has(props.item.type)) return {};
  return {
    value: [
      rule(input => (/^\d+$/.test(input) ? null : t("systemConfig.integerRequired")), "change")
    ]
  };
});

const rangeVisible = computed(
  () =>
    props.item != null &&
    props.item.type !== "string" &&
    props.item.type !== "url" &&
    props.item.min != null &&
    props.item.max != null
);

/** Restore only makes sense while the stored value differs from the built-in default. */
const restorable = computed(() => {
  const item = props.item;
  return item != null && !acting.value && item.value !== item.defaultValue;
});

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    form.value = props.item?.value ?? "";
    formRef.value?.clearValidate();
  }
);

function close(): void {
  emit("update:modelValue", false);
}

async function save(): Promise<void> {
  const item = props.item;
  if (!item || !formRef.value) return;
  if (!(await formRef.value.validate().catch(() => false))) return;
  acting.value = true;
  try {
    await updateSystemConfig(item.key, form.value);
    ElMessage.success(t("systemConfig.saved"));
    emit("saved");
    close();
  } catch (error) {
    notifyError(error);
  } finally {
    acting.value = false;
  }
}

async function restore(): Promise<void> {
  const item = props.item;
  if (!item || !restorable.value) return;
  try {
    await ElMessageBox.confirm(
      t("systemConfig.resetConfirm", { name: t(item.nameKey) }),
      t("systemConfig.resetTitle"),
      { type: "warning" }
    );
  } catch {
    return;
  }
  acting.value = true;
  try {
    await resetSystemConfig(item.key);
    ElMessage.success(t("systemConfig.restored"));
    emit("saved");
    close();
  } catch (error) {
    notifyError(error);
  } finally {
    acting.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('systemConfig.editTitle')"
    width="520px"
    data-test="config-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form
      v-if="item"
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="96px"
      @submit.prevent
    >
      <el-form-item :label="t('systemConfig.key')">
        <code class="config-dialog__key" data-test="config-dialog-key">{{ item.key }}</code>
      </el-form-item>
      <el-form-item :label="t('systemConfig.description')">
        <span data-test="config-dialog-name">{{ t(item.nameKey) }}</span>
      </el-form-item>
      <el-form-item :label="t('systemConfig.value')" prop="value">
        <el-input v-model="form.value" data-test="config-value-input" />
        <div class="config-dialog__hint" data-test="config-value-hint">
          {{ t("systemConfig.defaultValueHint", { value: item.defaultValue || "—" }) }}
          <template v-if="rangeVisible">
            · {{ t("systemConfig.rangeHint", { min: item.min, max: item.max }) }}
          </template>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-test="config-restore" :disabled="!restorable" @click="restore">{{
        t("systemConfig.restoreDefault")
      }}</el-button>
      <el-button data-test="config-cancel" @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="acting" data-test="config-save" @click="save">{{
        t("common.save")
      }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.config-dialog__key {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  line-height: var(--tcrm-line-height-body);
  overflow-wrap: anywhere;
}

/* The hint is auxiliary text. The dialog body teleports out of #app, so the global
   `.el-overlay *` body-size rule (which carries !important) is the one to outspecify. */
.el-overlay .config-dialog__hint {
  width: 100%;
  font-size: var(--tcrm-font-size-help) !important;
  line-height: var(--tcrm-line-height-help) !important;
  color: var(--el-text-color-secondary);
}
</style>
