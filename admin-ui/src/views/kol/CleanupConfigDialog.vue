<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { cleanupKol, updateAutoCleanup } from "@/api/kol/promotion";
import type { KolVO } from "@/api/types";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "CleanupConfigDialog" });

const MIN_DAYS = 1;
const MAX_DAYS = 365;

/**
 * Per-creator cleanup (task 32 §2.2 / 33 §3.2): auto cleanup off or after N days without
 * orders, plus the immediate cleanup with a mandatory reason. The confirm text spells out that
 * the creator's Weidian product links are deleted for good.
 */
const props = defineProps<{
  modelValue: boolean;
  kol: KolVO | null;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "changed"): void;
}>();

const { t } = useI18n();

const saving = ref(false);
const cleaning = ref(false);
const form = reactive({ auto: false, days: 30 });

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    form.auto = (props.kol?.autoCleanupDays ?? null) !== null;
    form.days = props.kol?.autoCleanupDays ?? 30;
  }
);

function daysValid(): boolean {
  return Number.isInteger(form.days) && form.days >= MIN_DAYS && form.days <= MAX_DAYS;
}

async function save() {
  const kol = props.kol;
  if (!kol || saving.value) return;
  if (form.auto && !daysValid()) {
    ElMessage.warning(t("cleanup.daysUnit"));
    return;
  }
  saving.value = true;
  try {
    await updateAutoCleanup(kol.id, form.auto ? form.days : null);
    ElMessage.success(t("cleanup.saved"));
    emit("changed");
    emit("update:modelValue", false);
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}

async function immediate() {
  const kol = props.kol;
  if (!kol || cleaning.value) return;
  let reason = "";
  try {
    const result = await ElMessageBox.prompt(
      t("cleanup.immediateConfirm", { code: kol.kolCode, name: kol.name }),
      t("cleanup.immediateTitle"),
      {
        type: "warning",
        inputType: "textarea",
        inputPlaceholder: t("cleanup.reasonPlaceholder"),
        inputValidator: value => Boolean(value?.trim()) && [...(value ?? "")].length <= 200,
        inputErrorMessage: t("cleanup.reasonRequired")
      }
    );
    reason = result.value.trim();
  } catch {
    return; // cancelled
  }
  cleaning.value = true;
  try {
    await cleanupKol(kol.id, reason);
    ElMessage.success(t("cleanup.immediateStarted"));
    emit("changed");
    emit("update:modelValue", false);
  } catch (error) {
    notifyError(error);
  } finally {
    cleaning.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('cleanup.title')"
    width="520px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="110px" @submit.prevent>
      <el-form-item :label="t('kol.code')">
        <span>{{ kol?.kolCode ?? "" }}（{{ kol?.name ?? "" }}）</span>
      </el-form-item>
      <el-form-item :label="t('cleanup.autoCleanup')">
        <el-radio-group v-model="form.auto" data-test="cleanup-auto">
          <el-radio-button :value="false">{{ t("cleanup.autoOff") }}</el-radio-button>
          <el-radio-button :value="true">{{ t("cleanup.daysLabel") }}</el-radio-button>
        </el-radio-group>
        <template v-if="form.auto">
          <el-input-number
            v-model="form.days"
            data-test="cleanup-days"
            :min="MIN_DAYS"
            :max="MAX_DAYS"
            :step="1"
            step-strictly
          />
          <span class="cleanup-unit">{{ t("cleanup.daysUnit") }}</span>
        </template>
      </el-form-item>
    </el-form>
    <div class="cleanup-immediate">
      <el-button type="danger" plain data-test="cleanup-now" :loading="cleaning" @click="immediate">
        {{ t("cleanup.immediate") }}
      </el-button>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" data-test="cleanup-save" :loading="saving" @click="save">
        {{ t("common.save") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.cleanup-unit {
  margin-left: 8px;
  color: var(--tcrm-color-text-secondary);
}
.cleanup-immediate {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
</style>
