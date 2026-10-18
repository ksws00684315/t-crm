<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { updatePromotionStatus } from "@/api/kol/promotion";
import type { DisplayStatus, KolVO, PromotionStatus } from "@/api/types";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";
import {
  missingPromotionFields,
  PROMOTION_STATUSES,
  promotionStatusLabelKey
} from "@/utils/promotion";

defineOptions({ name: "PromotionStatusDialog" });

const props = defineProps<{
  modelValue: boolean;
  kol: KolVO | null;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "changed"): void;
  /** The 422 answer asked for the settings; the page opens the config drawer. */
  (event: "openConfig"): void;
}>();

const { t } = useI18n();

const saving = ref(false);
const target = ref<PromotionStatus | "">("");
const errorText = ref("");
const missingKeys = ref<string[]>([]);
/** True when the backend answered 422 incomplete, even without a readable field list. */
const incomplete = ref(false);

const FIELD_KEYS = ["defaultRate", "platformTemplateIds", "landingUrl", "inviteCode"];

function fieldLabel(key: string): string {
  return FIELD_KEYS.includes(key) ? t(`promotion.field.${key}`) : key;
}

function statusLabel(status: DisplayStatus): string {
  return t(promotionStatusLabelKey(status));
}

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    target.value = "";
    errorText.value = "";
    missingKeys.value = [];
    incomplete.value = false;
  }
);

async function submit() {
  const kol = props.kol;
  if (!kol || !target.value) {
    errorText.value = t("kol.changeStatusTargetRequired");
    return;
  }
  const next = target.value;
  try {
    await ElMessageBox.confirm(
      t("kol.changeStatusConfirm", {
        code: kol.kolCode,
        name: kol.name,
        status: statusLabel(next)
      }),
      t("kol.changeStatusTitle"),
      { type: "warning" }
    );
  } catch {
    return; // cancelled
  }
  saving.value = true;
  errorText.value = "";
  missingKeys.value = [];
  incomplete.value = false;
  try {
    await updatePromotionStatus(kol.id, next);
    ElMessage.success(t("kol.statusChanged"));
    emit("changed");
    emit("update:modelValue", false);
  } catch (error) {
    if (error instanceof ApiError && error.errorCode === "KOL_PROMOTION_CONFIG_INCOMPLETE") {
      incomplete.value = true;
      missingKeys.value = missingPromotionFields(error.payload);
      const fields = missingKeys.value.map(fieldLabel).join("、");
      errorText.value =
        fields.length > 0
          ? t("promotion.configIncomplete", { fields })
          : t("errors.KOL_PROMOTION_CONFIG_INCOMPLETE");
    } else {
      notifyError(error);
    }
  } finally {
    saving.value = false;
  }
}

function goConfig(): void {
  emit("openConfig");
  emit("update:modelValue", false);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('kol.changeStatusTitle')"
    width="min(480px, calc(100vw - 24px))"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="errorText"
      class="status-error"
      type="warning"
      :closable="false"
      :title="errorText"
      data-test="status-error"
    />
    <el-form label-width="110px" @submit.prevent>
      <el-form-item :label="t('kol.changeStatusTarget')">
        <el-select v-model="target" data-test="status-target" style="width: 220px">
          <el-option
            v-for="status in PROMOTION_STATUSES"
            :key="status"
            :label="statusLabel(status)"
            :value="status"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button v-if="incomplete" type="primary" data-test="go-config" @click="goConfig">
        {{ t("promotion.goConfig") }}
      </el-button>
      <el-button type="primary" data-test="status-submit" :loading="saving" @click="submit">
        {{ t("common.confirm") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.status-error {
  margin-bottom: 12px;
}
</style>
