<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  createBdTransfer,
  type BdTransferResultVO,
  type TransferTarget
} from "@/api/kol/bd-transfer";
import { listBdUsers } from "@/api/system/bd-user";
import type { BdUserVO } from "@/api/types";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "BdTransferDialog" });

const props = defineProps<{ modelValue: boolean; target: TransferTarget | null }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const { t } = useI18n();

/** Select value for the "unassigned (company)" option; submitted as `toBdUserId: null`. */
const UNASSIGNED = "__unassigned__";
const MAX_KOLS = 500;

const bdUsers = ref<BdUserVO[]>([]);
const optionsLoading = ref(false);
const saving = ref(false);
const selectedBd = ref<string | null>(null);
const scheduled = ref(false);
const effectiveLocal = ref<Date | null>(null);
const reason = ref("");
const conflictCodes = ref<string[]>([]);

const targetText = computed(() => {
  const target = props.target;
  if (!target) return "";
  return target.kolIds
    ? t("bdTransfer.selectedKols", { n: target.kolIds.length })
    : t("bdTransfer.bdAllKols", {
        name: target.fromBdName ?? target.fromBdUserId ?? "",
        n: target.count ?? 0
      });
});

/** A BD transfer keeps every creator with its source BD, so the source itself is not selectable. */
const options = computed(() => {
  const source = props.target?.kolIds ? null : (props.target?.fromBdUserId ?? null);
  return bdUsers.value.filter(bd => bd.id !== source);
});

const timezoneNote = computed(() => {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const offset = -new Date().getTimezoneOffset();
  const sign = offset >= 0 ? "+" : "-";
  const minutes = Math.abs(offset);
  const hourPart = Math.floor(minutes / 60);
  const minutePart = minutes % 60;
  const text =
    minutePart === 0 ? `${hourPart}` : `${hourPart}:${String(minutePart).padStart(2, "0")}`;
  return t("bdTransfer.timezoneHint", { timezone: zone, offset: `${sign}${text}` });
});

const startOfToday = () => {
  const day = new Date();
  day.setHours(0, 0, 0, 0);
  return day.getTime();
};

/** The picker blocks past days; the exact instant is re-checked on submit. */
function disabledDate(day: Date): boolean {
  return day.getTime() < startOfToday();
}

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    selectedBd.value = null;
    scheduled.value = false;
    effectiveLocal.value = null;
    reason.value = "";
    conflictCodes.value = [];
    void loadOptions();
  }
);

async function loadOptions() {
  if (bdUsers.value.length > 0 || optionsLoading.value) return;
  optionsLoading.value = true;
  try {
    bdUsers.value = await listBdUsers();
  } catch (error) {
    notifyError(error);
  } finally {
    optionsLoading.value = false;
  }
}

function close() {
  emit("update:modelValue", false);
}

/**
 * Validates the picked instant: after now and within one calendar year (design §4.13). Returns the
 * ISO UTC string, or null when the field is missing/invalid (message shown by the caller).
 */
function effectiveAtOrNull(): { value?: string; error?: string } {
  if (!scheduled.value) return {};
  const picked = effectiveLocal.value;
  if (!picked) return { error: t("bdTransfer.pickTime") };
  const now = Date.now();
  if (picked.getTime() <= now) return { error: t("bdTransfer.timeFuture") };
  const limit = new Date(now);
  limit.setFullYear(limit.getFullYear() + 1);
  if (picked.getTime() > limit.getTime()) return { error: t("bdTransfer.timeRange") };
  return { value: picked.toISOString() };
}

async function submit() {
  const target = props.target;
  if (!target || saving.value) return;
  if (!selectedBd.value) {
    ElMessage.warning(t("bdTransfer.newBdRequired"));
    return;
  }
  if (target.kolIds && target.kolIds.length > MAX_KOLS) {
    ElMessage.warning(t("bdTransfer.tooMany", { max: MAX_KOLS }));
    return;
  }
  const effective = effectiveAtOrNull();
  if (effective.error) {
    ElMessage.warning(effective.error);
    return;
  }
  saving.value = true;
  conflictCodes.value = [];
  try {
    const result: BdTransferResultVO = await createBdTransfer({
      ...(target.kolIds ? { kolIds: target.kolIds } : { fromBdUserId: target.fromBdUserId }),
      toBdUserId: selectedBd.value === UNASSIGNED ? null : selectedBd.value,
      ...(effective.value ? { effectiveAt: effective.value } : {}),
      ...(reason.value.trim() ? { reason: reason.value.trim() } : {})
    });
    ElMessage.success(
      t("bdTransfer.success", {
        immediate: result.immediate,
        scheduled: result.scheduled,
        skipped: result.skipped
      })
    );
    emit("saved");
    close();
  } catch (error) {
    if (error instanceof ApiError && error.errorCode === "KOL_TRANSFER_EXISTS") {
      conflictCodes.value = Array.isArray(error.payload)
        ? error.payload.filter((code): code is string => typeof code === "string")
        : [];
      return;
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
    :title="t('bdTransfer.title')"
    width="480px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="90px" label-position="left" @submit.prevent>
      <el-form-item :label="t('bdTransfer.target')">
        <span data-test="transfer-target">{{ targetText }}</span>
      </el-form-item>
      <el-form-item :label="t('bdTransfer.newBd')">
        <el-select
          v-model="selectedBd"
          filterable
          :loading="optionsLoading"
          :placeholder="t('bdTransfer.newBdPlaceholder')"
          style="width: 100%"
        >
          <el-option
            v-for="bd in options"
            :key="bd.id"
            :label="t('kol.bdOption', { nickname: bd.nickname, username: bd.username })"
            :value="bd.id"
          />
          <el-option :label="t('bdTransfer.unassigned')" :value="UNASSIGNED" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('bdTransfer.effectiveTime')">
        <div class="effective">
          <el-radio-group v-model="scheduled" data-test="effective-mode">
            <el-radio-button :value="false">{{ t("bdTransfer.now") }}</el-radio-button>
            <el-radio-button :value="true">{{ t("bdTransfer.at") }}</el-radio-button>
          </el-radio-group>
          <template v-if="scheduled">
            <el-date-picker
              v-model="effectiveLocal"
              type="datetime"
              :disabled-date="disabledDate"
              :placeholder="t('bdTransfer.pickTimePlaceholder')"
              data-test="effective-at"
            />
            <span class="effective__tz">{{ timezoneNote }}</span>
          </template>
        </div>
      </el-form-item>
      <el-form-item :label="t('bdTransfer.reason')">
        <el-input
          v-model="reason"
          type="textarea"
          :rows="2"
          maxlength="255"
          show-word-limit
          :placeholder="t('bdTransfer.reasonPlaceholder')"
        />
      </el-form-item>
    </el-form>
    <el-alert
      v-if="conflictCodes.length"
      type="error"
      :closable="false"
      data-test="transfer-conflicts"
      :title="t('bdTransfer.conflicts')"
    >
      <span class="conflicts">{{ conflictCodes.join(t("link.separator")) }}</span>
    </el-alert>
    <template #footer>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" data-test="transfer-submit" @click="submit">{{
        t("common.confirm")
      }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.effective {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
  width: 100%;
}
.effective__tz {
  font-size: var(--tcrm-font-size-help);
  color: var(--tcrm-color-text-secondary);
}
.conflicts {
  word-break: break-all;
}
</style>
